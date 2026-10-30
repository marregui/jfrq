// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;

import dev.jfrq.core.util.Durations;

/**
 * One dump of a window of a running recording, the way {@code jcmd JFR.dump} does it:
 * clone the recording and stop the clone, which seals the chunk being written so the
 * newest events are in a finished chunk; stream the clone's chunks that overlap the
 * window into a file; close the clone. The recording itself is not touched.
 *
 * <p>Nothing is left behind, even when the dump is cut short: a shutdown hook, in place from
 * before the clone exists until it is closed, closes it and removes the partial file on
 * Ctrl-C or {@code SIGTERM}. A stopped clone left in the JVM would pin its chunks on disk
 * until the JVM exits. The hook waits for the JVM at most {@link Cleanup#HOOK_TIMEOUT}: a
 * JVM that does not answer (stopped with {@code SIGSTOP}, hung) cannot keep this process
 * from exiting, and the clone it keeps is named on standard error.
 *
 * @param file  where the dump was written
 * @param bytes its size
 * @param stop  when the clone was stopped: the exact end of the last chunk in the file,
 *              and the point the next delta continues from
 */
public record Snapshot(Path file, long bytes, Instant stop) {

    /** JMX moves the file in blocks; the JDK's default is 50 KB, which is a lot of round trips for a 40 MB dump. */
    private static final int BLOCK_SIZE = 1 << 20;

    /**
     * @param pid     the target's pid, for the line that names a clone the JVM would not close
     * @param replace whether an existing {@code file} is replaced; when not, the dump fails
     *                rather than overwrite it
     * @throws IOException when the window holds no data, or the file cannot be written; a
     *                     partial temporary file is removed and an existing target is left alone
     */
    public static Snapshot take(final FlightRecorderMXBean fr, final String pid, final long recordingId,
            final Window window, final Path file, final boolean replace) throws IOException {
        final Cleanup cleanup = new Cleanup(fr, pid);
        final Thread hook = new Thread(cleanup, "jfrq-live dump cleanup");
        // Before the clone exists, so there is no moment a clone is in the JVM with nothing to
        // close it; a JVM already shutting down refuses the hook, and nothing has been acquired.
        Runtime.getRuntime().addShutdownHook(hook);
        Snapshot snapshot = null;
        Throwable failure = null;
        try {
            // In flight from here: a stop now waits for the JVM's answer and closes what it made.
            cleanup.cloning(recordingId);
            final long clone;
            try {
                clone = fr.cloneRecording(recordingId, true);
            } catch (final RuntimeException | Error e) {
                cleanup.cloneFailed();
                throw e;
            }
            cleanup.cloned(clone);
            snapshot = write(fr, cleanup, clone, window, file, replace);
        } catch (final IOException | RuntimeException | Error e) {
            failure = e;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (final IllegalStateException e) {
            // The JVM is already shutting down and the hook is running: it cleans up too.
        }
        failure = cleanup.close(failure);
        if (failure instanceof final IOException e) {
            throw e;
        }
        if (failure instanceof final RuntimeException e) {
            throw e;
        }
        if (failure instanceof final Error e) {
            throw e;
        }
        return snapshot;
    }

    private static Snapshot write(final FlightRecorderMXBean fr, final Cleanup cleanup, final long clone,
            final Window window, final Path file, final boolean replace) throws IOException {
        final Instant stop = stopTime(fr, clone);
        final Map<String, String> options = new HashMap<>();
        options.put("blockSize", Integer.toString(BLOCK_SIZE));
        if (window.begin() != null) {
            options.put("startTime", window.begin().toString());
        }
        if (window.end() != null) {
            options.put("endTime", window.end().toString());
        }
        final long stream;
        try {
            stream = fr.openStream(clone, options);
        } catch (final IOException e) {
            // "No recording data available": no chunk overlaps the window.
            throw new IOException("the recording holds no data in the window " + Live.describe(window)
                    + " (" + e.getMessage() + ")", e);
        }
        cleanup.stream(stream);
        final Path temporary = cleanup.temporary(file);
        long bytes = 0;
        // Opened without CREATE: if the shutdown hook removed the file in between, the dump
        // fails here instead of bringing it back for nobody to remove.
        try (final OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] block;
            while ((block = fr.readStream(stream)) != null) {
                out.write(block);
                bytes += block.length;
            }
        }
        move(temporary, file, replace);
        return new Snapshot(file, bytes, stop);
    }

    /** Publishes a completed dump in one step where the filesystem supports it. */
    private static void move(final Path temporary, final Path target, final boolean replace) throws IOException {
        if (!replace) {
            // Not atomic with the check, but the name is this process's own millisecond stamp.
            if (Files.exists(target)) {
                throw new IOException("the dump was not written: " + target + " already exists");
            }
            Files.move(temporary, target);
            return;
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Instant stopTime(final FlightRecorderMXBean fr, final long clone) throws IOException {
        for (final RecordingInfo r : fr.getRecordings()) {
            if (r.getId() == clone) {
                return Instant.ofEpochMilli(r.getStopTime());
            }
        }
        throw new IOException("the JVM lost the recording clone " + clone + " before it could be read");
    }

    /**
     * What a dump leaves behind until it is done: the clone, its stream, the partial file.
     * Closed by whichever comes first: the dump finishing, or the shutdown hook when the
     * process is told to stop mid-dump; each resource is detached under the lock and freed
     * outside it (G-4.4), so the hook never waits on a thread that is itself waiting for the
     * JVM. Every step is attempted; the first failure is the one reported and the rest are
     * suppressed into it (G-4.3). A resource acquired after the cleanup was closed is still
     * registered, so the next {@link #close} frees it, and the acquisition fails.
     */
    static final class Cleanup implements Runnable {

        /** How long the shutdown hook waits for the JVM to close the clone before it gives up. */
        static final Duration HOOK_TIMEOUT = Duration.ofSeconds(5);

        private final FlightRecorderMXBean fr;
        private final String pid;
        private long clone = -1;
        private long cloneId = -1;
        private boolean closed;
        /** A clone exists whose close has not returned yet: what the shutdown hook waits for. */
        private boolean pending;
        /** The clone request has been sent and not answered: the JVM may be making one right now. */
        private boolean cloning;
        private long source = -1;
        private long stream = -1;
        private Path temporary;

        Cleanup(final FlightRecorderMXBean fr, final String pid) {
            this.fr = fr;
            this.pid = pid;
        }

        synchronized void cloning(final long recordingId) {
            source = recordingId;
            cloning = true;
        }

        synchronized void cloneFailed() {
            cloning = false;
            notifyAll();
        }

        /**
         * Registers the clone even when the dump was interrupted while the JVM made it, so
         * the {@link #close} that follows frees it; the acquisition then fails.
         */
        synchronized void cloned(final long id) throws IOException {
            clone = id;
            cloneId = id;
            pending = true;
            cloning = false;
            notifyAll();
            interrupted();
        }

        synchronized void stream(final long id) throws IOException {
            stream = id;
            interrupted();
        }

        /** A sibling temporary file makes an interrupted dump unable to damage {@code target}. */
        synchronized Path temporary(final Path target) throws IOException {
            interrupted();
            final Path absolute = target.toAbsolutePath();
            final Path parent = absolute.getParent();
            final Path name = absolute.getFileName();
            if (parent == null || name == null) {
                throw new IOException("cannot create a temporary dump next to " + target);
            }
            temporary = Files.createTempFile(parent, "." + name + ".", ".part");
            return temporary;
        }

        private void interrupted() throws IOException {
            if (closed) {
                throw new IOException("the dump was interrupted");
            }
        }

        /** The shutdown hook: the process is exiting, so there is nobody to report a failure to. */
        @Override
        public void run() {
            abandon(System.err, HOOK_TIMEOUT);
        }

        /**
         * Closes everything without letting the JVM hold the process: the partial file is
         * removed on this thread, the JMX calls run on a daemon thread, and after
         * {@code timeout} without the clone closed the clone is named on {@code err} so the
         * user can close it.
         */
        void abandon(final PrintStream err, final Duration timeout) {
            final Path part;
            synchronized (this) {
                closed = true;
                part = temporary;
                temporary = null;
            }
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (final IOException e) {
                    // The process is exiting; the file is a hidden .part beside the target.
                }
            }
            Thread.ofPlatform().daemon().name("jfrq-live clone close").start(() -> close(null));
            final long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (this) {
                long left = deadline - System.nanoTime();
                while ((pending || cloning) && left > 0) {
                    try {
                        wait(TimeUnit.NANOSECONDS.toMillis(left) + 1);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    left = deadline - System.nanoTime();
                }
                if (cloning) {
                    err.print("jfrq-live: JVM " + pid + " did not answer the clone request in " + Durations.format(timeout)
                            + "; a clone of recording " + source + " may be left in it: 'jfrq-live " + pid
                            + " status' lists it and 'jfrq-live " + pid + " stop --recording ID' closes it\n");
                    err.flush();
                } else if (pending) {
                    err.print("jfrq-live: JVM " + pid + " did not answer in " + Durations.format(timeout)
                            + "; the dump's recording clone " + cloneId + " is still in it: 'jfrq-live " + pid
                            + " stop --recording " + cloneId + "' closes it\n");
                    err.flush();
                }
            }
        }

        Throwable close(final Throwable primary) {
            final long s;
            final long c;
            final Path t;
            synchronized (this) {
                closed = true;
                s = stream;
                stream = -1;
                c = clone;
                clone = -1;
                t = temporary;
                temporary = null;
            }
            Throwable failure = primary;
            if (t != null) {
                try {
                    // After a successful dump it has been moved already, and this does nothing.
                    Files.deleteIfExists(t);
                } catch (final IOException | RuntimeException | Error e) {
                    failure = chain(failure, e);
                }
            }
            if (s >= 0) {
                try {
                    fr.closeStream(s);
                } catch (final IOException | RuntimeException | Error e) {
                    failure = chain(failure, e);
                }
            }
            if (c >= 0) {
                try {
                    fr.closeRecording(c);
                } catch (final IOException | RuntimeException | Error e) {
                    failure = chain(failure, e);
                } finally {
                    synchronized (this) {
                        pending = false;
                        notifyAll();
                    }
                }
            }
            return failure;
        }

        private static Throwable chain(final Throwable primary, final Throwable next) {
            if (primary == null) {
                return next;
            }
            primary.addSuppressed(next);
            return primary;
        }
    }
}
