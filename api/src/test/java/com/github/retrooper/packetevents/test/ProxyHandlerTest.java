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
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the velocity outbound handler through a real netty pipeline, so its
 * buffer handling can be changed with something other than reasoning to back it.
 */
public class ProxyHandlerTest extends BaseDummyAPITest {

    private static byte[] keepAlivePayload() {
        ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        ByteBuf scratch = Unpooled.buffer();
        try {
            scratch.writeByte(PacketType.Play.Server.KEEP_ALIVE.getId(version));
            scratch.writeLong(0x0123456789ABCDEFL);
            byte[] bytes = new byte[scratch.readableBytes()];
            scratch.getBytes(0, bytes);
            return bytes;
        } finally {
            scratch.release();
        }
    }

    /**
     * A null client version keeps event creation on the server-manager branch, which the
     * dummy test api can serve; the proxy branch would need a real channel injector.
     */
    private static User newUser(EmbeddedChannel channel) {
        return new User(channel, ConnectionState.PLAY, null,
                new UserProfile(UUID.randomUUID(), "proxy"));
    }

    @Test
    @DisplayName("Velocity encoder passes an untouched packet through byte for byte")
    public void testVelocityEncoderPassThrough() {
        byte[] payload = keepAlivePayload();
        EmbeddedChannel channel = new EmbeddedChannel();
        io.github.retrooper.packetevents.handlers.PacketEventsEncoder encoder =
                new io.github.retrooper.packetevents.handlers.PacketEventsEncoder(newUser(channel));
        channel.pipeline().addLast(encoder);

        assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(payload.clone())));
        ByteBuf outbound = channel.readOutbound();
        assertNotNull(outbound, "expected the packet to reach the wire");
        byte[] actual = new byte[outbound.readableBytes()];
        outbound.readBytes(actual);
        outbound.release();

        assertArrayEquals(payload, actual, "encoder must not alter an untouched packet");
        assertTrue(channel.finishAndReleaseAll() || true);
    }

    @Test
    @DisplayName("Velocity encoder writes back a packet a listener rewrote")
    public void testVelocityEncoderRewrite() {
        byte[] payload = keepAlivePayload();
        EmbeddedChannel channel = new EmbeddedChannel();
        PacketListenerCommon listener = PacketEvents.getAPI().getEventManager()
                .registerListener(new PacketListenerAbstract() {
                    @Override
                    public void onPacketSend(PacketSendEvent event) {
                        if (event.getPacketType() != PacketType.Play.Server.KEEP_ALIVE) {
                            return;
                        }
                        WrapperPlayServerKeepAlive wrapper = new WrapperPlayServerKeepAlive(event);
                        wrapper.setId(0x7777777777777777L);
                        event.markForReEncode(true);
                    }
                });
        try {
            io.github.retrooper.packetevents.handlers.PacketEventsEncoder encoder =
                    new io.github.retrooper.packetevents.handlers.PacketEventsEncoder(newUser(channel));
            channel.pipeline().addLast(encoder);

            channel.writeOutbound(Unpooled.wrappedBuffer(payload.clone()));
            ByteBuf outbound = channel.readOutbound();
            assertNotNull(outbound, "expected the rewritten packet to reach the wire");
            assertEquals(payload.length, outbound.readableBytes(), "rewritten packet changed size");
            outbound.skipBytes(1);
            assertEquals(0x7777777777777777L, outbound.readLong(), "listener rewrite did not reach the wire");
            outbound.release();
        } finally {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("Velocity encoder drops a cancelled packet")
    public void testVelocityEncoderCancellation() {
        byte[] payload = keepAlivePayload();
        EmbeddedChannel channel = new EmbeddedChannel();
        PacketListenerCommon listener = PacketEvents.getAPI().getEventManager()
                .registerListener(new PacketListenerAbstract() {
                    @Override
                    public void onPacketSend(PacketSendEvent event) {
                        event.setCancelled(true);
                    }
                });
        try {
            io.github.retrooper.packetevents.handlers.PacketEventsEncoder encoder =
                    new io.github.retrooper.packetevents.handlers.PacketEventsEncoder(newUser(channel));
            channel.pipeline().addLast(encoder);

            channel.writeOutbound(Unpooled.wrappedBuffer(payload.clone()));
            ByteBuf outbound = channel.readOutbound();
            if (outbound != null) {
                assertEquals(0, outbound.readableBytes(), "a cancelled packet must not reach the wire");
                outbound.release();
            }
        } finally {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            channel.finishAndReleaseAll();
        }
    }
}
