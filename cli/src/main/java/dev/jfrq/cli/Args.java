// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Glob;

/**
 * Command-line arguments: positionals plus {@code --name value} / {@code --name=value}
 * options and {@code --flag} switches. Small enough that a library is not worth the
 * dependency. Unknown options are an error, so typos never pass silently, and so are an
 * option given twice (which of the two was meant is a guess), an empty value, and a
 * single-dash token such as {@code -x}; {@code -h} is {@code --help} where that flag
 * exists. Public because {@code jfrq-live} parses its own command line with it.
 */
public final class Args {

    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new LinkedHashMap<>();
    private final Set<String> flags;

    /** Parses {@code argv}; {@code valued} names options that take a value, {@code flags} those that do not. */
    public static Args parse(final String[] argv, final Set<String> valued, final Set<String> flags) {
        final Args a = new Args(flags);
        for (int i = 0; i < argv.length; i++) {
            final String arg = argv[i];
            if (arg.equals("-h") && flags.contains("help")) {
                a.put("help", "true");
                continue;
            }
            if (!arg.startsWith("--")) {
                if (arg.length() > 1 && arg.charAt(0) == '-') {
                    // A lone '-' could be a file name; '-x' is a mistyped option, not a recording.
                    throw new UsageException("unknown option " + arg);
                }
                a.positional.add(arg);
                continue;
            }
            String name = arg.substring(2);
            String value = null;
            final int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (flags.contains(name)) {
                if (value != null) {
                    throw new UsageException("--" + name + " takes no value");
                }
                a.put(name, "true");
            } else if (valued.contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.length) {
                        throw new UsageException("--" + name + " needs a value");
                    }
                    value = argv[++i];
                }
                if (value.isEmpty()) {
                    throw new UsageException("--" + name + " needs a value");
                }
                a.put(name, value);
            } else {
                throw new UsageException("unknown option --" + name);
            }
        }
        return a;
    }

    private Args(final Set<String> flags) {
        this.flags = flags;
    }

    private void put(final String name, final String value) {
        if (options.putIfAbsent(name, value) != null) {
            throw new UsageException("--" + name + " given twice");
        }
    }

    public List<String> positional() {
        return positional;
    }

    /** The first positional argument, or a usage error naming what is missing. */
    public String first(final String what) {
        if (positional.isEmpty()) {
            throw new UsageException("missing " + what);
        }
        return positional.getFirst();
    }

    public Optional<String> option(final String name) {
        return Optional.ofNullable(options.get(name));
    }

    public boolean flag(final String name) {
        return flags.contains(name) && options.containsKey(name);
    }

    /** {@code --top N}: rows per table, a positive number, 15 by default. */
    public int top() {
        return positive("top", 15);
    }

    /** {@code --<name> N}: a positive number, {@code fallback} when the option is not given. */
    public int positive(final String name, final int fallback) {
        final String v = options.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            final int n = Integer.parseInt(v);
            if (n <= 0) {
                throw new UsageException("--" + name + " must be positive");
            }
            return n;
        } catch (final NumberFormatException e) {
            throw new UsageException("--" + name + " is not a number: " + v);
        }
    }

    /**
     * {@code --<name> GLOB}: the comma-separated patterns, empty when the option is not given; a
     * value that names no pattern ({@code ","}) is a usage error rather than a filter that matches nothing.
     */
    public Optional<Glob> globs(final String name) {
        final String v = options.get(name);
        if (v == null) {
            return Optional.empty();
        }
        final Glob glob = Glob.of(v);
        if (glob.isEmpty()) {
            throw new UsageException("--" + name + " must name at least one pattern");
        }
        return Optional.of(glob);
    }

    public long durationOption(final String name, final String fallback, final boolean zeroAllowed) {
        final String v = options.getOrDefault(name, fallback);
        if (!v.equals(v.strip())) {
            // The parser would forgive the space and read the rest; a quoted "50 " is a slip, not a value.
            throw new UsageException("--" + name + " has spaces around it: '" + v + "'");
        }
        if (Character.isDigit(v.charAt(v.length() - 1)) && !v.equals("0")) {
            // A bare number would be nanoseconds, which nobody means on a command line.
            throw new UsageException("--" + name + " needs a unit: " + v + "ms, " + v + "s ...");
        }
        try {
            final long nanos = Durations.parseNanos(v);
            if (nanos < 0 || (nanos == 0 && !zeroAllowed)) {
                throw new UsageException("--" + name + " must be " + (zeroAllowed ? "zero or more" : "positive"));
            }
            return nanos;
        } catch (final IllegalArgumentException e) {
            throw new UsageException("--" + name + ": " + e.getMessage());
        }
    }

    /** A usage error: the message is printed with the command's help and exit code 2. */
    public static final class UsageException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public UsageException(final String message) {
            super(message);
        }
    }
}
