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
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;

import dev.jfrq.cli.Args;
import dev.jfrq.cli.Main;
import dev.jfrq.core.jfr.Chunks;
import dev.jfrq.core.util.Bytes;
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

            options:
              --recording ID|NAME  which recording, when the JVM runs more than one; a name
                                   two recordings share is an error: use the id
              --out FILE           where the dump goes, replacing FILE (default
                                   <pid>-<command>-<HHmmss.SSS>Z.jfr here, UTC, never replaced)
              --state DIR          where cursors are kept (default ~/.jfrq/live)
              --max-age D          keep this much recent data: 10m, 1h, in whole seconds (a
                                   fraction is rounded up); 0 or infinity removes the bound
                                   (start, bound)
              --max-size SIZE      keep this much data: 200MB, 1GB; 0 removes the bound (start, bound)
              --settings NAME|FILE JDK profile name (default, profile) or a .jfc file (start)
              --set EVENT#SETTING=VALUE[,...]  settings on top of those, as stalls advises them:
                                   --set jdk.ExecutionSample#period=1ms (start)
              --name NAME          the recording's name, not one the JVM has (start; default jfrq-live)
              --version, --help

            after --: a jfrq command and its options, run on the dump; the file is inserted for you,
            and the question is checked before anything is dumped:
              jfrq-live 4242 delta -- stalls --thread 'event-loop-*'
              jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr

            every dump prints its actual span next to the window asked for: dumps are whole
            chunks, and a window the JVM has already discarded (max-age, max-size) comes back
            shorter than asked. exit status: 0 ok, 1 the JVM or the dump failed, 2 usage error
            """.formatted(Main.VERSION);

    /**
     * Every time jfrq-live prints is UTC and says so, as jfrq's own header does: a dump is read
     * on other machines and next to other tools, and a clock time without a zone is a guess.
     */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String DEFAULT_NAME = "jfrq-live";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("HHmmss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Set<String> FLAGS = Set.of("help", "version");
    private static final Pattern SIZE = Pattern.compile("\\s*(\\d+)\\s*([kKmMgGtT]?)[bB]?\\s*");
    /** Chunk boundaries are why a file and its window differ by a little; more than this is worth a line. */
    private static final Duration SLACK = Duration.ofSeconds(1);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

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
    private static void line(final PrintStream stream, final String text) {
        stream.print(text + '\n');
    }

    /** {@code 14:03:07.121Z .. 14:05:12.004Z}, with "start" and "now" for the open ends. */
    static String describe(final Window w) {
        return (w.begin() == null ? "the start" : TIME.format(w.begin())) + " .. "
                + (w.end() == null ? "now" : TIME.format(w.end()));
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
            final String[] rest = Arrays.copyOfRange(argv, 2, separator < 0 ? argv.length : separator);
            final String[] question = separator < 0 ? new String[0] : Arrays.copyOfRange(argv, separator + 1, argv.length);
            final Args args = Args.parse(rest, valued(command), FLAGS);
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
                    default -> dump(jvm, command, args, question, file);
                };
            } catch (final IOException | RuntimeException | Error e) {
                try {
                    jvm.close();
                } catch (final IOException | RuntimeException | Error c) {
                    e.addSuppressed(c);
                }
                throw e;
            }
            release(jvm, pid);
            return status;
        } catch (final Args.UsageException e) {
            line(err, "jfrq-live: " + e.getMessage());
            line(err, "Run 'jfrq-live --help' for usage.");
            return 2;
        } catch (final IOException e) {
            line(err, "jfrq-live: " + Main.describe(e));
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
            line(err, "jfrq-live: warning: the connection to JVM " + pid + " did not close cleanly: " + Main.describe(e));
        }
    }

    /**
     * What an unchecked failure from the JMX proxy means. A method whose interface declares no
     * {@code IOException} (most of {@link FlightRecorderMXBean}) reports a lost connection as
     * an {@link UndeclaredThrowableException} around it.
     */
    static String failure(final RuntimeException e) {
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
            default -> throw new Args.UsageException("unknown command '" + command + "'");
        };
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
        line(out, cursorLine(Cursor.load(stateDir(args), jvm.pid(), jvm.startTime())));
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
            line(out, unboundedWarning(jvm));
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
            try {
                fr.closeRecording(id);
            } catch (final IOException | RuntimeException | Error c) {
                e.addSuppressed(c);
            }
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
        final RecordingInfo r = pick(jvm, args);
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
        final RecordingInfo r = pick(jvm, args);
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
        final FlightRecorderMXBean fr = jvm.flightRecorder();
        final RecordingInfo r = pick(jvm, args);
        final Path dir = stateDir(args);
        final Cursor cursor;
        final Window window;
        final Snapshot snapshot;
        final Chunks chunks;
        // The lock spans reading the cursor to advancing it: two deltas in a row, not two at once.
        try (final Closeable _ = Cursor.lock(dir, jvm.pid(), err)) {
            cursor = Cursor.load(dir, jvm.pid(), jvm.startTime());
            window = switch (command) {
                case "full" -> Window.EVERYTHING;
                case "delta" -> {
                    if (cursor.next() == null) {
                        throw new Args.UsageException("no cursor for JVM " + jvm.pid() + " yet: run 'full' first");
                    }
                    yield new Window(cursor.next(), null);
                }
                default -> {
                    if (cursor.lastWindow() == null) {
                        throw new Args.UsageException("no window to repeat for JVM " + jvm.pid()
                                + ": run 'full' or 'delta' first");
                    }
                    yield cursor.lastWindow();
                }
            };
            // A named --out is the caller's to replace; a default name never replaces an earlier dump.
            snapshot = Snapshot.take(fr, jvm.pid(), r.getId(), window, file, args.option("out").isPresent());
            chunks = Chunks.scan(snapshot.file());
            if (!command.equals("again")) {
                // A full dump's window starts where its file does, so an 'again' of it can tell
                // when the JVM has since discarded part of it.
                final Window taken = window.begin() != null ? window
                        : new Window(Instant.ofEpochSecond(0, chunks.startNanos()), null);
                cursor.advance(taken, snapshot.stop());
            }
        }
        // A question that answers in JSON owns standard output: a program reads it whole, so
        // what the dump itself says goes to standard error instead of ahead of the document.
        final PrintStream to = question.length > 0 && Main.answersInJson(question) ? err : out;
        check(snapshot, chunks, window, command, r, jvm, to);
        line(to, cursorLine(cursor));
        if (question.length == 0) {
            return 0;
        }
        line(to, "");
        final List<String> argv = new ArrayList<>(question.length + 1);
        argv.add(question[0]);
        argv.add(file.toString());
        argv.addAll(Arrays.asList(question).subList(1, question.length));
        return Main.run(argv.toArray(String[]::new), out, err);
    }

    /**
     * The span check: what the file holds against what was asked, and why they differ when
     * they do. The chunk headers say it all, so the dump is not parsed.
     */
    private static void check(final Snapshot snapshot, final Chunks chunks, final Window window, final String command,
            final RecordingInfo r, final Jvm jvm, final PrintStream to) throws IOException {
        if (chunks.complete() == 0) {
            throw new IOException("the dump " + snapshot.file() + " holds no complete chunk");
        }
        final Instant start = Instant.ofEpochSecond(0, chunks.startNanos());
        final Instant end = Instant.ofEpochSecond(0, chunks.endNanos());
        line(to, String.format(Locale.ROOT, "Dumped     %s  %s, %d chunk%s, %s .. %s (%s)", snapshot.file(),
                Bytes.format(snapshot.bytes()), chunks.count(), chunks.count() == 1 ? "" : "s", TIME.format(start),
                TIME.format(end), Durations.format(chunks.endNanos() - chunks.startNanos())));
        if (chunks.isTruncated()) {
            line(to, "WARNING    the file is truncated: it ends inside a chunk; " + chunks.complete()
                    + " complete chunk(s) before it");
        }
        if (chunks.isInProgress()) {
            line(to, "WARNING    the file's last chunk is not finished; jfrq will refuse it");
        }
        final String why = switch (command) {
            case "full" -> "everything the recording kept";
            case "delta" -> "since the previous dump";
            default -> "the previous window again";
        };
        line(to, String.format(Locale.ROOT, "Window     %s (%s)", describe(window), why));
        for (final String note : span(window, start, end, r.getStartTime(), bounds(r))) {
            line(to, note);
        }
        if (command.equals("full") && r.getMaxAge() == 0 && r.getMaxSize() == 0) {
            line(to, unboundedWarning(jvm));
        }
    }

    /**
     * How the file's span {@code [start, end]} departs from {@code window} by more than chunk
     * granularity, and why. A file that starts late lost the window's beginning: to the
     * recording's bounds, or, when the recording itself started after the window began (it
     * was stopped and started again since the cursor was set), to never having recorded it.
     */
    static List<String> span(final Window window, final Instant start, final Instant end, final long recordingStartMillis,
            final String bounds) {
        final List<String> lines = new ArrayList<>();
        if (window.begin() != null) {
            final Duration early = Duration.between(start, window.begin());
            if (early.compareTo(SLACK) > 0) {
                lines.add("Note       the file starts " + Durations.format(early)
                        + " before the window: the JVM hands over whole chunks");
            } else if (early.negated().compareTo(SLACK) > 0) {
                final Instant recordingStart = Instant.ofEpochMilli(recordingStartMillis);
                if (recordingStartMillis != 0 && recordingStart.isAfter(window.begin())) {
                    lines.add("WARNING    the file starts " + Durations.format(early.negated())
                            + " after the window: the recording started at " + TIME.format(recordingStart)
                            + ", after the window began, so it never held that data (stopped and started again?)");
                } else {
                    lines.add("WARNING    the file starts " + Durations.format(early.negated())
                            + " after the window: the JVM had already discarded that data (" + bounds + ")");
                }
            }
        }
        if (window.end() != null) {
            final Duration late = Duration.between(window.end(), end);
            if (late.compareTo(SLACK) > 0) {
                lines.add("Note       the file ends " + Durations.format(late)
                        + " after the window: the JVM hands over whole chunks");
            }
        }
        return lines;
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

    /**
     * The recording to act on: {@code --recording} by id, or by a name only one recording
     * has, else the JVM's single running one.
     */
    private static RecordingInfo pick(final Jvm jvm, final Args args) throws IOException {
        final List<RecordingInfo> all = jvm.flightRecorder().getRecordings();
        final Optional<String> wanted = args.option("recording");
        if (wanted.isPresent()) {
            final String w = wanted.get();
            final List<RecordingInfo> named = new ArrayList<>();
            for (final RecordingInfo r : all) {
                if (w.equals(Long.toString(r.getId()))) {
                    return r;
                }
                if (w.equals(r.getName())) {
                    named.add(r);
                }
            }
            if (named.size() == 1) {
                return named.getFirst();
            }
            if (named.size() > 1) {
                final StringBuilder sb = new StringBuilder("--recording '" + w + "' names " + named.size()
                        + " recordings; pick one by id:");
                for (final RecordingInfo r : named) {
                    sb.append(' ').append(r.getId()).append(" (").append(r.getState()).append(')');
                }
                throw new Args.UsageException(sb.toString());
            }
            throw new IOException("JVM " + jvm.pid() + " has no recording '" + w + "'; 'jfrq-live " + jvm.pid()
                    + " status' lists them");
        }
        final List<RecordingInfo> running = new ArrayList<>();
        for (final RecordingInfo r : all) {
            if ("RUNNING".equals(r.getState())) {
                running.add(r);
            }
        }
        if (running.isEmpty()) {
            throw new IOException("JVM " + jvm.pid() + " has no running recording: start one with 'jfrq-live "
                    + jvm.pid() + " start', or run the JVM with -XX:StartFlightRecording");
        }
        if (running.size() > 1) {
            final StringBuilder sb = new StringBuilder("JVM " + jvm.pid() + " has " + running.size()
                    + " running recordings; pick one with --recording ID|NAME:");
            for (final RecordingInfo r : running) {
                sb.append(' ').append(r.getId()).append('=').append(r.getName());
            }
            throw new IOException(sb.toString());
        }
        return running.getFirst();
    }

    private static RecordingInfo recording(final FlightRecorderMXBean fr, final long id) throws IOException {
        for (final RecordingInfo r : fr.getRecordings()) {
            if (r.getId() == id) {
                return r;
            }
        }
        throw new IOException("the JVM lost recording " + id);
    }

    private static Path stateDir(final Args args) {
        return args.option("state").map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".jfrq", "live"));
    }

    private static String jvmLine(final Jvm jvm) {
        // The pid, not RuntimeMXBean.getName(): see Jvm.attach for what asking for the name costs.
        // The host it would add is always this machine, since the command takes a local pid.
        return String.format(Locale.ROOT, "JVM        %s, started %s, %s", jvm.pid(),
                DATE_TIME.format(Instant.ofEpochMilli(jvm.startTime())), jvm.vmVersion());
    }

    private static String recordingLine(final RecordingInfo r) {
        final String since = r.getStartTime() == 0 ? "not started" : "since " + TIME.format(Instant.ofEpochMilli(r.getStartTime()));
        return String.format(Locale.ROOT, "Recording  %-3d %-16s %-8s %s, %s, %s", r.getId(), r.getName(), r.getState(),
                since, bounds(r), r.isToDisk() ? "to disk" : "in memory");
    }

    private static String bounds(final RecordingInfo r) {
        if (r.getMaxAge() == 0 && r.getMaxSize() == 0) {
            return "no bound";
        }
        final StringBuilder sb = new StringBuilder();
        if (r.getMaxAge() != 0) {
            sb.append("max-age ").append(Durations.format(Duration.ofSeconds(r.getMaxAge())));
        }
        if (r.getMaxSize() != 0) {
            sb.append(sb.isEmpty() ? "" : ", ").append("max-size ").append(Bytes.format(r.getMaxSize()));
        }
        return sb.toString();
    }

    private static String cursorLine(final Cursor c) {
        if (c.next() == null) {
            return "Cursor     none: 'full' sets it (" + c.file() + ")";
        }
        return String.format(Locale.ROOT, "Cursor     next delta from %s; last window %s (%s)", TIME.format(c.next()),
                describe(c.lastWindow()), c.file());
    }

    private static String unboundedWarning(final Jvm jvm) {
        return "WARNING    the recording has no bound: the JVM keeps every chunk and each 'full' dump is bigger"
                + " than the last; 'jfrq-live " + jvm.pid() + " bound --max-age 10m' keeps the recent ten minutes";
    }
}
