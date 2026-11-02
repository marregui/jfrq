// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.util.Set;

import dev.jfrq.core.coll.ObjLongHashMap;

/**
 * The event types the analyses read, as dense {@code int} tags (G-1.10). {@link JfrReader}
 * resolves a type's tag once per {@code EventType} object and hands it to every sink with
 * the event, so the per-event path switches on an {@code int} instead of matching the
 * type name (G-2.2).
 *
 * <p>Invariant other code depends on: the tags are dense, start at 0, are chained
 * {@code + 1}, and {@link #COUNT} is last; {@link #NAMES} is indexed by tag (G-1.9).
 */
public final class EventKinds {

    /** An event type no analysis reads; {@code jfrq info} still counts it. */
    public static final int UNKNOWN = -1;

    public static final int ACTIVE_SETTING = 0;
    public static final int EXECUTION_SAMPLE = ACTIVE_SETTING + 1;
    public static final int NATIVE_METHOD_SAMPLE = EXECUTION_SAMPLE + 1;
    public static final int JAVA_MONITOR_ENTER = NATIVE_METHOD_SAMPLE + 1;
    public static final int THREAD_PARK = JAVA_MONITOR_ENTER + 1;
    public static final int JAVA_MONITOR_WAIT = THREAD_PARK + 1;
    public static final int THREAD_SLEEP = JAVA_MONITOR_WAIT + 1;
    public static final int SOCKET_READ = THREAD_SLEEP + 1;
    public static final int SOCKET_WRITE = SOCKET_READ + 1;
    public static final int FILE_READ = SOCKET_WRITE + 1;
    public static final int FILE_WRITE = FILE_READ + 1;
    public static final int FILE_FORCE = FILE_WRITE + 1;
    public static final int GC_PHASE_PAUSE = FILE_FORCE + 1;
    public static final int SAFEPOINT_BEGIN = GC_PHASE_PAUSE + 1;
    public static final int SAFEPOINT_END = SAFEPOINT_BEGIN + 1;
    public static final int EXECUTE_VM_OPERATION = SAFEPOINT_END + 1;
    public static final int OBJECT_ALLOCATION_SAMPLE = EXECUTE_VM_OPERATION + 1;
    public static final int OBJECT_ALLOCATION_IN_NEW_TLAB = OBJECT_ALLOCATION_SAMPLE + 1;
    public static final int OBJECT_ALLOCATION_OUTSIDE_TLAB = OBJECT_ALLOCATION_IN_NEW_TLAB + 1;
    public static final int THREAD_ALLOCATION_STATISTICS = OBJECT_ALLOCATION_OUTSIDE_TLAB + 1;
    public static final int THREAD_START = THREAD_ALLOCATION_STATISTICS + 1;
    public static final int THREAD_END = THREAD_START + 1;
    public static final int GARBAGE_COLLECTION = THREAD_END + 1;
    public static final int OLD_GARBAGE_COLLECTION = GARBAGE_COLLECTION + 1;
    public static final int GC_HEAP_SUMMARY = OLD_GARBAGE_COLLECTION + 1;
    public static final int GC_CONFIGURATION = GC_HEAP_SUMMARY + 1;
    public static final int GC_HEAP_CONFIGURATION = GC_CONFIGURATION + 1;
    public static final int CPU_LOAD = GC_HEAP_CONFIGURATION + 1;
    public static final int JAVA_THREAD_STATISTICS = CPU_LOAD + 1;
    public static final int RESIDENT_SET_SIZE = JAVA_THREAD_STATISTICS + 1;
    public static final int EXCEPTION_STATISTICS = RESIDENT_SET_SIZE + 1;
    public static final int JAVA_EXCEPTION_THROW = EXCEPTION_STATISTICS + 1;
    public static final int JAVA_ERROR_THROW = JAVA_EXCEPTION_THROW + 1;
    public static final int EVACUATION_FAILED = JAVA_ERROR_THROW + 1;
    public static final int THREAD_CPU_LOAD = EVACUATION_FAILED + 1;
    public static final int NATIVE_MEMORY_USAGE = THREAD_CPU_LOAD + 1;
    public static final int NATIVE_MEMORY_USAGE_TOTAL = NATIVE_MEMORY_USAGE + 1;
    public static final int OS_INFORMATION = NATIVE_MEMORY_USAGE_TOTAL + 1;
    public static final int JVM_INFORMATION = OS_INFORMATION + 1;
    public static final int PROCESS_START = JVM_INFORMATION + 1;
    public static final int COUNT = PROCESS_START + 1;

    private static final String[] NAMES = new String[COUNT];
    private static final ObjLongHashMap<String> BY_NAME = new ObjLongHashMap<>(COUNT, UNKNOWN);

    static {
        NAMES[ACTIVE_SETTING] = "jdk.ActiveSetting";
        NAMES[EXECUTION_SAMPLE] = "jdk.ExecutionSample";
        NAMES[NATIVE_METHOD_SAMPLE] = "jdk.NativeMethodSample";
        NAMES[JAVA_MONITOR_ENTER] = "jdk.JavaMonitorEnter";
        NAMES[THREAD_PARK] = "jdk.ThreadPark";
        NAMES[JAVA_MONITOR_WAIT] = "jdk.JavaMonitorWait";
        NAMES[THREAD_SLEEP] = "jdk.ThreadSleep";
        NAMES[SOCKET_READ] = "jdk.SocketRead";
        NAMES[SOCKET_WRITE] = "jdk.SocketWrite";
        NAMES[FILE_READ] = "jdk.FileRead";
        NAMES[FILE_WRITE] = "jdk.FileWrite";
        NAMES[FILE_FORCE] = "jdk.FileForce";
        NAMES[GC_PHASE_PAUSE] = "jdk.GCPhasePause";
        NAMES[SAFEPOINT_BEGIN] = "jdk.SafepointBegin";
        NAMES[SAFEPOINT_END] = "jdk.SafepointEnd";
        NAMES[EXECUTE_VM_OPERATION] = "jdk.ExecuteVMOperation";
        NAMES[OBJECT_ALLOCATION_SAMPLE] = "jdk.ObjectAllocationSample";
        NAMES[OBJECT_ALLOCATION_IN_NEW_TLAB] = "jdk.ObjectAllocationInNewTLAB";
        NAMES[OBJECT_ALLOCATION_OUTSIDE_TLAB] = "jdk.ObjectAllocationOutsideTLAB";
        NAMES[THREAD_ALLOCATION_STATISTICS] = "jdk.ThreadAllocationStatistics";
        NAMES[THREAD_START] = "jdk.ThreadStart";
        NAMES[THREAD_END] = "jdk.ThreadEnd";
        NAMES[GARBAGE_COLLECTION] = "jdk.GarbageCollection";
        NAMES[OLD_GARBAGE_COLLECTION] = "jdk.OldGarbageCollection";
        NAMES[GC_HEAP_SUMMARY] = "jdk.GCHeapSummary";
        NAMES[GC_CONFIGURATION] = "jdk.GCConfiguration";
        NAMES[GC_HEAP_CONFIGURATION] = "jdk.GCHeapConfiguration";
        NAMES[CPU_LOAD] = "jdk.CPULoad";
        NAMES[JAVA_THREAD_STATISTICS] = "jdk.JavaThreadStatistics";
        NAMES[RESIDENT_SET_SIZE] = "jdk.ResidentSetSize";
        NAMES[EXCEPTION_STATISTICS] = "jdk.ExceptionStatistics";
        NAMES[JAVA_EXCEPTION_THROW] = "jdk.JavaExceptionThrow";
        NAMES[JAVA_ERROR_THROW] = "jdk.JavaErrorThrow";
        NAMES[EVACUATION_FAILED] = "jdk.EvacuationFailed";
        NAMES[THREAD_CPU_LOAD] = "jdk.ThreadCPULoad";
        NAMES[NATIVE_MEMORY_USAGE] = "jdk.NativeMemoryUsage";
        NAMES[NATIVE_MEMORY_USAGE_TOTAL] = "jdk.NativeMemoryUsageTotal";
        NAMES[OS_INFORMATION] = "jdk.OSInformation";
        NAMES[JVM_INFORMATION] = "jdk.JVMInformation";
        NAMES[PROCESS_START] = "jdk.ProcessStart";
        for (int kind = 0; kind < COUNT; kind++) {
            assert NAMES[kind] != null : kind;
            BY_NAME.put(NAMES[kind], kind);
        }
    }

    private EventKinds() {
    }

    /** The tag for a JFR event type name, or {@link #UNKNOWN}. */
    public static int kindOf(final String eventTypeName) {
        return (int) BY_NAME.get(eventTypeName);
    }

    /** The JFR event type name of a tag. */
    public static String nameOf(final int kind) {
        return NAMES[kind];
    }

    /** The names of the given tags, for {@link JfrReader.Sink#eventTypes()}. */
    public static Set<String> names(final int... kinds) {
        final String[] names = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            names[i] = NAMES[kinds[i]];
        }
        return Set.of(names);
    }
}
