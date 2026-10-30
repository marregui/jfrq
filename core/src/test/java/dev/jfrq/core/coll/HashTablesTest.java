// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open-addressing tables against {@code java.util}: the same random sequence of
 * inserts, overwrites, deletes and lookups must leave both in the same state, through
 * several rehashes. The seed is printed, named in every failure, and taken back through
 * {@code -Djfrq.test.seed} ({@code ./gradlew :core:test -Djfrq.test.seed=<seed>} forwards
 * it) so a failing run can be replayed (G-11.4).
 */
class HashTablesTest {

    static final long SEED = Long.getLong("jfrq.test.seed", System.nanoTime());
    static final int OPERATIONS = 200_000;
    /** Few distinct keys, so deletes and re-inserts hit occupied neighbourhoods. */
    static final int KEY_SPACE = 4_000;

    static final String REPLAY = "seed " + SEED + ", replay with -Djfrq.test.seed=" + SEED;

    static {
        System.out.println("HashTablesTest " + REPLAY);
    }

    /** Gradle shows a failure's message but not the test's output, so the seed goes into the message. */
    @RegisterExtension
    static final TestExecutionExceptionHandler SEED_ON_FAILURE = (context, failure) -> {
        throw new AssertionError(failure.getMessage() + " [" + REPLAY + "]", failure);
    };

    /** A key whose hash collides in bands, so linear probing runs long and deletes have to shift. */
    record Key(int id) {
        @Override
        public int hashCode() {
            return id >>> 3;
        }
    }

    @Test
    void objObjHashMapMatchesHashMap() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final ObjObjHashMap<Key, String> map = new ObjObjHashMap<>(4);
        final Map<Key, String> reference = new HashMap<>();
        for (int op = 0; op < OPERATIONS; op++) {
            final Key k = new Key(rnd.nextInt(KEY_SPACE));
            switch (rnd.nextInt(4)) {
                case 0, 1 -> {
                    final String v = "v" + op;
                    assertEquals(reference.put(k, v), map.put(k, v));
                }
                case 2 -> assertEquals(reference.remove(k) != null, map.remove(k));
                default -> {
                    assertEquals(reference.get(k), map.get(k));
                    assertEquals(reference.containsKey(k), map.contains(k));
                    assertEquals(!reference.containsKey(k), map.excludes(k));
                }
            }
            assertEquals(reference.size(), map.size());
        }
        assertEquals(reference, slotsOf(map));
        assertEquals(reference.isEmpty(), map.isEmpty());
        assertEquals(!reference.isEmpty(), map.notEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(new Key(1)));
    }

    @Test
    void objObjHashMapGetOrInsertIdiom() {
        final ObjObjHashMap<String, int[]> map = new ObjObjHashMap<>();
        final int i = map.keyIndex("a");
        assertTrue(i >= 0);
        final int[] counter = map.putAt(i, "a", new int[1]);
        counter[0]++;
        final int again = map.keyIndex("a");
        assertTrue(again < 0);
        assertEquals(1, map.valueAtQuick(again)[0]);
        assertEquals(1, map.valueAt(again)[0]);
        assertThrows(IllegalArgumentException.class, () -> map.valueAt(map.keyIndex("b")));
        final int slot = -again - 1;
        assertTrue(map.hasKeyAtSlot(slot));
        assertEquals("a", map.keyAtSlot(slot));
        assertEquals(counter, map.valueAtSlot(slot));
        // The result of putAt survives a rehash triggered by that very insert.
        final ObjObjHashMap<Integer, Integer> small = new ObjObjHashMap<>(1);
        for (int k = 0; k < 100; k++) {
            final int index = small.keyIndex(k);
            assertEquals(k, small.putAt(index, k, k));
            assertEquals(k, small.get(k));
        }
        assertEquals(100, small.size());
    }

    @Test
    void identityMapUsesReferencesNotEquals() {
        final IdentityObjObjHashMap<String, Integer> map = new IdentityObjObjHashMap<>(2);
        final String a = new String("key");
        final String b = new String("key");
        assertEquals(a, b);
        map.put(a, 1);
        assertEquals(1, map.get(a));
        assertNull(map.get(b));
        assertNull(map.put(b, 2));
        assertEquals(1, map.put(a, 3));
        assertEquals(2, map.size());
        assertEquals(3, map.get(a));
        assertEquals(2, map.get(b));
        // Growth through several rehashes keeps every entry reachable.
        final Object[] many = new Object[500];
        final IdentityObjObjHashMap<Object, Integer> grown = new IdentityObjObjHashMap<>(1);
        for (int i = 0; i < many.length; i++) {
            many[i] = new Object();
            final int index = grown.keyIndex(many[i]);
            assertTrue(index >= 0);
            assertEquals(i, grown.putAt(index, many[i], i));
            assertEquals(i + 1, grown.size());
        }
        for (int i = 0; i < many.length; i++) {
            final int index = grown.keyIndex(many[i]);
            assertTrue(index < 0);
            assertEquals(i, grown.valueAtQuick(index));
        }
        assertFalse(grown.isEmpty());
        grown.clear();
        assertTrue(grown.isEmpty());
        assertNull(grown.get(many[0]));
    }

    @Test
    void objLongHashMapMatchesHashMap() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final ObjLongHashMap<Key> map = new ObjLongHashMap<>(4, Long.MIN_VALUE);
        final Map<Key, Long> reference = new HashMap<>();
        for (int op = 0; op < OPERATIONS; op++) {
            final Key k = new Key(rnd.nextInt(KEY_SPACE));
            switch (rnd.nextInt(5)) {
                case 0 -> {
                    reference.put(k, (long) op);
                    map.put(k, op);
                }
                case 1 -> {
                    final long delta = rnd.nextInt(100) - 50;
                    final long expected = reference.merge(k, delta, Long::sum);
                    assertEquals(expected, map.increment(k, delta));
                }
                case 2 -> assertEquals(reference.remove(k) != null, map.remove(k));
                default -> {
                    assertEquals(reference.getOrDefault(k, Long.MIN_VALUE), map.get(k));
                    assertEquals(reference.containsKey(k), map.contains(k));
                }
            }
            assertEquals(reference.size(), map.size());
        }
        final Map<Key, Long> seen = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                seen.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        assertEquals(reference, seen);
        assertEquals(Long.MIN_VALUE, map.noEntryValue());
        final int absent = map.keyIndex(new Key(KEY_SPACE + 1));
        assertTrue(absent >= 0);
        assertThrows(IllegalArgumentException.class, () -> map.valueAt(absent));
        map.putAt(absent, new Key(KEY_SPACE + 1), 7);
        final int present = map.keyIndex(new Key(KEY_SPACE + 1));
        assertEquals(7, map.valueAt(present));
        assertTrue(map.excludes(new Key(KEY_SPACE + 2)));
        assertTrue(map.notEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(-1, new ObjLongHashMap<String>().get("nothing"));
    }

    @Test
    void objHashSetMatchesHashSet() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final ObjHashSet<Key> set = new ObjHashSet<>(4);
        final Set<Key> reference = new HashSet<>();
        for (int op = 0; op < OPERATIONS; op++) {
            final Key k = new Key(rnd.nextInt(KEY_SPACE));
            switch (rnd.nextInt(3)) {
                case 0 -> assertEquals(reference.add(k), set.add(k));
                case 1 -> assertEquals(reference.remove(k), set.remove(k));
                default -> {
                    assertEquals(reference.contains(k), set.contains(k));
                    assertEquals(!reference.contains(k), set.excludes(k));
                }
            }
            assertEquals(reference.size(), set.size());
        }
        final Set<Key> seen = new HashSet<>();
        for (int s = 0, n = set.slots(); s < n; s++) {
            if (set.hasKeyAtSlot(s)) {
                seen.add(set.keyAtSlot(s));
            }
        }
        assertEquals(reference, seen);
        final int index = set.keyIndex(new Key(KEY_SPACE + 5));
        assertTrue(index >= 0);
        set.addAt(index, new Key(KEY_SPACE + 5));
        assertTrue(set.contains(new Key(KEY_SPACE + 5)));
        assertTrue(set.notEmpty());
        set.clear();
        assertTrue(set.isEmpty());
        assertTrue(new ObjHashSet<String>().isEmpty());
    }

    @Test
    void longObjHashMapMatchesHashMap() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final LongObjHashMap<String> map = new LongObjHashMap<>(4, Long.MIN_VALUE);
        final Map<Long, String> reference = new HashMap<>();
        for (int op = 0; op < OPERATIONS; op++) {
            // Negative keys too, and now and then the no-entry key itself: keys come from the file,
            // so the map has to store every long.
            final long k = rnd.nextInt(64) == 0 ? Long.MIN_VALUE : rnd.nextInt(KEY_SPACE) - KEY_SPACE / 2;
            if (rnd.nextBoolean()) {
                final String v = "v" + op;
                assertEquals(reference.put(k, v), map.put(k, v));
            } else {
                assertEquals(reference.get(k), map.get(k));
                assertEquals(reference.containsKey(k), map.contains(k));
                assertEquals(!reference.containsKey(k), map.excludes(k));
            }
            assertEquals(reference.size(), map.size());
        }
        final Map<Long, String> seen = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                seen.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        assertEquals(reference, seen);
        final int absent = map.keyIndex(Long.MAX_VALUE);
        assertTrue(absent >= 0);
        assertThrows(IllegalArgumentException.class, () -> map.valueAt(absent));
        assertEquals("x", map.putAt(absent, Long.MAX_VALUE, "x"));
        assertEquals("x", map.valueAt(map.keyIndex(Long.MAX_VALUE)));
        assertTrue(map.notEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(Long.MAX_VALUE));
        final LongObjHashMap<String> defaults = new LongObjHashMap<>();
        assertNull(defaults.put(0, "zero"));
        assertEquals("zero", defaults.put(0, "nil"));
        assertEquals("nil", defaults.get(0));
    }

    @Test
    void longObjHashMapStoresItsNoEntryKey() {
        // The default no-entry key is -1, a value a recording can hold (an id, an address).
        final LongObjHashMap<String> map = new LongObjHashMap<>(1);
        assertFalse(map.contains(-1));
        assertNull(map.get(-1));
        final int free = map.keyIndex(-1);
        assertTrue(free >= 0);
        assertEquals("minus one", map.putAt(free, -1, "minus one"));
        assertEquals(1, map.size());
        assertTrue(map.contains(-1));
        assertEquals("minus one", map.valueAt(map.keyIndex(-1)));
        assertEquals("minus one", map.put(-1, "again"));
        // Through several rehashes, and visible to iteration like any other entry.
        for (long k = 0; k < 100; k++) {
            map.put(k, "v" + k);
        }
        assertEquals(101, map.size());
        assertEquals("again", map.get(-1));
        final Map<Long, String> seen = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                seen.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        assertEquals(101, seen.size());
        assertEquals("again", seen.get(-1L));
        map.clear();
        assertTrue(map.isEmpty());
        assertFalse(map.contains(-1));
        assertNull(map.get(-1));
    }

    /**
     * {@code capacityFor(n)} is the promise that n inserts into a new table never rehash.
     * The identity map shares the arithmetic ({@link Hashes}) but exposes no slot count.
     */
    @Test
    void aTableSizedForNEntriesHoldsThemWithoutARehash() {
        for (final int n : new int[]{0, 1, 7, 8, 9, 16, 100, 1000}) {
            final ObjObjHashMap<Integer, Integer> objObj = new ObjObjHashMap<>(n);
            final ObjLongHashMap<Integer> objLong = new ObjLongHashMap<>(n, -1);
            final ObjHashSet<Integer> set = new ObjHashSet<>(n);
            final LongObjHashMap<Integer> longObj = new LongObjHashMap<>(n);
            final int[] before = {objObj.slots(), objLong.slots(), set.slots(), longObj.slots()};
            for (int i = 0; i < n; i++) {
                final Integer k = i;
                objObj.put(k, k);
                objLong.put(k, i);
                set.add(k);
                longObj.put(i, k);
            }
            assertEquals(before[0], objObj.slots(), "ObjObjHashMap for " + n);
            assertEquals(before[1], objLong.slots(), "ObjLongHashMap for " + n);
            assertEquals(before[2], set.slots(), "ObjHashSet for " + n);
            assertEquals(before[3], longObj.slots(), "LongObjHashMap for " + n);
            // The next insert past the load factor is the one that grows it.
            final int full = (int) (before[0] * Hashes.LOAD_FACTOR);
            for (int i = n; i <= full; i++) {
                objObj.put(i, i);
            }
            assertEquals(before[0] * 2, objObj.slots(), "ObjObjHashMap past " + full);
        }
    }

    @Test
    void capacityArithmetic() {
        assertEquals(1, Hashes.ceilPow2(0));
        assertEquals(1, Hashes.ceilPow2(1));
        assertEquals(2, Hashes.ceilPow2(2));
        assertEquals(4, Hashes.ceilPow2(3));
        assertEquals(1024, Hashes.ceilPow2(1000));
        assertEquals(1024, Hashes.ceilPow2(1024));
        assertEquals(16, Hashes.capacityFor(0));
        assertEquals(16, Hashes.capacityFor(8));
        assertEquals(32, Hashes.capacityFor(9));
        assertEquals(32, Hashes.capacityFor(16));
        assertEquals(9, Hashes.freeFor(16));
        // The array limit is 2^30 slots: past it the answer is an explanation, not a negative size.
        assertEquals(1 << 30, Hashes.capacityFor(1 << 29));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Hashes.capacityFor((1 << 29) + 1))
                .getMessage().contains("cannot hold"));
        assertEquals(1 << 30, Hashes.grow(1 << 29));
        assertTrue(assertThrows(IllegalStateException.class, () -> Hashes.grow(1 << 30)).getMessage().contains("full"));
        // A key at its home slot never moves; one probed past the hole does.
        assertTrue(Hashes.mayMove(3, 5, 2));
        assertFalse(Hashes.mayMove(3, 5, 4));
        assertFalse(Hashes.mayMove(3, 5, 5));
        assertTrue(Hashes.mayMove(14, 1, 12));
        assertFalse(Hashes.mayMove(14, 1, 15));
        assertFalse(Hashes.mayMove(14, 1, 0));
    }

    private static <K, V> Map<K, V> slotsOf(final ObjObjHashMap<K, V> map) {
        final Map<K, V> out = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                out.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        return out;
    }
}
