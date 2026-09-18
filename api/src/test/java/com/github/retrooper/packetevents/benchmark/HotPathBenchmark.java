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

package com.github.retrooper.packetevents.benchmark;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.PacketSide;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.test.base.TestPacketEventsBuilder;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the three paths this work touched. Run with
 * {@code ./gradlew :api:jmh -PjmhArgs="-prof gc"} to get allocation per operation alongside the timings.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class HotPathBenchmark {

    private static final int[] PACKET_IDS = {5, 130, 140};

    private Plugin plugin;
    private Chunk_v1_18 chunk;
    private ClientVersion version;
    private EmbeddedChannel channel;
    private User user;
    private ByteBuf buffer;

    private int blockCursor;
    private int packetIdCursor;

    private PacketWrapper<?> sectionWrapper;
    private BaseChunk[] emptySections;
    private NBTCompound heightmaps;
    private PacketWrapper<?> itemWrapper;
    private PacketWrapper<?> metadataWrapper;
    private int registryCursor;
    private EmbeddedChannel velocityChannel;
    private byte[] proxyPayload;

    @Setup(Level.Trial)
    public void setup() {
        MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin("packetevents");
        PacketEvents.setAPI(TestPacketEventsBuilder.buildNoCache(this.plugin));
        PacketType.prepare();

        this.version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        this.chunk = new Chunk_v1_18(this.version);
        int[] ids = new int[]{
                StateTypes.STONE.createBlockState(this.version).getGlobalId(),
                StateTypes.DIRT.createBlockState(this.version).getGlobalId(),
                StateTypes.OAK_LOG.createBlockState(this.version).getGlobalId(),
                StateTypes.GLASS.createBlockState(this.version).getGlobalId(),
                StateTypes.WATER.createBlockState(this.version).getGlobalId(),
        };
        int i = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    this.chunk.set(x, y, z, ids[i++ % ids.length]);
                }
            }
        }

        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
        });
        this.channel = new EmbeddedChannel();
        this.user = new User(this.channel, ConnectionState.PLAY, this.version,
                new UserProfile(UUID.randomUUID(), "bench"));
        this.buffer = Unpooled.buffer();
        this.buffer.writeByte(PacketType.Play.Server.KEEP_ALIVE.getId(this.version));
        this.buffer.writeLong(1234L);

        // one serialized chunk section, decoded fresh on every invocation
        PacketWrapper<?> writer = PacketWrapper.createUniversalPacketWrapper(Unpooled.buffer());
        Chunk_v1_18.write(writer, this.chunk);
        this.sectionWrapper = PacketWrapper.createUniversalPacketWrapper(writer.getBuffer());

        this.emptySections = new BaseChunk[24];
        for (int s = 0; s < this.emptySections.length; s++) {
            this.emptySections[s] = new Chunk_v1_18(this.version);
        }
        this.heightmaps = new NBTCompound();

        // a plain stack with no component patches, the common case in inventory packets
        PacketWrapper<?> itemWriter = PacketWrapper.createUniversalPacketWrapper(Unpooled.buffer());
        itemWriter.writeItemStack(ItemStack.builder()
                .type(ItemTypes.DIAMOND_SWORD).amount(1).build());
        this.itemWrapper = PacketWrapper.createUniversalPacketWrapper(itemWriter.getBuffer());

        // a small metadata update, the shape entity packets actually carry
        PacketWrapper<?> metaWriter = PacketWrapper.createUniversalPacketWrapper(Unpooled.buffer());
        List<EntityData<?>> metadata = new ArrayList<>();
        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0));
        metadata.add(new EntityData<>(1, EntityDataTypes.INT, 300));
        metadata.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, Boolean.TRUE));
        metaWriter.writeEntityMetadata(metadata);
        this.metadataWrapper = PacketWrapper.createUniversalPacketWrapper(metaWriter.getBuffer());

        // a forwarded packet of realistic size, where copying it actually costs something
        this.proxyPayload = new byte[1024];
        this.proxyPayload[0] = (byte) PacketType.Play.Server.KEEP_ALIVE.getId(this.version);
        this.velocityChannel = new EmbeddedChannel();
        this.velocityChannel.pipeline().addLast(
                new io.github.retrooper.packetevents.handlers.PacketEventsEncoder(
                        new User(this.velocityChannel, ConnectionState.PLAY, null,
                                new UserProfile(UUID.randomUUID(), "proxy"))));
    }

    @TearDown(Level.Trial)
    public void teardown() {
        this.buffer.release();
        PacketEvents.setAPI(null);
        TestPacketEventsBuilder.clearBuildCache();
        MockBukkit.unmock();
    }

    @Benchmark
    public WrappedBlockState blockStateLookup() {
        int cursor = this.blockCursor = (this.blockCursor + 1) & 4095;
        return this.chunk.get(cursor & 15, (cursor >> 4) & 15, (cursor >> 8) & 15);
    }

    @Benchmark
    public PacketTypeCommon packetTypeLookup() {
        int cursor = this.packetIdCursor = (this.packetIdCursor + 1) % PACKET_IDS.length;
        return PacketType.getById(PacketSide.SERVER, ConnectionState.PLAY, this.version, PACKET_IDS[cursor]);
    }

    @Benchmark
    public Object clientBoundPacket() throws Exception {
        this.buffer.readerIndex(0);
        return PacketEventsImplHelper.handleClientBoundPacket(this.channel, this.user, null, this.buffer, true);
    }

    @Benchmark
    public Chunk_v1_18 chunkSectionDecode() {
        this.sectionWrapper.getBuffer();
        ((ByteBuf) this.sectionWrapper.getBuffer()).readerIndex(0);
        return Chunk_v1_18.read(this.sectionWrapper);
    }

    @Benchmark
    public Column columnConstruction() {
        return new Column(0, 0, true, this.emptySections, null, this.heightmaps);
    }

    @Benchmark
    public ItemStack itemStackDecode() {
        ((ByteBuf) this.itemWrapper.getBuffer()).readerIndex(0);
        return this.itemWrapper.readItemStack();
    }

    @Benchmark
    public Object entityMetadataDecode() {
        ((ByteBuf) this.metadataWrapper.getBuffer()).readerIndex(0);
        return this.metadataWrapper.readEntityMetadata();
    }

    /**
     * One outbound packet through the velocity encoder. Sized like a real forwarded
     * payload, since the cost being measured is a full packet copy.
     */
    @Benchmark
    public Object velocityEncode() {
        this.velocityChannel.writeOutbound(Unpooled.wrappedBuffer(this.proxyPayload));
        ByteBuf outbound = this.velocityChannel.readOutbound();
        if (outbound != null) {
            outbound.release();
        }
        return outbound;
    }

    /**
     * Item ids run past the Integer cache, unlike entity data type ids, so this is
     * where the registry's boxed map lookup actually costs an allocation.
     */
    @Benchmark
    public Object registryLookupLargeId() {
        int cursor = this.registryCursor = (this.registryCursor + 1) & 255;
        return ItemTypes.getRegistry().getById(this.version, 600 + cursor);
    }
}
