// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

/**
 * The event fields the analyses read, as dense {@code int} tags (G-1.10), so that whether
 * an event type has a field is one bit of a mask resolved once per {@code EventType}
 * object ({@link dev.jfrq.core.model.Interner#fields}) instead of a by-name scan of the
 * type's descriptors on every event. {@link Events} takes these tags.
 *
 * <p>Invariant other code depends on: the tags are dense, start at 0, are chained
 * {@code + 1}, {@link #COUNT} is last and at most 64 (the mask is a {@code long});
 * {@link #NAMES} is indexed by tag (G-1.9).
 */
public final class Fields {

    public static final int EVENT_THREAD = 0;
    public static final int SAMPLED_THREAD = EVENT_THREAD + 1;
    public static final int ACCUMULATED_COUNT = SAMPLED_THREAD + 1;
    public static final int ACTIVE_COUNT = ACCUMULATED_COUNT + 1;
    public static final int ADDRESS = ACTIVE_COUNT + 1;
    public static final int ALLOCATED = ADDRESS + 1;
    public static final int ALLOCATION_SIZE = ALLOCATED + 1;
    public static final int BYTES_READ = ALLOCATION_SIZE + 1;
    public static final int BYTES_WRITTEN = BYTES_READ + 1;
    public static final int CAUSE = BYTES_WRITTEN + 1;
    public static final int COMMAND = CAUSE + 1;
    public static final int COMMITTED = COMMAND + 1;
    public static final int GC_ID = COMMITTED + 1;
    public static final int GC_TIME_RATIO = GC_ID + 1;
    public static final int HEAP_USED = GC_TIME_RATIO + 1;
    public static final int HOST = HEAP_USED + 1;
    public static final int JVM_SYSTEM = HOST + 1;
    public static final int JVM_USER = JVM_SYSTEM + 1;
    public static final int JVM_VERSION = JVM_USER + 1;
    public static final int LONGEST_PAUSE = JVM_VERSION + 1;
    public static final int MACHINE_TOTAL = LONGEST_PAUSE + 1;
    public static final int MAX_SIZE = MACHINE_TOTAL + 1;
    public static final int MESSAGE = MAX_SIZE + 1;
    public static final int MONITOR_CLASS = MESSAGE + 1;
    public static final int NAME = MONITOR_CLASS + 1;
    public static final int OBJECT_CLASS = NAME + 1;
    public static final int OPERATION = OBJECT_CLASS + 1;
    public static final int OS_VERSION = OPERATION + 1;
    public static final int PARENT_THREAD = OS_VERSION + 1;
    public static final int PARKED_CLASS = PARENT_THREAD + 1;
    public static final int PATH = PARKED_CLASS + 1;
    public static final int PAUSE_TARGET = PATH + 1;
    public static final int PEAK_COUNT = PAUSE_TARGET + 1;
    public static final int PORT = PEAK_COUNT + 1;
    public static final int PREVIOUS_OWNER = PORT + 1;
    public static final int SAFEPOINT = PREVIOUS_OWNER + 1;
    public static final int SAFEPOINT_ID = SAFEPOINT + 1;
    public static final int SIZE = SAFEPOINT_ID + 1;
    public static final int STACK_TRACE = SIZE + 1;
    public static final int SUM_OF_PAUSES = STACK_TRACE + 1;
    public static final int SYSTEM = SUM_OF_PAUSES + 1;
    public static final int THREAD = SYSTEM + 1;
    public static final int THROWABLES = THREAD + 1;
    public static final int THROWN_CLASS = THROWABLES + 1;
    public static final int TIME = THROWN_CLASS + 1;
    public static final int TIMED_OUT = TIME + 1;
    public static final int TIMEOUT = TIMED_OUT + 1;
    public static final int TLAB_SIZE = TIMEOUT + 1;
    public static final int TYPE = TLAB_SIZE + 1;
    public static final int UNTIL = TYPE + 1;
    public static final int USER = UNTIL + 1;
    public static final int WEIGHT = USER + 1;
    public static final int WHEN = WEIGHT + 1;
    public static final int COUNT = WHEN + 1;

    private static final String[] NAMES = new String[COUNT];

    static {
        NAMES[EVENT_THREAD] = "eventThread";
        NAMES[SAMPLED_THREAD] = "sampledThread";
        NAMES[ACCUMULATED_COUNT] = "accumulatedCount";
        NAMES[ACTIVE_COUNT] = "activeCount";
        NAMES[ADDRESS] = "address";
        NAMES[ALLOCATED] = "allocated";
        NAMES[ALLOCATION_SIZE] = "allocationSize";
        NAMES[BYTES_READ] = "bytesRead";
        NAMES[BYTES_WRITTEN] = "bytesWritten";
        NAMES[CAUSE] = "cause";
        NAMES[COMMAND] = "command";
        NAMES[COMMITTED] = "committed";
        NAMES[GC_ID] = "gcId";
        NAMES[GC_TIME_RATIO] = "gcTimeRatio";
        NAMES[HEAP_USED] = "heapUsed";
        NAMES[HOST] = "host";
        NAMES[JVM_SYSTEM] = "jvmSystem";
        NAMES[JVM_USER] = "jvmUser";
        NAMES[JVM_VERSION] = "jvmVersion";
        NAMES[LONGEST_PAUSE] = "longestPause";
        NAMES[MACHINE_TOTAL] = "machineTotal";
        NAMES[MAX_SIZE] = "maxSize";
        NAMES[MESSAGE] = "message";
        NAMES[MONITOR_CLASS] = "monitorClass";
        NAMES[NAME] = "name";
        NAMES[OBJECT_CLASS] = "objectClass";
        NAMES[OPERATION] = "operation";
        NAMES[OS_VERSION] = "osVersion";
        NAMES[PARENT_THREAD] = "parentThread";
        NAMES[PARKED_CLASS] = "parkedClass";
        NAMES[PATH] = "path";
        NAMES[PAUSE_TARGET] = "pauseTarget";
        NAMES[PEAK_COUNT] = "peakCount";
        NAMES[PORT] = "port";
        NAMES[PREVIOUS_OWNER] = "previousOwner";
        NAMES[SAFEPOINT] = "safepoint";
        NAMES[SAFEPOINT_ID] = "safepointId";
        NAMES[SIZE] = "size";
        NAMES[STACK_TRACE] = "stackTrace";
        NAMES[SUM_OF_PAUSES] = "sumOfPauses";
        NAMES[SYSTEM] = "system";
        NAMES[THREAD] = "thread";
        NAMES[THROWABLES] = "throwables";
        NAMES[THROWN_CLASS] = "thrownClass";
        NAMES[TIME] = "time";
        NAMES[TIMED_OUT] = "timedOut";
        NAMES[TIMEOUT] = "timeout";
        NAMES[TLAB_SIZE] = "tlabSize";
        NAMES[TYPE] = "type";
        NAMES[UNTIL] = "until";
        NAMES[USER] = "user";
        NAMES[WEIGHT] = "weight";
        NAMES[WHEN] = "when";
        assert COUNT <= Long.SIZE : COUNT;
        for (int field = 0; field < COUNT; field++) {
            assert NAMES[field] != null : field;
        }
    }

    private Fields() {
    }

    /** The JFR field name of a tag. */
    public static String nameOf(final int field) {
        return NAMES[field];
    }

    /** The bit of {@code field} in a presence mask. */
    public static long bit(final int field) {
        return 1L << field;
    }
}
