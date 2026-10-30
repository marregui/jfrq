// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jdk.jfr.Recording;

/**
 * Makes real recordings in-process for the tests that exercise the JFR path. Each fixture
 * produces one pathology on a thread with a known name so assertions can be precise.
 */
public final class JfrFixtures {

    /** How long a lock holder outlives the exchange, so it is still alive when the recording stops. */
    static final long HOLDER_TAIL_MILLIS = 800;

    /**
     * The longest any fixture waits on a latch or a thread. Generous, so a loaded machine
     * never trips it; finite, so a fixture that goes wrong fails the test instead of hanging
     * the build.
     */
    static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

    private JfrFixtures() {
    }

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /** Records {@code body} with the given settings into a fresh file under {@code dir}. */
    public static Path record(final Path dir, final String name, final Consumer<Recording> settings, final Body body) throws Exception {
        final Path file = Files.createTempFile(dir, name, ".jfr");
        try (final Recording r = new Recording()) {
            // Without this event the file does not say which thresholds and periods were active.
            r.enable("jdk.ActiveSetting");
            settings.accept(r);
            r.setDestination(file);
            r.start();
            body.run();
            r.stop();
        }
        return file;
    }

    /** Runs {@code body} on a thread with the given name and waits for it. */
    public static void onThread(final String name, final Body body) throws Exception {
        final Throwable[] failure = new Throwable[1];
        final Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (final Throwable e) {
                failure[0] = e;
            }
        }, name);
        t.start();
        join(t);
        if (failure[0] != null) {
            throw new AssertionError("thread " + name + " failed", failure[0]);
        }
    }

    /**
     * Makes {@code waiter} block on {@code lock} for about {@code holdMillis} while
     * {@code holder} holds it; returns once the waiter has been through the lock, which is
     * after the holder released it. The holder is still running then, on purpose: see below.
     */
    public static void contend(final Object lock, final String holder, final String waiter, final long holdMillis) throws Exception {
        final CountDownLatch held = new CountDownLatch(1);
        final Thread h = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                sleep(holdMillis);
            }
            sleep(HOLDER_TAIL_MILLIS);
        }, holder);
        h.start();
        // The waiter only gets through the lock once the holder has released it, so joining it
        // alone orders the whole exchange. The holder is deliberately not joined: JFR writes the
        // thread constant pool when the recording stops, and a thread that has already exited is
        // written as an unknown previous owner, which is the holder this fixture exists to name.
        // Its tail sleep keeps it alive across the stop, and it ends on its own.
        onThread(waiter, () -> {
            await(held);
            sleep(20); // let the holder settle inside the critical section
            synchronized (lock) {
                lock.notifyAll();
            }
        });
    }

    /** Burns CPU on the calling thread for about {@code millis}; sampled as Java execution. */
    public static void burn(final long millis) {
        final long deadline = System.nanoTime() + millis * 1_000_000L;
        long acc = 1;
        do {
            for (int i = 0; i < 5_000; i++) {
                acc = acc * 6364136223846793005L + 1442695040888963407L + i;
            }
        } while (System.nanoTime() < deadline);
        if (acc == 42) {
            throw new IllegalStateException("unlikely, but the loop must not be optimised away");
        }
    }

    public static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Waits for {@code latch}, at most {@link #WAIT_LIMIT}; running out of time is a test failure. */
    static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("latch still at " + latch.getCount() + " after " + WAIT_LIMIT);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits for {@code t} to end, at most {@link #WAIT_LIMIT}; running out of time is a test
     * failure, and the thread is interrupted so it does not outlive the test by much.
     */
    static void join(final Thread t) {
        try {
            if (!t.join(WAIT_LIMIT)) {
                t.interrupt();
                throw new AssertionError("thread " + t.getName() + " still running after " + WAIT_LIMIT);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
