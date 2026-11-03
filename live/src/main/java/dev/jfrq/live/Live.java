// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;

import dev.jfrq.cli.Args;
import dev.jfrq.cli.Main;
import dev.jfrq.core.util.Durations;

/**
 * {@code jfrq-live}: the loop that works on a running JVM. A live recording cannot be read
 * in place (its last chunk is open; see {@code docs/design/README.md} section 3), so each
 * question is asked of a dump: {@code full} for everything the recording holds,
 * {@code delta} for what happened since the previous dump, {@code again} for the previous
 * window once more. A cursor file per JVM remembers where the last dump stopped. Every
 * dump is followed by the span check (the file's chunk headers, against the window
 * that was asked for) and, after {@code --}, by the {@code jfrq} question to run on it.
 *
 * <pre>
 *   jfrq-live 4242 start --max-age 10m
 *   jfrq-live 4242 full  -- stalls --thread 'event-loop-*'
 *   jfrq-live 4242 delta -- stalls --thread 'event-loop-*'
 *   jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr
 * </pre>
 */
public final class Live {

    /** The settings docs/RECORDING.md recommends on top of a JDK profile: short waits recorded, I/O unthrottled. */
    static final Map<String, String> RECOMMENDED = Map.ofEntries(
            Map.entry("jdk.JavaMonitorEnter#threshold", "1 ms"),
            Map.entry("jdk.ThreadPark#threshold", "1 ms"),
            Map.entry("jdk.ThreadSleep#threshold", "1 ms"),
            Map.entry("jdk.SocketRead#threshold", "1 ms"),
            Map.entry("jdk.SocketWrite#threshold", "1 ms"),
            Map.entry("jdk.FileRead#threshold", "1 ms"),
            Map.entry("jdk.FileWrite#threshold", "1 ms"),
            Map.entry("jdk.SocketRead#throttle", "off"),
            Map.entry("jdk.SocketWrite#throttle", "off"),
            Map.entry("jdk.FileRead#throttle", "off"),
            Map.entry("jdk.FileWrite#throttle", "off"),
            Map.entry("jdk.ExecutionSample#period", "10 ms"),
            Map.entry("jdk.NativeMethodSample#period", "10 ms"),
            Map.entry("jdk.ObjectAllocationSample#throttle", "1000/s"));

    static final String USAGE = """
            jfrq-live %s - ask a running JVM's flight recording one question at a time

            usage: jfrq-live <pid> <command> [options] [-- <jfrq command> [jfrq options]]

            commands:
              status  the JVM's recordings (state, bounds) and this tool's cursor for it
              start   start a recording in the JVM: the JDK 'profile' settings with the thresholds
                      docs/RECORDING.md recommends, or --settings NAME for another JDK profile as
                      recommended, or --settings FILE.jfc taken as it is; then --set on top
              bound   set --max-age / --max-size on the running recording
              full    dump everything the recording holds; the cursor moves to the dump's end
              delta   dump what happened since the cursor; the cursor moves to the dump's end
              again   dump the previous window once more; the cursor stays
              stop    stop and close the recording
              watch   follow the JVM for --for: a delta window every --every with each --ask question
                      run on it, a sample of its MBeans every --sample into samples.tsv, a class
                      histogram per window with --histo, and a summary table at the end
              histo   the JVM's class histogram, as jcmd GC.class_histogram prints it (a full GC first)

            jfrq-live growth H1 H2 [H3 ...] [--top N]
                      the classes whose instances and bytes grew from each histogram to the next,
                      then those whose bytes grew at every step while their instances did not

            options:
              --recording ID|NAME  which recording, when the JVM runs more than one; a name
                                   two recordings share is an error: use the id
              --out FILE           where the dump goes, replacing FILE (default
                                   <pid>-<command>-<HHmmss.SSS>Z.jfr here, UTC, never replaced);
                                   watch: a new or empty directory (default
                                   watch-<pid>-<yyyyMMdd-HHmm> here, UTC); histo: the file
                                   (default standard output)
              --state DIR          where cursors are kept (default ~/.jfrq/live)
              --max-age D          keep this much recent data: 10m, 1h, in whole seconds (a
                                   fraction is rounded up); 0 or infinity removes the bound
                                   (start, bound)
              --max-size SIZE      keep this much data: 200MB, 1GB; 0 removes the bound (start, bound)
              --settings NAME|FILE JDK profile name (default, profile) or a .jfc file (start)
              --set EVENT#SETTING=VALUE[,...]  settings on top of those, as stalls advises them:
                                   --set jdk.ExecutionSample#period=1ms (start)
              --name NAME          the recording's name, not one the JVM has (start; default jfrq-live)
              --every D            a window this often (watch)
              --for D              how long to watch; it ends by itself (watch)
              --sample D           a sample this often (watch; default 60s)
              --ask 'QUESTION'     a jfrq question run on each window, its answer in
                                   wNN-HHmm.<command>.txt; repeatable (watch)
              --histo              a class histogram at the start and after each window; each
                                   one is a full GC in the JVM (watch)
              --version, --help

            after --: a jfrq command and its options, run on the dump; the file is inserted for you,
            and the question is checked before anything is dumped:
              jfrq-live 4242 delta -- stalls --thread 'event-loop-*'
              jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr

            every dump prints its actual span next to the window asked for: dumps are whole
            chunks, and a window the JVM has already discarded (max-age, max-size) comes back
            shorter than asked. exit status: 0 ok, 1 the JVM or the dump failed, 2 usage error
            """.formatted(Main.VERSION);

    private static final String DEFAULT_NAME = "jfrq-live";
    private static final DateTimeFormatter DIR_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("HHmmss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Set<String> FLAGS = Set.of("help", "version");
    private static final Pattern SIZE = Pattern.compile("\\s*(\\d+)\\s*([kKmMgGtT]?)[bB]?\\s*");

    private final PrintStream err;
    private final PrintStream out;

    Live(final PrintStream out, final PrintStream err) {
        this.out = out;
        this.err = err;
    }

    static void main(final String[] argv) {
        // UTF-8 on every platform, for its own lines and for jfrq's answer below them (Main#utf8).
        final PrintStream out = Main.utf8(FileDescriptor.out);
        final PrintStream err = Main.utf8(FileDescriptor.err);
        final int status = new Live(out, err).run(argv);
        out.flush();
        err.flush();
        System.exit(status);
    }

    /**
     * Writes one line ending in {@code '\n'}, never the platform separator, so a dump reads the
     * same on every machine and {@code jfrq}'s own output below it is terminated identically.
     */
    static void line(final PrintStream stream, final String text) {
        stream.print(text + '\n');
    }

    /** Runs a command and returns the exit status without calling {@link System#exit}. */
    int run(final String[] argv) {
        final int status = command(argv);
        // Once jfrq has run, its own check has spoken for the shared stream.
        if (status == 0 && out.checkError()) {
            line(err, "jfrq-live: cannot write to standard output");
            return 1;
        }
        return status;
    }

    private int command(final String[] argv) {
        try {
            if (argv.length == 0 || argv[0].equals("--help") || argv[0].equals("-h") || argv[0].equals("help")) {
                out.print(USAGE);
                return 0;
            }
            if (argv[0].equals("--version")) {
                line(out, "jfrq-live " + Main.VERSION);
                return 0;
            }
            if (argv[0].equals("growth")) {
                return growth(Arrays.copyOfRange(argv, 1, argv.length));
            }
            final String pid = pid(argv[0]);
            if (argv.length == 1 || argv[1].equals("--")) {
                throw new Args.UsageException("missing command after the pid");
            }
            final String command = argv[1];
            int separator = -1;
            for (int i = 2; i < argv.length && separator < 0; i++) {
                if (argv[i].equals("--")) {
                    separator = i;
                }
            }
            final List<String[]> asks = new ArrayList<>();
            final String[] given = Arrays.copyOfRange(argv, 2, separator < 0 ? argv.length : separator);
            // --ask is the one option given more than once, so it is taken out before Args sees the rest.
            final String[] rest = command.equals("watch") ? asks(given, asks) : given;
            final String[] question = separator < 0 ? new String[0] : Arrays.copyOfRange(argv, separator + 1, argv.length);
            final Args args = Args.parse(rest, valued(command),
                    command.equals("watch") ? union(FLAGS, "histo") : FLAGS);
            if (args.flag("help")) {
                out.print(USAGE);
                return 0;
            }
            if (args.flag("version")) {
                line(out, "jfrq-live " + Main.VERSION);
                return 0;
            }
            if (!args.positional().isEmpty()) {
                throw new Args.UsageException("unexpected argument '" + args.positional().getFirst() + "'");
            }
            final boolean dumps = command.equals("full") || command.equals("delta") || command.equals("again");
            if (question.length > 0 && !dumps) {
                throw new Args.UsageException("only full, delta and again take a jfrq command after --");
            }
            if (separator >= 0 && question.length == 0) {
                throw new Args.UsageException("nothing after --: name the jfrq command to run on the dump");
            }
            if (command.equals("watch")) {
                return new Watch(plan(pid, args, asks), out, err).run();
            }
            // Everything that can be checked without the JVM is checked before attaching: a typo
            // costs no attach, no dump and no cursor move.
            final Path file = dumps ? dumpFile(args, pid, command) : null;
            if (question.length > 0) {
                Main.check(question, file);
            }
            final List<String> notes = new ArrayList<>();
            final Map<String, String> limits = command.equals("start") || command.equals("bound")
                    ? bounds(args, notes) : Map.of();
            if (command.equals("bound") && limits.isEmpty()) {
                throw new Args.UsageException("bound needs --max-age and/or --max-size");
            }
            final Map<String, String> sets = args.option("set").map(Live::parseSets).orElse(Map.of());
            final Jvm jvm = Jvm.attach(pid, err);
            final int status;
            try {
                status = switch (command) {
                    case "status" -> status(jvm, args);
                    case "start" -> start(jvm, args, limits, sets, notes);
                    case "bound" -> bound(jvm, args, limits, notes);
                    case "stop" -> stop(jvm, args);
                    case "histo" -> histo(jvm, args);
                    default -> dump(jvm, command, args, question, file);
                };
            } catch (final IOException | RuntimeException | Error e) {
                Closing.after(e, jvm::close);
                throw e;
            }
            release(jvm, pid);
            return status;
        } catch (final Args.UsageException e) {
            line(err, "jfrq-live: " + e.getMessage());
            line(err, "Run 'jfrq-live --help' for usage.");
            return 2;
        } catch (final IOException e) {
            line(err, "jfrq-live: " + failure(e));
            return 1;
        } catch (final IllegalArgumentException | IllegalStateException e) {
            // The recorder's own refusals: a recording in the wrong state, a setting it does not know.
            line(err, "jfrq-live: the JVM refused: " + e.getMessage());
            return 1;
        } catch (final RuntimeException e) {
            line(err, "jfrq-live: " + failure(e));
            return 1;
        }
    }

    /**
     * Closes the connection after the command succeeded. The answer is already printed and
     * true, so a connection that will not close is worth a warning, not a failed exit.
     */
    void release(final Closeable connection, final String pid) {
        try {
            connection.close();
        } catch (final IOException e) {
            line(err, "jfrq-live: warning: the connection to JVM " + pid + " did not close cleanly: " + failure(e));
        }
    }

    /**
     * A failure as one line, the one form every message of {@code jfrq-live} uses: an
     * {@link IOException} by its message, as {@code jfrq} describes it; a lost connection as
     * such, which a JMX proxy method whose interface declares no {@code IOException} (most of
     * {@link FlightRecorderMXBean}) reports as an {@link UndeclaredThrowableException} around
     * it; anything else as {@code failed: } and the exception.
     */
    static String failure(final Throwable e) {
        if (e instanceof final IOException io) {
            return Main.describe(io);
        }
        Throwable cause = e;
        while (cause instanceof final UndeclaredThrowableException u && u.getUndeclaredThrowable() != null) {
            cause = u.getUndeclaredThrowable();
        }
        if (cause instanceof final IOException io) {
            return "the connection to the JVM failed: " + Main.describe(io);
        }
        return "failed: " + cause;
    }

    /** The pid as the JVM spells it: {@code 080524} is process 80524, with one cursor file. */
    private static String pid(final String text) {
        if (text.isEmpty() || !isDigits(text)) {
            throw new Args.UsageException("the first argument is the JVM's pid, not '" + text + "'");
        }
        try {
            return Long.toString(Long.parseLong(text));
        } catch (final NumberFormatException e) {
            throw new Args.UsageException("the first argument is the JVM's pid, not '" + text + "'");
        }
    }

    /**
     * {@code --out}, or a name made from the pid, the command and the time to the millisecond;
     * two default-named dumps in the same second are two files.
     */
    static Path dumpFile(final Args args, final String pid, final String command) {
        final Optional<String> out = args.option("out");
        if (out.isPresent()) {
            return Path.of(out.orElseThrow());
        }
        return Path.of(pid + "-" + command + "-" + FILE_STAMP.format(Instant.now()) + ".jfr");
    }

    /** The options each command accepts, so an option in the wrong place is a usage error. */
    private static Set<String> valued(final String command) {
        final Set<String> common = Set.of("recording", "state");
        return switch (command) {
            case "status", "stop" -> common;
            case "bound" -> union(common, "max-age", "max-size");
            case "start" -> union(common, "max-age", "max-size", "settings", "name", "set");
            case "full", "delta", "again" -> union(common, "out");
            case "histo" -> Set.of("out");
            case "watch" -> union(common, "out", "every", "for", "sample");
            default -> throw new Args.UsageException("unknown command '" + command + "'");
        };
    }

    /**
     * Moves every {@code --ask Q} or {@code --ask=Q} of {@code given} into {@code asks}, split
     * into words; returns the rest.
     */
    static String[] asks(final String[] given, final List<String[]> asks) {
        final List<String> rest = new ArrayList<>();
        for (int i = 0; i < given.length; i++) {
            final String text;
            if (given[i].equals("--ask")) {
                if (i + 1 >= given.length) {
                    throw new Args.UsageException("--ask needs a value");
                }
                text = given[++i];
            } else if (given[i].startsWith("--ask=")) {
                text = given[i].substring("--ask=".length());
            } else {
                rest.add(given[i]);
                continue;
            }
            final List<String> words = words(text);
            if (words.isEmpty()) {
                throw new Args.UsageException("--ask needs a jfrq question, such as 'health'");
            }
            asks.add(words.toArray(String[]::new));
        }
        return rest.toArray(String[]::new);
    }

    /**
     * A question as the shell would split it: words between spaces, with {@code '...'} and
     * {@code "..."} keeping spaces and the other quote inside one word; no escapes.
     */
    static List<String> words(final String text) {
        final List<String> words = new ArrayList<>();
        final StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            throw new Args.UsageException("--ask " + text + ": a quote is not closed");
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    /**
     * The watch's plan, checked before attaching: the durations, each question as {@code jfrq}
     * will read it, and the directory, which must be new or empty so that no file of an earlier
     * watch is taken for one of this.
     */
    static Watch.Plan plan(final String pid, final Args args, final List<String[]> asks) throws IOException {
        if (args.option("every").isEmpty() || args.option("for").isEmpty()) {
            throw new Args.UsageException("watch needs --every and --for: how often a window, and for how long");
        }
        final long everyNanos = args.durationOption("every", "0", false);
        final long forNanos = args.durationOption("for", "0", false);
        final long sampleNanos = args.durationOption("sample", "60s", false);
        if (everyNanos == Durations.INFINITE || forNanos == Durations.INFINITE || sampleNanos == Durations.INFINITE) {
            throw new Args.UsageException("watch takes finite durations: it ends by itself");
        }
        final Path dir = args.option("out").map(Path::of).orElseGet(() -> Path.of("watch-" + pid + "-"
                + DIR_STAMP.format(Instant.now())));
        for (final String[] q : asks) {
            Main.check(q, dir.resolve("w01.jfr"));
        }
        if (Files.isDirectory(dir)) {
            try (final Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) {
                    throw new Args.UsageException("--out " + dir
                            + " already holds files: name a new or empty directory");
                }
            }
        }
        Files.createDirectories(dir);
        return new Watch.Plan(pid, args, everyNanos, forNanos, sampleNanos, dir, args.flag("histo"), List.copyOf(asks));
    }

    /** {@code histo}: the class histogram to {@code --out}, else to standard output. */
    private int histo(final Jvm jvm, final Args args) throws IOException {
        final Optional<String> to = args.option("out");
        if (to.isEmpty()) {
            out.print(jvm.classHistogram(Dumps.HISTO_TIMEOUT));
            return 0;
        }
        Dumps.histogram(jvm, Dumps.HISTO_TIMEOUT, Path.of(to.orElseThrow()), out);
        return 0;
    }

    /** {@code jfrq-live growth H1 H2 ...}: no JVM, only the files. */
    private int growth(final String[] argv) throws IOException {
        final Args args = Args.parse(argv, Set.of("top"), FLAGS);
        if (args.flag("help")) {
            out.print(USAGE);
            return 0;
        }
        if (args.positional().size() < 2) {
            throw new Args.UsageException("growth needs two class histograms or more, oldest first");
        }
        final List<Histogram> histograms = new ArrayList<>();
        for (final String f : args.positional()) {
            histograms.add(Histogram.read(Path.of(f)));
        }
        out.print(Histogram.table(args.positional(), histograms, args.top()));
        return 0;
    }

    private static boolean isDigits(final String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static Set<String> union(final Set<String> base, final String... more) {
        final Set<String> s = new HashSet<>(base);
        s.addAll(Arrays.asList(more));
        return s;
    }

    private int status(final Jvm jvm, final Args args) throws IOException {
        line(out, jvmLine(jvm));
        final List<RecordingInfo> recordings = jvm.flightRecorder().getRecordings();
        if (recordings.isEmpty()) {
            line(out, "Recording  none: start one with 'jfrq-live " + jvm.pid()
                    + " start', or run the JVM with -XX:StartFlightRecording");
        }
        for (final RecordingInfo r : recordings) {
            line(out, recordingLine(r));
        }
        line(out, cursorLine(Cursor.load(Dumps.stateDir(args), jvm.pid(), jvm.startTime())));
        return 0;
    }

    private int start(final Jvm jvm, final Args args, final Map<String, String> limits, final Map<String, String> sets,
            final List<String> notes) throws IOException {
        final FlightRecorderMXBean fr = jvm.flightRecorder();
        final String settings = args.option("settings").orElse("profile");
        final String name = args.option("name").orElse(DEFAULT_NAME);
        for (final RecordingInfo r : fr.getRecordings()) {
            if (name.equals(r.getName())) {
                // Two recordings of one name make --recording NAME a guess.
                throw new Args.UsageException("JVM " + jvm.pid() + " already has a recording named '" + name + "' (id "
                        + r.getId() + ", " + r.getState() + "): give this one another --name");
            }
        }
        final Map<String, String> options = new HashMap<>(limits);
        options.put("name", name);
        final long id = startRecording(fr, settings, options, sets);
        final RecordingInfo started = recording(fr, id);
        line(out, jvmLine(jvm));
        line(out, recordingLine(started));
        for (final String note : notes) {
            line(out, note);
        }
        line(out, settingsLine(settings, sets));
        // What the recording ended up with, not what was asked: --max-age 0 or infinity is no bound either.
        if (started.getMaxAge() == 0 && started.getMaxSize() == 0) {
            line(out, Dumps.unboundedWarning(jvm));
        }
        return 0;
    }

    /** The settings {@code --set} accepts a value of, with the values each takes. */
    private static final Map<String, Pattern> SET_VALUES = Map.of(
            "period", Pattern.compile("(?:\\d+\\s*(?:ns|us|ms|s|m|h|d)|0|everyChunk|beginChunk|endChunk)"),
            "threshold", Pattern.compile("(?:\\d+\\s*(?:ns|us|ms|s|m|h|d)|0)"),
            "throttle", Pattern.compile("(?:\\d+\\s*/\\s*(?:ns|us|ms|s|m|h|d)|off)"),
            "enabled", Pattern.compile("true|false"),
            "stackTrace", Pattern.compile("true|false"));

    /**
     * {@code --set}'s list, {@code jdk.ExecutionSample#period=1ms,jdk.ThreadPark#threshold=0ms},
     * as the setting each names and its value, in the order given. The recorder takes a value
     * it cannot read without a word (a period of {@code 1 msec} became {@code everyChunk},
     * measured on JDK 25), so a value of the settings it can be checked for is checked here,
     * before the JVM is attached to; any other setting's value goes through as given.
     */
    static Map<String, String> parseSets(final String text) {
        final Map<String, String> sets = new LinkedHashMap<>();
        for (final String item : text.split(",", -1)) {
            final int hash = item.indexOf('#');
            final int eq = item.indexOf('=');
            if (hash <= 0 || eq < hash + 2 || eq == item.length() - 1) {
                throw new Args.UsageException("--set takes EVENT#SETTING=VALUE[,...], not '" + item + "'");
            }
            final String key = item.substring(0, eq).strip();
            final String value = item.substring(eq + 1).strip();
            final Pattern values = SET_VALUES.get(key.substring(key.indexOf('#') + 1));
            if (values != null && !values.matcher(value).matches()) {
                throw new Args.UsageException("--set " + key + "=" + value + ": not a value the recorder reads as one");
            }
            if (sets.put(key, value) != null) {
                throw new Args.UsageException("--set names " + key + " twice");
            }
        }
        return sets;
    }

    /**
     * Creates, configures and starts a recording; one the JVM refuses to configure or start is
     * closed again (G-4.2), and the refusal, not a failure to close, is what is thrown.
     * {@code sets} go on top of the settings, profile or file; each must name a setting the
     * configured recording already has, since the recorder ignores one it does not know.
     */
    static long startRecording(final FlightRecorderMXBean fr, final String settings, final Map<String, String> options,
            final Map<String, String> sets) throws IOException {
        final long id = fr.newRecording();
        try {
            if (settings.endsWith(".jfc")) {
                fr.setConfiguration(id, Files.readString(Path.of(settings), StandardCharsets.UTF_8));
            } else {
                fr.setPredefinedConfiguration(id, settings);
            }
            if (!settings.endsWith(".jfc") || !sets.isEmpty()) {
                // setRecordingSettings replaces the whole map, so the configuration is re-read and overlaid.
                final Map<String, String> merged = new HashMap<>(fr.getRecordingSettings(id));
                if (!settings.endsWith(".jfc")) {
                    merged.putAll(RECOMMENDED);
                }
                for (final Map.Entry<String, String> set : sets.entrySet()) {
                    if (!merged.containsKey(set.getKey())) {
                        throw new Args.UsageException("--set " + set.getKey() + ": the recording's settings have no "
                                + "such event setting");
                    }
                    merged.put(set.getKey(), set.getValue());
                }
                fr.setRecordingSettings(id, merged);
            }
            fr.setRecordingOptions(id, options);
            fr.startRecording(id);
        } catch (final IOException | RuntimeException | Error e) {
            Closing.after(e, () -> fr.closeRecording(id));
            throw e;
        }
        return id;
    }

    /**
     * What the recording was actually configured with. The operator is at the keyboard now
     * and this is the cheapest moment to answer "did my settings take effect"; the only
     * other way is {@code jfrq info} on the first dump, one dump later.
     */
    static String settingsLine(final String settings, final Map<String, String> sets) {
        final StringBuilder set = new StringBuilder();
        for (final Map.Entry<String, String> e : sets.entrySet()) {
            set.append(set.isEmpty() ? "; then --set " : ", ").append(e.getKey()).append('=').append(e.getValue());
        }
        if (settings.endsWith(".jfc")) {
            return String.format(Locale.ROOT, "Settings   %s, taken as it is%s", settings, set);
        }
        final List<String> thresholds = new ArrayList<>();
        final List<String> throttlesOff = new ArrayList<>();
        final List<String> periods = new ArrayList<>();
        final List<String> throttles = new ArrayList<>();
        for (final Map.Entry<String, String> e : new TreeMap<>(RECOMMENDED).entrySet()) {
            final int hash = e.getKey().indexOf('#');
            final String event = e.getKey().substring("jdk.".length(), hash);
            switch (e.getKey().substring(hash + 1)) {
                case "threshold" -> thresholds.add(event + " " + e.getValue());
                case "period" -> periods.add(event + " " + e.getValue());
                case "throttle" -> {
                    if ("off".equals(e.getValue())) {
                        throttlesOff.add(event);
                    } else {
                        throttles.add(event + " " + e.getValue());
                    }
                }
                default -> throw new IllegalStateException(e.getKey());
            }
        }
        final StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Settings   the JDK's '%s' settings, then: ",
                settings));
        sb.append("thresholds ").append(String.join(", ", thresholds));
        sb.append("; throttle off for ").append(String.join(", ", throttlesOff));
        sb.append("; ").append(String.join(", ", throttles));
        sb.append("; sampling ").append(String.join(", ", periods));
        return sb.append(set).toString();
    }

    private int bound(final Jvm jvm, final Args args, final Map<String, String> limits, final List<String> notes)
            throws IOException {
        final FlightRecorderMXBean fr = jvm.flightRecorder();
        final RecordingInfo r = Dumps.pick(jvm, args);
        fr.setRecordingOptions(r.getId(), limits);
        line(out, recordingLine(recording(fr, r.getId())));
        for (final String note : notes) {
            line(out, note);
        }
        return 0;
    }

    /**
     * Stops the recording if it is running, then closes it. A recording that is not running
     * (a stopped one, or the clone of a dump that was killed outright) is only closed:
     * stopping it is an error the recorder would refuse, and it would stay behind.
     */
    private int stop(final Jvm jvm, final Args args) throws IOException {
        final FlightRecorderMXBean fr = jvm.flightRecorder();
        final RecordingInfo r = Dumps.pick(jvm, args);
        final boolean running = "RUNNING".equals(r.getState());
        if (running) {
            fr.stopRecording(r.getId());
            // A recording with a destination (-XX:StartFlightRecording=filename=...) is written
            // there when it stops and closed by the JVM itself: there is nothing left to close.
            if (!exists(fr, r.getId())) {
                line(out, String.format(Locale.ROOT, "Stopped    %d  %s  and the JVM wrote it to %s and closed it",
                        r.getId(), r.getName(), r.getDestination()));
                return 0;
            }
        }
        fr.closeRecording(r.getId());
        line(out, running
                ? String.format(Locale.ROOT, "Stopped    %d  %s  and closed it; the JVM discards its data", r.getId(), r.getName())
                : String.format(Locale.ROOT, "Closed     %d  %s  (it was %s); the JVM discards its data", r.getId(),
                        r.getName(), r.getState()));
        return 0;
    }

    private static boolean exists(final FlightRecorderMXBean fr, final long id) {
        for (final RecordingInfo r : fr.getRecordings()) {
            if (r.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private int dump(final Jvm jvm, final String command, final Args args, final String[] question, final Path file)
            throws IOException {
        final RecordingInfo r = Dumps.pick(jvm, args);
        // A named --out is the caller's to replace; a default name never replaces an earlier dump.
        final boolean replace = args.option("out").isPresent();
        final Dumps.Taken taken = Dumps.take(jvm, command, r, Dumps.stateDir(args), file, replace, err);
        // A question that answers in JSON owns standard output: a program reads it whole, so
        // what the dump itself says goes to standard error instead of ahead of the document.
        final PrintStream to = question.length > 0 && Main.answersInJson(question) ? err : out;
        Dumps.check(taken.snapshot(), taken.chunks(), taken.window(), command, r, jvm, to);
        line(to, cursorLine(taken.cursor()));
        if (question.length == 0) {
            return 0;
        }
        line(to, "");
        return Main.run(questionOn(question, file), out, err);
    }

    /**
     * {@code question} with {@code file} as its recording: {@code stalls --thread x} asks
     * {@code stalls file --thread x}.
     */
    static String[] questionOn(final String[] question, final Path file) {
        final List<String> argv = new ArrayList<>(question.length + 1);
        argv.add(question[0]);
        argv.add(file.toString());
        argv.addAll(Arrays.asList(question).subList(1, question.length));
        return argv.toArray(String[]::new);
    }

    /**
     * {@code --max-age} and {@code --max-size} as recorder options, empty when neither was
     * given; checked before attaching. JFR keeps its age bound in whole seconds, so an age
     * under a second is refused rather than sent as a zero that means "no bound", and a
     * fraction is rounded up, with a note in {@code notes}.
     */
    private static Map<String, String> bounds(final Args args, final List<String> notes) {
        final Map<String, String> options = new HashMap<>();
        final Optional<String> age = args.option("max-age");
        if (age.isPresent()) {
            final long nanos = args.durationOption("max-age", "0", true);
            if (nanos > 0 && nanos < 1_000_000_000L) {
                throw new Args.UsageException("--max-age " + age.orElseThrow()
                        + ": the JVM keeps whole seconds; give 1s or more, or 0 to remove the bound");
            }
            // "infinity" keeps everything, which is the recorder's 0.
            final long seconds = nanos == Durations.INFINITE ? 0
                    : nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1);
            // Rounded up, the last fraction of a second below the longest duration overflows a
            // Duration's nanoseconds, here and in the JVM's own recorder.
            final long longest = Long.MAX_VALUE / 1_000_000_000L;
            if (seconds > longest) {
                throw new Args.UsageException("--max-age " + age.orElseThrow() + ": the JVM keeps at most "
                        + longest + "s; give 0 or infinity to remove the bound");
            }
            if (nanos != Durations.INFINITE && nanos % 1_000_000_000L != 0) {
                notes.add("Note       --max-age " + age.orElseThrow() + " rounded up to "
                        + Durations.format(Duration.ofSeconds(seconds)) + ": the JVM keeps whole seconds");
            }
            // The recorder takes a timespan with a unit; 0 is its own "no bound".
            options.put("maxAge", seconds + " s");
        }
        if (args.option("max-size").isPresent()) {
            options.put("maxSize", Long.toString(parseSize(args.option("max-size").orElseThrow())));
        }
        return options;
    }

    /** {@code 200MB}, {@code 1g}, {@code 0}: bytes, decimal units as every size jfrq prints. */
    static long parseSize(final String text) {
        final Matcher m = SIZE.matcher(text);
        if (!m.matches()) {
            throw new Args.UsageException("--max-size: not a size: '" + text + "' (e.g. 200MB, 1GB, 0)");
        }
        final long unit = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "" -> 1L;
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "g" -> 1_000_000_000L;
            default -> 1_000_000_000_000L;
        };
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (final NumberFormatException e) {
            throw new Args.UsageException("--max-size: too large: " + text);
        }
        if (n > Long.MAX_VALUE / unit) {
            throw new Args.UsageException("--max-size: too large: " + text);
        }
        return n * unit;
    }

    private static RecordingInfo recording(final FlightRecorderMXBean fr, final long id) throws IOException {
        for (final RecordingInfo r : fr.getRecordings()) {
            if (r.getId() == id) {
                return r;
            }
        }
        throw new IOException("the JVM lost recording " + id);
    }

    private static String jvmLine(final Jvm jvm) {
        // The pid, not RuntimeMXBean.getName(): see Jvm.attach for what asking for the name costs.
        // The host it would add is always this machine, since the command takes a local pid.
        return String.format(Locale.ROOT, "JVM        %s, started %s, %s", jvm.pid(),
                Dumps.DATE_TIME.format(Instant.ofEpochMilli(jvm.startTime())), jvm.vmVersion());
    }

    private static String recordingLine(final RecordingInfo r) {
        final String since = r.getStartTime() == 0 ? "not started"
                : "since " + Dumps.TIME.format(Instant.ofEpochMilli(r.getStartTime()));
        return String.format(Locale.ROOT, "Recording  %-3d %-16s %-8s %s, %s, %s", r.getId(), r.getName(), r.getState(),
                since, Dumps.bounds(r), r.isToDisk() ? "to disk" : "in memory");
    }

    private static String cursorLine(final Cursor c) {
        if (c.next() == null) {
            return "Cursor     none: 'full' sets it (" + c.file() + ")";
        }
        return String.format(Locale.ROOT, "Cursor     next delta from %s; last window %s (%s)",
                Dumps.TIME.format(c.next()), Dumps.describe(c.lastWindow()), c.file());
    }
}
