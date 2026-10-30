// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.demo;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoAppTest {

    @TempDir
    Path dir;

    @Test
    void cleanScenarioServesRequestsWithoutRecording() throws Exception {
        final DemoApp.Result r = DemoApp.run(Scenario.CLEAN, Duration.ofMillis(1500), null, 4, 100, 1);
        assertTrue(r.requests() > 50, r.latency());
        assertTrue(r.latency().contains("p99"));
        assertNull(r.recording());
        assertTrue(r.describe().startsWith("scenario clean:"));
    }

    @Test
    void allScenarioWritesARecording() throws Exception {
        final Path out = dir.resolve("all.jfr");
        final DemoApp.Result r = DemoApp.run(Scenario.ALL, Duration.ofMillis(2500), out, 4, 100, 2);
        assertTrue(r.requests() > 0, r.latency());
        assertTrue(Files.size(out) > 10_000, "recording is too small");
        assertEquals(out, r.recording());
    }

    @Test
    void scenarioParsing() {
        assertEquals(Scenario.BLOCKING_IO, Scenario.parse("blocking-io"));
        assertEquals(Scenario.LOCK, Scenario.parse("LOCK"));
        assertEquals(Scenario.ALL, Scenario.parse("all"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("nope"));
        assertTrue(Scenario.flags().contains("blocking-io"));
        assertTrue(Scenario.ALL.blockingIo() && Scenario.ALL.lock() && Scenario.ALL.cpu() && Scenario.ALL.alloc());
        assertTrue(Scenario.CPU.cpu() && !Scenario.CPU.lock());
        assertTrue(DemoApp.usage().contains("--scenario"));
    }

    @Test
    void cpuWorkRespectsItsBudget() {
        final long t0 = System.nanoTime();
        CpuWork.burn(50);
        final long elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsed >= 50 && elapsed < 1000, "took " + elapsed + " ms");
    }

    @Test
    void lookupsAreCountedPerRunNotPerJvm() throws Exception {
        // Unpaced, and long enough to reach the one-in-400 lookup on a slow runner: a Windows CI
        // machine managed 192 requests in 1.5 s on four connections.
        final DemoApp.Result first = DemoApp.run(Scenario.BLOCKING_IO, Duration.ofSeconds(5), null, 8, 0, 1);
        assertTrue(first.lookups() > 0, first.describe());
        final DemoApp.Result second = DemoApp.run(Scenario.CLEAN, Duration.ofMillis(300), null, 1, 100, 1);
        assertEquals(0, second.lookups(), second.describe());
    }

    @Test
    void recorderWithABadDestinationLeaksNoRecording() {
        final int before = demoRecordings();
        assertThrows(IOException.class, () -> new Recorder(dir));
        assertEquals(before, demoRecordings());
    }

    @Test
    void recorderClosedBeforeItStartedReleasesTheRecording() throws Exception {
        final int before = demoRecordings();
        final Recorder recorder = new Recorder(dir.resolve("never.jfr"));
        assertEquals(before + 1, demoRecordings());
        recorder.close();
        recorder.close();
        assertEquals(before, demoRecordings());
    }

    @Test
    void recorderStopsOnceAndClosesAfterAStop() throws Exception {
        final Path out = dir.resolve("stopped.jfr");
        final int before = demoRecordings();
        final Recorder recorder = new Recorder(out);
        recorder.start();
        recorder.stop();
        recorder.stop();
        recorder.close();
        assertEquals(before, demoRecordings());
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void serverThatCannotBindShutsItsEventLoopsDown() throws Exception {
        try (final ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Throwable e = assertThrows(Throwable.class,
                    () -> new Server(Scenario.CLEAN, new SessionRegistry(new Persistence()), -1, 1, taken.getLocalPort()));
            assertTrue(e instanceof BindException, e.toString());
        }
        // The bind ran on the acceptor's thread, so that thread exists; it must not outlive the failure.
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (acceptorAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(acceptorAlive(), "an acceptor thread outlived the failed bind");
    }

    @Test
    void summaryTakenWhileConnectionsStillRunIsConsistent() throws Exception {
        try (final Server server = new Server(Scenario.CLEAN, new SessionRegistry(new Persistence()), -1, 1, 0)) {
            final LoadClient load = new LoadClient(server.port(), 4, 0, 2_000_000);
            load.start();
            try {
                // What DemoApp sees when a connection outlives stop()'s join: counts still moving.
                final long deadline = System.nanoTime() + 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    load.summary();
                }
            } finally {
                load.stop();
            }
            assertTrue(load.summary().contains("p99"), load.summary());
        }
    }

    private static int demoRecordings() {
        int n = 0;
        for (final Recording r : FlightRecorder.getFlightRecorder().getRecordings()) {
            if ("jfrq-demo".equals(r.getName())) {
                n++;
            }
        }
        return n;
    }

    private static boolean acceptorAlive() {
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("acceptor-") && t.isAlive()) {
                return true;
            }
        }
        return false;
    }
}
