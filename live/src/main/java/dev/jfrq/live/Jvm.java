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
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import jdk.management.jfr.FlightRecorderMXBean;

import dev.jfrq.core.util.Durations;

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

    private static final String DIAGNOSTIC_COMMAND = "com.sun.management:type=DiagnosticCommand";
    private static final String FLIGHT_RECORDER = "jdk.management.jfr:type=FlightRecorder";
    /** What one sample reads of the OS MBean; an attribute the platform lacks is left out of the answer. */
    private static final String[] OS_ATTRIBUTES = {"ProcessCpuTime", "CpuLoad", "OpenFileDescriptorCount",
        "CommittedVirtualMemorySize"};
    private static final String RMI_HOSTNAME = "java.rmi.server.hostname";
    /** How long an attach runs before {@link #attach(String, PrintStream)} says it is attaching. */
    static final long ATTACH_NOTICE_MILLIS = 1_000;

    private final JMXConnector connector;
    private final FlightRecorderMXBean flightRecorder;
    private final MBeanServerConnection mbsc;
    private final String pid;
    private final RuntimeMXBean runtime;

    private Jvm(final String pid, final JMXConnector connector, final MBeanServerConnection mbsc,
            final FlightRecorderMXBean flightRecorder, final RuntimeMXBean runtime) {
        this.pid = pid;
        this.connector = connector;
        this.mbsc = mbsc;
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
            return new Jvm(pid, connector, mbsc, fr, runtime);
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

    /**
     * What the JVM says about itself now, over the same connection: four round trips (the
     * OS, memory and thread MBeans, and the clock is this side's). A value the platform does
     * not report (open file descriptors on Windows, a CPU load not yet known) is -1. The
     * resident set size is in no MBean.
     *
     * @param timeout how long the JVM has to answer each call
     * @throws IOException the connection failed, or the JVM did not answer within {@code timeout}
     */
    Sample sample(final Duration timeout) throws IOException {
        return within(() -> {
            try {
                final AttributeList os = mbsc.getAttributes(
                        new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME), OS_ATTRIBUTES);
                final CompositeData heap = (CompositeData) mbsc.getAttribute(
                        new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME), "HeapMemoryUsage");
                final Object threads = mbsc.getAttribute(new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME),
                        "ThreadCount");
                final Number cpuLoad = valueOrNull(os, "CpuLoad");
                return new Sample(System.nanoTime(), count(os, "ProcessCpuTime"),
                        cpuLoad == null ? -1 : cpuLoad.doubleValue(), ((Number) heap.get("used")).longValue(),
                        ((Number) heap.get("committed")).longValue(), ((Number) threads).longValue(),
                        count(os, "OpenFileDescriptorCount"), count(os, "CommittedVirtualMemorySize"));
            } catch (final JMException e) {
                throw new IOException("JVM " + pid + " refused a sample: " + e.getMessage(), e);
            }
        }, timeout, "a sample");
    }

    /**
     * One reading of the JVM's own MBeans.
     *
     * @param atNanos           this side's {@link System#nanoTime()} when the answer came
     * @param processCpuNanos   CPU time the process has used since it started, -1 when unknown
     * @param systemCpuLoad     the whole machine's recent CPU use, 0 to 1; negative when unknown
     * @param openFds           -1 where the platform has no count
     */
    record Sample(long atNanos, long processCpuNanos, double systemCpuLoad, long heapUsedBytes,
            long heapCommittedBytes, long threads, long openFds, long committedVirtualBytes) {
    }

    private static long count(final AttributeList list, final String name) {
        final Number n = valueOrNull(list, name);
        return n == null ? -1 : n.longValue();
    }

    /** The attribute's value, or {@code null} when the MBean did not return it. */
    private static Number valueOrNull(final AttributeList list, final String name) {
        for (final Attribute a : list.asList()) {
            if (a.getName().equals(name) && a.getValue() instanceof final Number n) {
                return n;
            }
        }
        return null;
    }

    /**
     * The JVM's class histogram, as {@code jcmd <pid> GC.class_histogram} prints it, through
     * the {@code DiagnosticCommand} MBean. The JVM runs a full GC first, so what it counts is
     * reachable: that pause shows in the recording as a GC with the cause "Heap Inspection
     * Initiated GC".
     *
     * @throws IOException the connection failed, the JVM refused, or it did not answer within {@code timeout}
     */
    String classHistogram(final Duration timeout) throws IOException {
        return within(() -> {
            try {
                return (String) mbsc.invoke(new ObjectName(DIAGNOSTIC_COMMAND), "gcClassHistogram",
                        new Object[] {new String[0]}, new String[] {String[].class.getName()});
            } catch (final JMException e) {
                throw new IOException("JVM " + pid + " refused GC.class_histogram: " + e.getMessage(), e);
            }
        }, timeout, "GC.class_histogram");
    }

    /**
     * Runs a remote call with a deadline (G-15.1): the call runs on a virtual thread, and a
     * JVM that has not answered after {@code timeout} is reported as such while the call is
     * left to the connection's own fate. The connection is not usable for this caller after that.
     *
     * @throws IOException what the call threw, or that the JVM did not answer {@code what} in time
     */
    static <T> T within(final Slow<T> call, final Duration timeout, final String what) throws IOException {
        final FutureTask<T> task = new FutureTask<>(call::run);
        final Thread thread = Thread.ofVirtual().name("jfrq-live " + what).start(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException e) {
            thread.interrupt();
            throw new IOException("the JVM did not answer " + what + " in " + Durations.format(timeout),
                    e);
        } catch (final InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + what, e);
        } catch (final ExecutionException e) {
            return rethrow(e.getCause());
        }
    }

    private static <T> T rethrow(final Throwable cause) throws IOException {
        if (cause instanceof final IOException io) {
            throw io;
        }
        if (cause instanceof final RuntimeException r) {
            throw r;
        }
        if (cause instanceof final Error error) {
            throw error;
        }
        throw new IOException(cause);
    }

    @Override
    public void close() throws IOException {
        connector.close();
    }

    /**
     * {@link #close()} with a deadline: closing an RMI connector is a remote call, which waits
     * for as long as a frozen JVM does not answer. Past {@code timeout} the close is left to a
     * virtual thread, which does not hold this process's exit.
     *
     * @throws IOException what the close threw, or that the JVM did not answer it in time
     */
    void close(final Duration timeout) throws IOException {
        within(() -> {
            connector.close();
            return null;
        }, timeout, "the connection's close");
    }
}
