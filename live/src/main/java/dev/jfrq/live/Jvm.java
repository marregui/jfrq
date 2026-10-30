// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import jdk.management.jfr.FlightRecorderMXBean;

/**
 * A connection to a running JVM's flight recorder. The attach API starts the JVM's local
 * management agent (the same thing {@code jcmd} and Mission Control do), and the recorder
 * is then driven through {@link FlightRecorderMXBean} over that local JMX connector.
 *
 * <p>Attaching needs the same user as the target, a target that was not started with
 * {@code -XX:+DisableAttachMechanism}, and a target runtime that carries
 * {@code jdk.management.agent} (every full JDK; a jlinked image may not).
 */
public final class Jvm implements Closeable {

    private static final String FLIGHT_RECORDER = "jdk.management.jfr:type=FlightRecorder";
    private static final String RMI_HOSTNAME = "java.rmi.server.hostname";
    /** How long an attach runs before {@link #attach(String, PrintStream)} says it is attaching. */
    static final long ATTACH_NOTICE_MILLIS = 1_000;

    private final JMXConnector connector;
    private final FlightRecorderMXBean flightRecorder;
    private final String pid;
    private final RuntimeMXBean runtime;

    private Jvm(final String pid, final JMXConnector connector, final FlightRecorderMXBean flightRecorder, final RuntimeMXBean runtime) {
        this.pid = pid;
        this.connector = connector;
        this.flightRecorder = flightRecorder;
        this.runtime = runtime;
    }

    /**
     * {@link #attach(String)}, saying so on {@code err} when it takes longer than
     * {@link #ATTACH_NOTICE_MILLIS}: the first attach starts the target's management agent,
     * which took 5.4 s on one machine, and a command that is silent that long looks hung.
     */
    public static Jvm attach(final String pid, final PrintStream err) throws IOException {
        return noticeIfSlow(() -> attach(pid), ATTACH_NOTICE_MILLIS, err, "jfrq-live: attaching to JVM " + pid
                + "; the first attach starts its management agent, which can take a few seconds\n");
    }

    /** Something that can fail with an {@link IOException}, run by {@link #noticeIfSlow}. */
    @FunctionalInterface
    interface Slow<T> {
        T run() throws IOException;
    }

    /**
     * Runs {@code action}, and prints {@code notice} on {@code err} once if it has not returned
     * after {@code afterMillis}; never after it has returned, so the notice cannot land among
     * what the caller prints next.
     */
    static <T> T noticeIfSlow(final Slow<T> action, final long afterMillis, final PrintStream err, final String notice)
            throws IOException {
        // One flag, read and set under one lock: the notice is printed before the action
        // settles or not at all.
        final boolean[] settled = new boolean[1];
        final Thread watch = Thread.ofVirtual().name("jfrq-live-attach-notice").start(() -> {
            try {
                Thread.sleep(afterMillis);
            } catch (final InterruptedException e) {
                return;
            }
            synchronized (settled) {
                if (!settled[0]) {
                    err.print(notice);
                    err.flush();
                }
            }
        });
        try {
            return action.run();
        } finally {
            synchronized (settled) {
                settled[0] = true;
            }
            watch.interrupt();
        }
    }

    /** @throws IOException when the process does not exist, is not a JVM, or refuses attachment */
    public static Jvm attach(final String pid) throws IOException {
        // RMI names this side's endpoint by resolving the machine's own hostname, which on a Mac
        // whose name does not resolve costs five seconds per connection. The local connector
        // only ever talks over loopback, so the name is irrelevant; say so before RMI asks.
        //
        // The target resolves too, and that side cannot be set from here: RuntimeMXBean.getName()
        // is VMManagementImpl.getVmId(), which calls InetAddress.getLocalHost() on every single
        // call, uncached. Against a Mac whose name does not resolve that is a five-second answer,
        // measured, with the target's own thread dump in the lookup. Nothing here calls getName().
        if (System.getProperty(RMI_HOSTNAME) == null) {
            System.setProperty(RMI_HOSTNAME, "127.0.0.1");
        }
        // On macOS the JDK's attach to a pid with no process waits about five seconds for a
        // handshake and then says the process "is not ready"; ask the OS first.
        if (ProcessHandle.of(Long.parseLong(pid)).isEmpty()) {
            throw new IOException("cannot attach to " + pid + ": no such process");
        }
        final VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(pid);
        } catch (final AttachNotSupportedException | IOException e) {
            throw new IOException("cannot attach to " + pid + ": " + e.getMessage(), e);
        }
        final String address;
        try {
            // Idempotent: a JVM whose agent is already running answers with the same address.
            address = vm.startLocalManagementAgent();
        } catch (final IOException | RuntimeException | Error e) {
            try {
                vm.detach();
            } catch (final IOException | RuntimeException | Error d) {
                e.addSuppressed(d);
            }
            throw e;
        }
        vm.detach();
        final JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
        try {
            final MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            final FlightRecorderMXBean fr = ManagementFactory.newPlatformMXBeanProxy(mbsc, FLIGHT_RECORDER,
                    FlightRecorderMXBean.class);
            final RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(mbsc,
                    ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            return new Jvm(pid, connector, fr, runtime);
        } catch (final IOException | RuntimeException | Error e) {
            try {
                connector.close();
            } catch (final IOException | RuntimeException | Error c) {
                e.addSuppressed(c);
            }
            throw e;
        }
    }

    public FlightRecorderMXBean flightRecorder() {
        return flightRecorder;
    }

    public String pid() {
        return pid;
    }

    /** Epoch milliseconds: with the pid, this identifies one JVM incarnation (pids are reused). */
    public long startTime() {
        return runtime.getStartTime();
    }

    public String vmVersion() {
        return runtime.getVmVersion();
    }

    @Override
    public void close() throws IOException {
        connector.close();
    }
}
