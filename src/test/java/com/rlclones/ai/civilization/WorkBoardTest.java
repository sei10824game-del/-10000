package com.rlclones.ai.civilization;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkBoardTest {
    private WorkBoard.Key key(UUID requester) { return new WorkBoard.Key(requester, "minecraft:overworld", "default", WorkBoard.FOOD); }
    @Test void repeatedRequestsUpdateWithoutDuplicatingOrStealingTheJob() {
        var board = new WorkBoard(); var key = key(UUID.randomUUID()); UUID worker = UUID.randomUUID();
        assertTrue(board.post(key, 4, 0)); assertTrue(board.claim(key, worker, 1));
        board.post(key, 8, 2);
        assertEquals(1, board.jobs(2).size()); assertEquals(8, board.find(key, 2).count);
        assertEquals(worker, board.find(key, 2).worker(2));
    }
    @Test void exactlyOneCourierCanClaimUntilLeaseExpires() {
        var board = new WorkBoard(); var key = key(UUID.randomUUID()); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        board.post(key, 4, 0); assertTrue(board.claim(key, a, 0));
        assertFalse(board.claim(key, b, 99)); assertTrue(board.claim(key, b, 100));
        assertFalse(board.complete(key, a, 101)); assertTrue(board.complete(key, b, 101));
        assertTrue(board.jobs(101).isEmpty());
    }
    @Test void heartbeatExtendsLeaseAndInterruptionsReleaseIt() {
        var board = new WorkBoard(); var key = key(UUID.randomUUID()); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        board.post(key, 4, 0); board.claim(key, a, 0); board.claim(key, a, 80);
        assertFalse(board.claim(key, b, 100));
        board.release(key, b); assertFalse(board.claim(key, b, 101));
        board.release(key, a); assertTrue(board.claim(key, b, 102));
    }
    @Test void staleRequestsCannotBeClaimedOrRetained() {
        var board = new WorkBoard(); var key = key(UUID.randomUUID());
        board.post(key, 4, 0);
        assertFalse(board.claim(key, UUID.randomUUID(), WorkBoard.LIFETIME));
        assertNull(board.find(key, WorkBoard.LIFETIME));
    }
    @Test void requesterCannotClaimTheirOwnJob() {
        var board = new WorkBoard(); UUID requester = UUID.randomUUID(); var key = key(requester);
        board.post(key, 4, 0); assertFalse(board.claim(key, requester, 0));
    }
    @Test void invalidCountsAndCapacityAreBounded() {
        var board = new WorkBoard();
        assertFalse(board.post(key(UUID.randomUUID()), 0, 0));
        assertFalse(board.post(key(UUID.randomUUID()), 65, 0));
        for (int i = 0; i < 256; i++) assertTrue(board.post(key(UUID.randomUUID()), 1, 0));
        assertFalse(board.post(key(UUID.randomUUID()), 1, 0));
        assertTrue(board.post(key(UUID.randomUUID()), 1, WorkBoard.LIFETIME));
    }
    @Test void teamDimensionAndItemRemainSeparate() {
        var board = new WorkBoard(); UUID requester = UUID.randomUUID();
        board.post(key(requester), 4, 0);
        board.post(new WorkBoard.Key(requester, "minecraft:the_nether", "default", WorkBoard.FOOD), 4, 0);
        board.post(new WorkBoard.Key(requester, "minecraft:overworld", "other", WorkBoard.FOOD), 4, 0);
        board.post(new WorkBoard.Key(requester, "minecraft:overworld", "default", "minecraft:iron_pickaxe"), 1, 0);
        assertEquals(4, board.jobs(0).size());
    }
}
