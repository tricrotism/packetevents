/*
 * This file is part of packetevents - https://github.com/retrooper/packetevents
 * Copyright (C) 2025 retrooper and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.retrooper.packetevents.test;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.Axis;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChunkGetAllocationTest extends BaseDummyAPITest {

    private static final int ROUNDS = 5;
    private static final int SWEEPS_PER_ROUND = 40;

    private static Chunk_v1_18 buildChunk(ClientVersion version) {
        Chunk_v1_18 chunk = new Chunk_v1_18(version);
        int[] ids = new int[]{
                StateTypes.STONE.createBlockState(version).getGlobalId(),
                StateTypes.DIRT.createBlockState(version).getGlobalId(),
                StateTypes.OAK_LOG.createBlockState(version).getGlobalId(),
                StateTypes.GLASS.createBlockState(version).getGlobalId(),
                StateTypes.WATER.createBlockState(version).getGlobalId(),
        };
        int i = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    chunk.set(x, y, z, ids[i++ % ids.length]);
                }
            }
        }
        return chunk;
    }

    private static long sweep(Chunk_v1_18 chunk, int sweeps) {
        long sink = 0;
        for (int i = 0; i < sweeps; i++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        sink += chunk.get(x, y, z).getGlobalId();
                    }
                }
            }
        }
        return sink;
    }

    @Test
    @DisplayName("BaseChunk#get allocates no bytes per call")
    public void testChunkGetAllocation() {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemorySupported(), "thread allocation sampling unavailable");
        threads.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().getId();

        ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        Chunk_v1_18 chunk = buildChunk(version);

        long sink = sweep(chunk, 200);

        long bestBytes = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            long before = threads.getThreadAllocatedBytes(threadId);
            sink += sweep(chunk, SWEEPS_PER_ROUND);
            long allocated = threads.getThreadAllocatedBytes(threadId) - before;
            bestBytes = Math.min(bestBytes, allocated);
        }

        long calls = (long) SWEEPS_PER_ROUND * 4096;
        double bytesPerCall = bestBytes / (double) calls;
        System.out.printf("BaseChunk#get: %d bytes over %d calls, %.3f bytes/call (sink=%d)%n",
                bestBytes, calls, bytesPerCall, sink);

        assertTrue(bytesPerCall < 1.0,
                "expected a zero-allocation read path, measured " + bytesPerCall + " bytes/call");
    }

    @ParameterizedTest
    @EnumSource(ClientVersion.class)
    @DisplayName("Every global id resolves to the state carrying that id")
    public void testIdLookupIsAligned(ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_1_8) || version.isNewerThan(ClientVersion.getLatest())) {
            return;
        }
        int resolved = 0;
        for (int id = 1; id < 40000; id++) {
            WrappedBlockState state = WrappedBlockState.getByGlobalId(version, id, false);
            if (state.getType() == StateTypes.AIR) {
                continue;
            }
            assertEquals(id, state.getGlobalId(), "id " + id + " on " + version + " resolved to a different state");
            resolved++;
        }
        assertTrue(resolved > 0, "no block states resolved for " + version);
    }

    @Test
    @DisplayName("Shared block states reject mutation, cloned ones accept it")
    public void testSharedStateIsProtected() {
        ClientVersion version = ClientVersion.V_1_21_2;
        int paleOakLog = StateTypes.PALE_OAK_LOG.createBlockState(version).getGlobalId();

        WrappedBlockState shared = WrappedBlockState.getByGlobalId(version, paleOakLog, false);
        assertSame(shared, WrappedBlockState.getByGlobalId(version, paleOakLog, false));
        assertThrows(IllegalStateException.class, () -> shared.setAxis(Axis.Z));
        assertEquals(paleOakLog, shared.getGlobalId());

        WrappedBlockState owned = WrappedBlockState.getByGlobalId(version, paleOakLog, true);
        assertNotSame(shared, owned);
        owned.setAxis(Axis.Z);
        assertEquals(paleOakLog + 1, owned.getGlobalId());
        assertEquals(paleOakLog, shared.getGlobalId());

        Chunk_v1_18 chunk = new Chunk_v1_18(version);
        chunk.set(0, 0, 0, paleOakLog);
        assertThrows(IllegalStateException.class, () -> chunk.get(version, 0, 0, 0).setAxis(Axis.Z));
        chunk.get(version, 0, 0, 0, true).setAxis(Axis.Z);
    }
}
