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

import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.PacketSide;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import com.github.retrooper.packetevents.util.VersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PacketTypeLookupTest extends BaseDummyAPITest {

    private static final int MAX_PROBED_ID = 512;

    @ParameterizedTest
    @EnumSource(ClientVersion.class)
    @DisplayName("Every packet id resolves to the type carrying that id")
    public void testPacketIdLookupIsAligned(ClientVersion version) {
        PacketType.prepare();
        int resolved = 0;
        for (PacketSide side : PacketSide.values()) {
            for (ConnectionState state : new ConnectionState[]{ConnectionState.PLAY, ConnectionState.CONFIGURATION}) {
                for (int id = 0; id < MAX_PROBED_ID; id++) {
                    PacketTypeCommon type = PacketType.getById(side, state, version, id);
                    if (type == null) {
                        continue;
                    }
                    assertEquals(id, type.getId(version),
                            "id " + id + " on " + version + " " + side + "/" + state + " resolved to a different type");
                    resolved++;
                }
            }
        }
        assertTrue(resolved > 0, "no packet types resolved for " + version);
    }

    @ParameterizedTest
    @EnumSource(ClientVersion.class)
    @DisplayName("Out of range packet ids resolve to null rather than throwing")
    public void testOutOfRangeIdsAreNull(ClientVersion version) {
        PacketType.prepare();
        for (PacketSide side : PacketSide.values()) {
            for (ConnectionState state : new ConnectionState[]{ConnectionState.PLAY, ConnectionState.CONFIGURATION}) {
                assertNull(PacketType.getById(side, state, version, Integer.MAX_VALUE));
                assertNull(PacketType.getById(side, state, version, -1));
                assertNull(PacketType.getById(side, state, version, Integer.MIN_VALUE));
            }
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("PacketType#getById allocates no bytes per call")
    public void testGetByIdAllocation() {
        PacketType.prepare();
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().getId();
        ClientVersion version = ClientVersion.getLatest();

        // ids at or above 128 fall outside the Integer cache, so the old map lookup boxed every call
        int sink = 0;
        for (int i = 0; i < 200_000; i++) {
            sink += lookupSweep(version);
        }

        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long before = threads.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < 100_000; i++) {
                sink += lookupSweep(version);
            }
            best = Math.min(best, threads.getThreadAllocatedBytes(threadId) - before);
        }

        long calls = 100_000L * 3;
        double bytesPerCall = best / (double) calls;
        System.out.printf("PacketType#getById: %d bytes over %d calls, %.3f bytes/call (sink=%d)%n",
                best, calls, bytesPerCall, sink);
        assertTrue(bytesPerCall < 1.0,
                "expected a zero-allocation packet id lookup, measured " + bytesPerCall + " bytes/call");
    }

    private static final int[] PROBED_IDS = {5, 130, 140};

    private static int lookupSweep(ClientVersion version) {
        int sum = 0;
        for (int id : PROBED_IDS) {
            PacketTypeCommon type = PacketType.getById(PacketSide.SERVER, ConnectionState.PLAY, version, id);
            sum += type == null ? 0 : 1;
        }
        return sum;
    }

    @ParameterizedTest
    @EnumSource(ClientVersion.class)
    @DisplayName("Cached version index matches a fresh reverse scan")
    public void testVersionMapperIndex(ClientVersion version) {
        VersionMapper mapper = new VersionMapper(
                ClientVersion.V_1_8, ClientVersion.V_1_13, ClientVersion.V_1_16,
                ClientVersion.V_1_20_5, ClientVersion.V_1_21_2, ClientVersion.getLatest());
        ClientVersion[] reversed = mapper.getReversedVersions();
        int expected = 0;
        for (int i = 0; i < reversed.length; i++) {
            if (version.isNewerThanOrEquals(reversed[i])) {
                expected = reversed.length - 1 - i;
                break;
            }
        }
        assertEquals(expected, mapper.getIndex(version), "version index changed for " + version);
    }
}
