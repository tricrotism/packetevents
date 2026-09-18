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

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ColumnAllocationTest extends BaseDummyAPITest {

    private static final BaseChunk[] SECTIONS = new BaseChunk[24];

    @Test
    @DisplayName("Columns without biome data hold no biome array")
    public void testNoBiomeArrayWhenAbsent() {
        Map<HeightmapType, long[]> heightmaps = Collections.emptyMap();

        for (Column column : new Column[]{
                new Column(0, 0, true, SECTIONS, null),
                new Column(0, 0, true, SECTIONS, null, new NBTCompound()),
                new Column(0, 0, true, SECTIONS, null, heightmaps),
        }) {
            assertFalse(column.hasBiomeData(), "expected no biome data");
            assertNull(column.getBiomeDataInts(), "biome int array must not be allocated when absent");
            assertNull(column.getBiomeDataBytes(), "biome byte array must not be allocated when absent");
        }
    }

    @Test
    @DisplayName("Columns with biome data still expose it")
    public void testBiomeArrayKeptWhenPresent() {
        Column column = new Column(0, 0, true, SECTIONS, null, new int[1024]);
        assertTrue(column.hasBiomeData());
        assertNotNull(column.getBiomeDataInts());

        Column byteColumn = new Column(0, 0, true, SECTIONS, null, new byte[256]);
        assertTrue(byteColumn.hasBiomeData());
        assertNotNull(byteColumn.getBiomeDataBytes());
    }

    @Test
    @DisplayName("Heightmap accessors still work on columns without heightmaps")
    public void testHeightmapAccessorsRemainSafe() {
        Column column = new Column(0, 0, true, SECTIONS, null);
        assertFalse(column.hasHeightMaps());
        assertTrue(column.getHeightmaps().isEmpty());
        assertNotNull(column.getHeightMaps());
    }
}
