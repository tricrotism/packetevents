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
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.UUID;

/**
 * Reports what one packet costs in allocated bytes after the hot path changes.
 * This is a measurement harness rather than a threshold assertion, so it prints
 * the figure and only fails if the path regresses far beyond the event object.
 */
public class PacketPathAllocationTest extends BaseDummyAPITest {

    private static final ClientVersion VERSION = ClientVersion.V_1_21_2;
    private static final int WARMUP = 100_000;
    private static final int MEASURED = 100_000;

    private static ByteBuf keepAliveBuffer(ClientVersion version) {
        int id = PacketType.Play.Server.KEEP_ALIVE.getId(version);
        if (id >= 128) {
            throw new IllegalStateException("expected a single byte packet id, got " + id);
        }
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(id);
        buffer.writeLong(1234L);
        return buffer;
    }

    @Test
    @DisplayName("Report bytes allocated per clientbound packet")
    public void testClientBoundPacketAllocation() throws Exception {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().getId();

        PacketListenerCommon listener = PacketEvents.getAPI().getEventManager()
                .registerListener(new PacketListenerAbstract() {
                });
        try {
            // the spigot handler passes autoProtocolTranslation = true, which resolves the
            // version from the server manager, so the packet id must match that version
            ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
            EmbeddedChannel channel = new EmbeddedChannel();
            User user = new User(channel, ConnectionState.PLAY, version,
                    new UserProfile(UUID.randomUUID(), "bench"));
            ByteBuf buffer = keepAliveBuffer(version);

            for (int i = 0; i < WARMUP; i++) {
                buffer.readerIndex(0);
                PacketEventsImplHelper.handleClientBoundPacket(channel, user, null, buffer, true);
            }

            long best = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long before = threads.getThreadAllocatedBytes(threadId);
                for (int i = 0; i < MEASURED; i++) {
                    buffer.readerIndex(0);
                    PacketEventsImplHelper.handleClientBoundPacket(channel, user, null, buffer, true);
                }
                best = Math.min(best, threads.getThreadAllocatedBytes(threadId) - before);
            }

            // isolate the event object so we can tell how much of the packet cost is anything else
            for (int i = 0; i < WARMUP; i++) {
                buffer.readerIndex(0);
                com.github.retrooper.packetevents.util.EventCreationUtil
                        .createSendEvent(channel, user, null, buffer, true);
            }
            long eventOnly = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long before = threads.getThreadAllocatedBytes(threadId);
                for (int i = 0; i < MEASURED; i++) {
                    buffer.readerIndex(0);
                    com.github.retrooper.packetevents.util.EventCreationUtil
                            .createSendEvent(channel, user, null, buffer, true);
                }
                eventOnly = Math.min(eventOnly, threads.getThreadAllocatedBytes(threadId) - before);
            }

            double bytesPerPacket = best / (double) MEASURED;
            System.out.printf("clientbound packet: %d bytes over %d packets, %.1f bytes/packet"
                            + " (event object alone %.1f, everything else %.1f)%n",
                    best, MEASURED, bytesPerPacket,
                    eventOnly / (double) MEASURED, (best - eventOnly) / (double) MEASURED);
            buffer.release();
            org.junit.jupiter.api.Assertions.assertTrue(bytesPerPacket < 200,
                    "packet path regressed, measured " + bytesPerPacket + " bytes/packet");
        } finally {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
    }
}
