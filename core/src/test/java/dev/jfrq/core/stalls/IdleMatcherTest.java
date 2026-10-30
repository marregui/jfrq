// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleMatcherTest {

    static Stack top(final String type, final String method) {
        return new Stack(List.of(new Frame(type, method, 0, "Native"),
                new Frame("io.netty.channel.nio.NioEventLoop", "run", 500, "JIT compiled")), false);
    }

    @ParameterizedTest
    @CsvSource({
            "sun.nio.ch.KQueue, poll",
            "sun.nio.ch.EPoll, wait",
            "sun.nio.ch.WEPoll, wait",
            "sun.nio.ch.EPollSelectorImpl, doSelect",
            "sun.nio.ch.SelectorImpl, select",
            "sun.nio.ch.SelectorImpl, lockAndDoSelect",
            "io.netty.channel.epoll.Native, epollWait",
            "io.netty.channel.epoll.Native, epollWait0",
            "io.netty.channel.kqueue.Native, keventWait",
            "jdk.internal.misc.Unsafe, park",
            "java.util.concurrent.locks.LockSupport, parkNanos",
            "java.lang.Object, wait0",
    })
    void defaultsRecogniseSelectorsAndParks(final String type, final String method) {
        assertTrue(IdleMatcher.defaults().isIdle(top(type, method)), type + "." + method);
    }

    @ParameterizedTest
    @CsvSource({
            "sun.nio.ch.SocketDispatcher, read0",
            "dev.app.CpuWork, burn",
            "java.lang.Thread, sleep",
            "io.netty.channel.nio.NioEventLoop, processSelectedKeys",
    })
    void defaultsRejectWork(final String type, final String method) {
        assertFalse(IdleMatcher.defaults().isIdle(top(type, method)), type + "." + method);
    }

    @Test
    void onlyTheInnermostFramesCount() {
        final Stack deepIdle = new Stack(List.of(
                new Frame("dev.app.Work", "a", 1, "JIT compiled"),
                new Frame("dev.app.Work", "b", 1, "JIT compiled"),
                new Frame("dev.app.Work", "c", 1, "JIT compiled"),
                new Frame("sun.nio.ch.KQueue", "poll", 0, "Native")), false);
        assertFalse(IdleMatcher.defaults().isIdle(deepIdle));
        final Stack thirdFrame = new Stack(List.of(
                new Frame("dev.app.Work", "a", 1, "JIT compiled"),
                new Frame("dev.app.Work", "b", 1, "JIT compiled"),
                new Frame("sun.nio.ch.KQueue", "poll", 0, "Native")), false);
        assertTrue(IdleMatcher.defaults().isIdle(thirdFrame));
        assertFalse(IdleMatcher.defaults().isIdle(Stack.EMPTY));
    }

    @Test
    void aCallerBlockedInManagedBlockIsNotWaitingForWork() {
        // JDK 25 routes CompletableFuture.get and every untimed Condition.await through
        // ForkJoinPool.managedBlock: the frame says nothing about who is waiting for what.
        final IdleMatcher workWaits = IdleMatcher.forWorkWaits();
        assertFalse(workWaits.isIdle(StallAnalysisTest.AWAITING_RESULT));
        // The pool's own frame is still found under the same machinery, eight frames down.
        assertTrue(workWaits.isIdle(StallAnalysisTest.NO_WORK));
    }

    @Test
    void workWaitsLookTenFramesDeep() {
        final List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            frames.add(new Frame("dev.app.Wrapper", "level" + i, 1, "JIT compiled"));
        }
        frames.add(new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1016, "JIT compiled"));
        assertTrue(IdleMatcher.forWorkWaits().isIdle(new Stack(frames, false)));
        frames.addFirst(new Frame("dev.app.Wrapper", "deeper", 1, "JIT compiled"));
        assertFalse(IdleMatcher.forWorkWaits().isIdle(new Stack(frames, false)));
    }

    @Test
    void customPatternsReplaceTheDefaults() {
        final IdleMatcher custom = IdleMatcher.of("dev\\.app\\.Loop\\.take, org\\.x\\.Y\\.z");
        assertTrue(custom.isIdle(top("dev.app.Loop", "take")));
        assertTrue(custom.isIdle(top("org.x.Y", "z")));
        assertFalse(custom.isIdle(top("sun.nio.ch.KQueue", "poll")));
        assertEquals("dev\\.app\\.Loop\\.take, org\\.x\\.Y\\.z", custom.toString());
        assertThrows(IllegalArgumentException.class, () -> IdleMatcher.of(" , "));
        assertThrows(IllegalArgumentException.class, () -> IdleMatcher.of("(unclosed"));
    }
}
