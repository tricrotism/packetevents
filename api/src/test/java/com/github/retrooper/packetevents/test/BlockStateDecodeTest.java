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
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.Axis;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BlockStateDecodeTest extends BaseDummyAPITest {

    private static final ClientVersion VERSION = ClientVersion.V_1_21_2;

    private static NBTCompound blockTag(String name, String property, String value) {
        NBTCompound properties = new NBTCompound();
        properties.setTag(property, new NBTString(value));
        NBTCompound tag = new NBTCompound();
        tag.setTag("Name", new NBTString(name));
        tag.setTag("Properties", properties);
        return tag;
    }

    @Test
    @DisplayName("Decoding applies lowercase vanilla property values")
    public void testDecodeAcceptsVanillaCasing() {
        WrappedBlockState decoded = WrappedBlockState.decode(blockTag("minecraft:pale_oak_log", "axis", "z"), VERSION);
        assertEquals(StateTypes.PALE_OAK_LOG, decoded.getType());
        assertEquals(Axis.Z, decoded.getData(StateValue.AXIS));
    }

    @Test
    @DisplayName("Decoded state carries the global id of the decoded properties")
    public void testDecodeRefreshesGlobalId() {
        int defaultId = StateTypes.PALE_OAK_LOG.createBlockState(VERSION).getGlobalId();
        WrappedBlockState decoded = WrappedBlockState.decode(blockTag("minecraft:pale_oak_log", "axis", "z"), VERSION);
        assertEquals(defaultId + 1, decoded.getGlobalId());
    }

    @Test
    @DisplayName("Decoding does not write into the shared default state data")
    public void testDecodeDoesNotCorruptSharedDefaults() {
        Object axisBefore = StateTypes.OAK_LOG.createBlockState(VERSION).getData(StateValue.AXIS);
        int idBefore = StateTypes.OAK_LOG.createBlockState(VERSION).getGlobalId();

        WrappedBlockState.decode(blockTag("minecraft:pale_oak_log", "axis", "z"), VERSION);

        assertEquals(axisBefore, StateTypes.OAK_LOG.createBlockState(VERSION).getData(StateValue.AXIS));
        assertEquals(idBefore, StateTypes.OAK_LOG.createBlockState(VERSION).getGlobalId());
    }
}
