// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import java.util.Arrays;
import java.util.List;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.Transient;

/**
 * Canonicalises what one pass over a recording reads, and makes reading it cheap.
 *
 * <p>Two facts about the JFR consumer API drive the design. Every field access on a
 * {@code RecordedObject} is a by-name linear scan of its descriptors, and class names
 * are re-derived on each call; a stack of twenty frames costs a hundred such lookups.
 * And the objects a chunk's constant pools resolve to ({@link RecordedStackTrace},
 * {@link RecordedThread}, {@link RecordedClass}, {@link RecordedMethod}) are shared
 * instances: every event that refers to the same stack refers to the same object. So the
 * first sight of a pool object pays the lookups once and is remembered by identity; the
 * value-keyed tables behind it then make equal stacks from different chunks share one
 * instance too. Those tables are probed with the raw components (a frame's type, method,
 * line and native bit; a stack's frame buffer), so a lookup that hits allocates nothing
 * (G-1.5, G-2.2).
 *
 * <p>Identity caches are bounded so that a many-chunk recording cannot pin every
 * chunk's pool objects; when full they are cleared and refilled, which costs at most one
 * slow resolution per object per refill.
 *
 * <p>Not thread-safe by design: one instance per reading thread.
 */
public final class Interner {

    /** Identity caches are cleared past this size; pools of a single chunk are far smaller. */
    public static final int IDENTITY_LIMIT = 200_000;

    private static final String UNKNOWN = "?";
    private static final long SAMPLED_THREAD = Fields.bit(Fields.SAMPLED_THREAD);
    private static final Method NO_METHOD = new Method(UNKNOWN, UNKNOWN);

    private final FrameTable frames = new FrameTable(4096);
    private final StackTable stacks = new StackTable(4096);
    private final ObjObjHashMap<ThreadRef, ThreadRef> threadRefs = new ObjObjHashMap<>(256);
    private final IdentityObjObjHashMap<RecordedStackTrace, Stack> stacksByIdentity = new IdentityObjObjHashMap<>(4096);
    private final IdentityObjObjHashMap<RecordedMethod, Method> methodsByIdentity = new IdentityObjObjHashMap<>(4096);
    private final IdentityObjObjHashMap<RecordedThread, ThreadRef> threadsByIdentity = new IdentityObjObjHashMap<>(256);
    private final IdentityObjObjHashMap<RecordedClass, String> classNamesByIdentity = new IdentityObjObjHashMap<>(1024);
    /** Event types are per chunk; a long recording would otherwise grow this without bound. */
    private final IdentityObjObjHashMap<EventType, TypeFields> fieldsByType = new IdentityObjObjHashMap<>(64);
    /** The type {@link #fields} resolved last and its mask: consecutive lookups on one event skip the probe. */
    private EventType lastType;
    private long lastFields;
    /** Frames of the stack being resolved; grown to the deepest stack seen, reused for life (G-3.3). */
    private Frame[] scratch = new Frame[64];

    /** The {@link Fields} an event type has; a holder because the identity table maps to objects. */
    private record TypeFields(long present) {
    }

    /** A method's declaring type and name, resolved once per pool object. */
    private record Method(String type, String name) {
    }

    public Stack stack(final RecordedStackTrace trace) {
        if (trace == null) {
            return Stack.EMPTY;
        }
        int index = stacksByIdentity.keyIndex(trace);
        if (index < 0) {
            return stacksByIdentity.valueAtQuick(index);
        }
        final List<RecordedFrame> recorded = trace.getFrames();
        final int depth = recorded.size();
        if (scratch.length < depth) {
            scratch = new Frame[Math.max(depth, scratch.length << 1)];
        }
        final Frame[] buffer = scratch;
        for (int i = 0; i < depth; i++) {
            buffer[i] = frame(recorded.get(i));
        }
        final Stack result = stacks.intern(buffer, depth, trace.isTruncated());
        if (stacksByIdentity.size() >= IDENTITY_LIMIT) {
            stacksByIdentity.clear();
            index = stacksByIdentity.keyIndex(trace);
        }
        stacksByIdentity.putAt(index, trace, result);
        return result;
    }

    /** The canonical instance equal to {@code f}. */
    public Frame frame(final Frame f) {
        return frames.intern(f.type(), f.method(), f.line(), f.isNative());
    }

    /** The thread a {@link RecordedThread} denotes, or {@code null}. */
    public ThreadRef thread(final RecordedThread t) {
        if (t == null) {
            return null;
        }
        int index = threadsByIdentity.keyIndex(t);
        if (index < 0) {
            return threadsByIdentity.valueAtQuick(index);
        }
        final ThreadRef ref = ThreadRef.of(t);
        final int canonical = threadRefs.keyIndex(ref);
        final ThreadRef result = canonical < 0 ? threadRefs.valueAtQuick(canonical) : threadRefs.putAt(canonical, ref, ref);
        if (threadsByIdentity.size() >= IDENTITY_LIMIT) {
            threadsByIdentity.clear();
            index = threadsByIdentity.keyIndex(t);
        }
        threadsByIdentity.putAt(index, t, result);
        return result;
    }

    /**
     * The thread an event belongs to: {@code sampledThread} for the sampler events,
     * {@code eventThread} otherwise, decided once per event type instead of per event.
     */
    public ThreadRef thread(@Transient final RecordedEvent e) {
        return Events.thread(e, (fields(e) & SAMPLED_THREAD) != 0 ? Fields.SAMPLED_THREAD : Fields.EVENT_THREAD, this);
    }

    /**
     * Which {@link Fields} the event's type has, as a mask of {@link Fields#bit} values:
     * resolved with {@code hasField} on the first event of each {@code EventType} object,
     * then by identity. The last type is checked first; the reader calls {@link #thread}
     * before dispatching, so every sink's lookups on the same event hit that entry.
     */
    public long fields(@Transient final RecordedEvent e) {
        final EventType type = e.getEventType();
        if (type == lastType) {
            return lastFields;
        }
        int index = fieldsByType.keyIndex(type);
        final TypeFields known;
        if (index < 0) {
            known = fieldsByType.valueAtQuick(index);
        } else {
            long present = 0;
            for (int field = 0; field < Fields.COUNT; field++) {
                if (e.hasField(Fields.nameOf(field))) {
                    present |= Fields.bit(field);
                }
            }
            if (fieldsByType.size() >= IDENTITY_LIMIT) {
                fieldsByType.clear();
                index = fieldsByType.keyIndex(type);
            }
            known = fieldsByType.putAt(index, type, new TypeFields(present));
        }
        lastType = type;
        lastFields = known.present();
        return lastFields;
    }

    /**
     * The JVM name of a class ({@code [B}, {@code java.lang.Object}), without a hidden class's
     * per-JVM address ({@link Frame#stableClass(String)}), or {@code null}.
     */
    public String className(final RecordedClass c) {
        if (c == null) {
            return null;
        }
        int index = classNamesByIdentity.keyIndex(c);
        if (index < 0) {
            return classNamesByIdentity.valueAtQuick(index);
        }
        final String name = Frame.stableClass(name(c));
        if (classNamesByIdentity.size() >= IDENTITY_LIMIT) {
            classNamesByIdentity.clear();
            index = classNamesByIdentity.keyIndex(c);
        }
        classNamesByIdentity.putAt(index, c, name);
        return name;
    }

    /**
     * A class's name as {@link RecordedClass#getName()} gives it, or {@code "null"} where a
     * damaged constant pool left the class without one: the JDK's accessor throws then.
     */
    private static String name(final RecordedClass c) {
        return String.valueOf(c.getString("name")).replace('/', '.');
    }

    public int distinctStacks() {
        return stacks.size();
    }

    public int distinctFrames() {
        return frames.size();
    }

    private Frame frame(final RecordedFrame f) {
        final Method m = method(f.getMethod());
        return frames.intern(m.type(), m.name(), f.getLineNumber(), Frame.isNativeKind(f.getType()));
    }

    private Method method(final RecordedMethod m) {
        if (m == null) {
            return NO_METHOD;
        }
        int index = methodsByIdentity.keyIndex(m);
        if (index < 0) {
            return methodsByIdentity.valueAtQuick(index);
        }
        // The raw name, address and all: a frame keeps it so it can say it is hidden, and prints
        // and keys itself without the address (Frame.stableName, Frame.pretty).
        final RecordedClass type = m.getType();
        final Method method = new Method(type == null ? UNKNOWN : name(type), String.valueOf(m.getName()));
        if (methodsByIdentity.size() >= IDENTITY_LIMIT) {
            methodsByIdentity.clear();
            index = methodsByIdentity.keyIndex(m);
        }
        methodsByIdentity.putAt(index, m, method);
        return method;
    }

    /**
     * Open-addressing table of canonical frames, probed with a frame's components so a
     * hit allocates nothing. Grows by doubling at half load.
     */
    private static final class FrameTable {
        private Frame[] entries;
        private int mask;
        private int free;
        private int size;

        FrameTable(final int capacity) {
            final int n = Integer.highestOneBit(Math.max(16, capacity * 2) - 1) << 1;
            entries = new Frame[n];
            mask = n - 1;
            free = n >>> 1;
        }

        private static int hash(final String type, final String method, final int line, final boolean nativeMethod) {
            int h = type.hashCode();
            h = 31 * h + method.hashCode();
            h = 31 * h + line;
            h = 31 * h + (nativeMethod ? 1 : 0);
            return h ^ (h >>> 16);
        }

        Frame intern(final String type, final String method, final int line, final boolean nativeMethod) {
            int index = hash(type, method, line, nativeMethod) & mask;
            while (true) {
                final Frame f = entries[index];
                if (f == null) {
                    final Frame created = new Frame(type, method, line, nativeMethod);
                    entries[index] = created;
                    size++;
                    if (--free == 0) {
                        rehash();
                    }
                    return created;
                }
                if (f.line() == line && f.isNative() == nativeMethod && f.type().equals(type) && f.method().equals(method)) {
                    return f;
                }
                index = (index + 1) & mask;
            }
        }

        int size() {
            return size;
        }

        private void rehash() {
            final Frame[] old = entries;
            final int n = old.length << 1;
            entries = new Frame[n];
            mask = n - 1;
            free = (n >>> 1) - size;
            for (final Frame f : old) {
                if (f != null) {
                    int index = hash(f.type(), f.method(), f.line(), f.isNative()) & mask;
                    while (entries[index] != null) {
                        index = (index + 1) & mask;
                    }
                    entries[index] = f;
                }
            }
        }
    }

    /**
     * Open-addressing table of canonical stacks, probed with a frame buffer so a hit
     * allocates nothing; the buffer is copied only when a new stack is created.
     */
    private static final class StackTable {
        private Stack[] entries;
        private int mask;
        private int free;
        private int size;

        StackTable(final int capacity) {
            final int n = Integer.highestOneBit(Math.max(16, capacity * 2) - 1) << 1;
            entries = new Stack[n];
            mask = n - 1;
            free = n >>> 1;
        }

        Stack intern(final Frame[] frames, final int depth, final boolean truncated) {
            final int hash = Stack.hashOf(frames, depth, truncated);
            int index = (hash ^ (hash >>> 16)) & mask;
            while (true) {
                final Stack s = entries[index];
                if (s == null) {
                    final Stack created = new Stack(Arrays.copyOf(frames, depth), truncated);
                    entries[index] = created;
                    size++;
                    if (--free == 0) {
                        rehash();
                    }
                    return created;
                }
                if (s.hashCode() == hash && s.sameAs(frames, depth, truncated)) {
                    return s;
                }
                index = (index + 1) & mask;
            }
        }

        int size() {
            return size;
        }

        private void rehash() {
            final Stack[] old = entries;
            final int n = old.length << 1;
            entries = new Stack[n];
            mask = n - 1;
            free = (n >>> 1) - size;
            for (final Stack s : old) {
                if (s != null) {
                    final int h = s.hashCode();
                    int index = (h ^ (h >>> 16)) & mask;
                    while (entries[index] != null) {
                        index = (index + 1) & mask;
                    }
                    entries[index] = s;
                }
            }
        }
    }
}
