// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * Starts an in-process JFR recording tuned for the questions jfrq asks. The tuning is
 * what you would pass on the command line for a real service; it is here so the demo
 * is one command:
 *
 * <pre>
 * -XX:StartFlightRecording=filename=app.jfr,settings=profile,\
 *   jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.ThreadSleep#threshold=1ms,\
 *   jdk.SocketRead#threshold=1ms,jdk.SocketWrite#threshold=1ms,jdk.FileRead#threshold=1ms,jdk.FileWrite#threshold=1ms,\
 *   jdk.SocketRead#throttle=off,jdk.SocketWrite#throttle=off,jdk.FileRead#throttle=off,jdk.FileWrite#throttle=off,\
 *   jdk.ExecutionSample#period=10ms,jdk.NativeMethodSample#period=10ms,\
 *   jdk.ObjectAllocationSample#throttle=1000/s
 * </pre>
 *
 * <p>The {@code profile} settings throttle socket and file events to 300 per second across
 * the JVM; a service doing thousands of short reads a second would then have a fair chance
 * of losing the one long read that mattered. The throttle is switched off here.
 */
final class Recorder implements AutoCloseable {

    private final Recording recording;
    private boolean closed;

    /** Configures the recording; closes it again if any step fails, so a bad destination leaks nothing (G-4.2). */
    Recorder(final Path destination) throws IOException, ParseException {
        recording = new Recording(Configuration.getConfiguration("profile"));
        try {
            recording.setName("jfrq-demo");
            final Duration oneMs = Duration.ofMillis(1);
            for (final String blocking : new String[] {"jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                    "jdk.JavaMonitorWait"}) {
                recording.enable(blocking).withThreshold(oneMs).withStackTrace();
            }
            for (final String io : new String[] {"jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite"}) {
                recording.enable(io).withThreshold(oneMs).withStackTrace().with("throttle", "off");
            }
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s").withStackTrace();
            recording.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
            recording.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
            recording.enable("jdk.SafepointEnd").withThreshold(Duration.ZERO);
            recording.setDestination(destination);
            recording.setToDisk(true);
        } catch (final Throwable e) {
            close();
            throw e;
        }
    }

    void start() {
        recording.start();
    }

    /**
     * Stops the recording and writes the file; the demo calls it before reading the result.
     * Does nothing on a recording that is not running: never started, or already stopped.
     */
    void stop() {
        if (recording.getState() == RecordingState.RUNNING) {
            recording.stop();
        }
    }

    /** Stops the recording if it is running, then releases it; idempotent (G-4.1). */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                stop();
            } finally {
                recording.close();
            }
        }
    }
}
