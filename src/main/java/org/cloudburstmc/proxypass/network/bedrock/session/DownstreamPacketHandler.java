package org.cloudburstmc.proxypass.network.bedrock.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;

import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.util.stream.LittleEndianDataOutputStream;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.util.NbtBlockDefinitionRegistry;
import org.cloudburstmc.proxypass.network.bedrock.util.RecipeUtils;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@RequiredArgsConstructor
public class DownstreamPacketHandler implements BedrockPacketHandler {
    private final BedrockSession session;
    private final ProxyPlayerSession player;
    private final ProxyPass proxy;

    @Override
    public PacketSignal handle(AvailableEntityIdentifiersPacket packet) {
        proxy.saveNBT("entity_identifiers", packet.getIdentifiers());
        return PacketSignal.UNHANDLED;
    }

    // Handles biome definitions when client-side chunk generation is enabled
    @Override
    public PacketSignal handle(CompressedBiomeDefinitionListPacket packet) {
        proxy.saveNBT("biome_definitions_compressed", packet.getDefinitions());
        return PacketSignal.UNHANDLED;
    }

    // Handles biome definitions when client-side chunk generation is disabled
    @Override
    public PacketSignal handle(BiomeDefinitionListPacket packet) {
        proxy.saveNBT("biome_definitions", packet.getDefinitions());
        return PacketSignal.UNHANDLED;
    }

    private static long decodeVarInt(ByteBuf buffer, int maxBits) {
        long result = 0;
        for (int shift = 0; shift < maxBits; shift += 7) {
            final byte b = buffer.readByte();
            result |= (b & 0x7FL) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new ArithmeticException("VarInt was too large");
    }

    // Modified version of VarInts.readInt that works on a ByteBuf
    public static int readVarInt(ByteBuf buffer) {
        int n = (int) decodeVarInt(buffer, 32);
        return (n >>> 1) ^ -(n & 1);
    }

    private static byte[] encodeFull(long value) {
        byte[] bytes;
        if ((value & ~0x1FFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) (value >>> 14)
            };
        } else if ((value & ~0xFFFFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) (value >>> 21)
            };
        } else if ((value & ~0x7FFFFFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) ((value >>> 21) & 0x7FL | 0x80L),
                    (byte) (value >>> 28)
            };
        } else if ((value & ~0x3FFFFFFFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) ((value >>> 21) & 0x7FL | 0x80L),
                    (byte) ((value >>> 28) & 0x7FL | 0x80L),
                    (byte) (value >>> 35)
            };
        } else if ((value & ~0x1FFFFFFFFFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) ((value >>> 21) & 0x7FL | 0x80L),
                    (byte) ((value >>> 28) & 0x7FL | 0x80L),
                    (byte) ((value >>> 35) & 0x7FL | 0x80L),
                    (byte) (value >>> 42)
            };
        } else if ((value & ~0xFFFFFFFFFFFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) ((value >>> 21) & 0x7FL | 0x80L),
                    (byte) ((value >>> 28) & 0x7FL | 0x80L),
                    (byte) ((value >>> 35) & 0x7FL | 0x80L),
                    (byte) ((value >>> 42) & 0x7FL | 0x80L),
                    (byte) (value >>> 49)
            };
        } else if ((value & ~0x7FFFFFFFFFFFFFFFL) == 0) {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) ((value >>> 21) & 0x7FL | 0x80L),
                    (byte) ((value >>> 28) & 0x7FL | 0x80L),
                    (byte) ((value >>> 35) & 0x7FL | 0x80L),
                    (byte) ((value >>> 42) & 0x7FL | 0x80L),
                    (byte) ((value >>> 49) & 0x7FL | 0x80L),
                    (byte) (value >>> 56)
            };
        } else {
            bytes = new byte[] {
                    (byte) (value & 0x7FL | 0x80L),
                    (byte) ((value >>> 7) & 0x7FL | 0x80L),
                    (byte) ((value >>> 14) & 0x7FL | 0x80L),
                    (byte) ((value >>> 21) & 0x7FL | 0x80L),
                    (byte) ((value >>> 28) & 0x7FL | 0x80L),
                    (byte) ((value >>> 35) & 0x7FL | 0x80L),
                    (byte) ((value >>> 42) & 0x7FL | 0x80L),
                    (byte) ((value >>> 49) & 0x7FL | 0x80L),
                    (byte) ((value >>> 56) & 0x7FL | 0x80L),
                    (byte) (value >>> 63)
            };
        }
        return bytes;
    }

    public static void writeVarInt(ByteBuf buffer, int value) {
        long longValue = ((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL;

        // Peel the one and two byte count cases explicitly as they are the most common VarInt sizes
        // that the server will write, to improve inlining.
        if ((longValue & ~0x7FL) == 0) {
            buffer.ensureWritable(1);
            buffer.writeByte((byte) longValue);
        } else if ((longValue & ~0x3FFFL) == 0) {
            byte[] bytes = {
                    (byte) (longValue & 0x7FL | 0x80L),
                    (byte) (longValue >>> 7)
            };
            buffer.ensureWritable(2);
            buffer.writeBytes(bytes);
        } else {
            byte[] bytesToWrite = encodeFull(longValue);
            buffer.ensureWritable(bytesToWrite.length);
            buffer.writeBytes(bytesToWrite);
        }
    }

    @Override
    public PacketSignal handle(SubChunkPacket packet) {
        // TODO:
        // - Move this into another file
        // - Add V3 support

        List<SubChunkData> subChunks = packet.getSubChunks();

        for (int subChunkIndex=0; subChunkIndex < subChunks.size(); subChunkIndex++) {
            ByteBuf subChunkData = subChunks.get(subChunkIndex).getData();

            if (subChunkData.readableBytes() == 0) {
                continue; // Skip this subchunk if it has no data
            }

            ByteBuf newSubChunkData = Unpooled.directBuffer(subChunkData.capacity()); // do not judge

            /*String finalString = "";
            for (int i = 0; i < subChunkData.capacity(); i ++) {
                byte b = subChunkData.getByte(i);
                finalString += String.format("%02x", b) + ' ';
            }

            System.out.println(finalString);*/

            subChunkData.readerIndex(0);
            newSubChunkData.readerIndex(0);

            // Read subchunk version
            int subChunkVersion = subChunkData.readUnsignedByte();
            newSubChunkData.ensureWritable(1);
            newSubChunkData.writeByte(subChunkVersion);

            if (subChunkVersion == 9 || subChunkVersion == 8) {
                // First byte is "storages count"
                int storagesCount = subChunkData.readUnsignedByte();
                newSubChunkData.ensureWritable(1);
                newSubChunkData.writeByte(storagesCount);

                // Potential junk second byte if v9
                if (subChunkVersion == 9) {
                    newSubChunkData.ensureWritable(1);
                    newSubChunkData.writeByte(subChunkData.readUnsignedByte()); // Skip the "layer height" field?
                }

                for (int y=0; y < storagesCount; y++) {
                    // Read palette
                    int paletteHeader = subChunkData.readUnsignedByte();

                    newSubChunkData.ensureWritable(1);
                    newSubChunkData.writeByte(paletteHeader);

                    int bitsPerBlock = paletteHeader >> 1;
                    boolean isPersistant = (paletteHeader & 0x01) == 0; // This should always be false or bad stuff will happen
                    assert !isPersistant;

                    if (bitsPerBlock != 0) {
                        int blocksPerWord = Integer.SIZE / bitsPerBlock;
                        int wordsCount = (4096 + blocksPerWord - 1) / blocksPerWord;
                        int[] words = new int[wordsCount];

                        // Read words
                        for (int i=0; i < wordsCount; i++) {
                            int word = subChunkData.readIntLE();

                            newSubChunkData.ensureWritable(4);
                            newSubChunkData.writeIntLE(word);

                            words[i] = word;
                        }
                        int paletteSize = readVarInt(subChunkData); // What?
                        writeVarInt(newSubChunkData, paletteSize);

                        // Now read the palette
                        List<Integer> blockPalette = new ArrayList<>();
                        for (int i=0; i < paletteSize; i++) {
                            int blockRID = readVarInt(subChunkData);
                            blockPalette.add(blockRID);

                            int fixedBlockRID = this.proxy.getRIDReplacementsServerToClient().getOrDefault(blockRID, 0);

                            // Get proper blockPalette
                            writeVarInt(newSubChunkData, fixedBlockRID);
                        }
                    }
                }

                // Dump the rest of the packet (block entities)
                // TODO: This code legit sucks
                while (subChunkData.readableBytes() > 0) {
                    newSubChunkData.ensureWritable(1);
                    newSubChunkData.writeByte(subChunkData.readByte());
                }
            } else if (subChunkVersion == 1) {
                System.out.println("ERRRRRR SUBCHNK V1 DETECTED OH NOOOOOOOOOOOOOOOOO");
            } else if (subChunkVersion >= 0 && subChunkVersion <= 7) {
                for (int i=0; i < 4096; i++) { // Read subchunk data
                    short blockRID = subChunkData.readUnsignedByte();

                    int fixedBlockRID = this.proxy.getRIDReplacementsServerToClient().getOrDefault(blockRID, 0);

                    // Get proper blockPalette
                    newSubChunkData.ensureWritable(1);
                    newSubChunkData.writeByte(fixedBlockRID);
                }

                // Dump the rest of the packet (block entities)
                // TODO: This code legit sucks
                while (subChunkData.readableBytes() > 0) {
                    newSubChunkData.ensureWritable(1);
                    newSubChunkData.writeByte(subChunkData.readByte());
                }
            } else {
                System.out.println("Unhandled subchunk version: " + Integer.toString(subChunkVersion));
            }

            subChunks.get(subChunkIndex).setData(newSubChunkData); // Is this needed?
        }

        packet.setSubChunks(subChunks); // Is this needed?

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(UpdateBlockPacket packet) {
        BlockDefinition definition = packet.getDefinition();

        BlockDefinition newBlockDefinition = this.proxy.getClientBlockDefinitions().getDefinition(this.proxy.getRIDReplacementsServerToClient().getOrDefault(definition.getRuntimeId(), 0));
        packet.setDefinition(newBlockDefinition);

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(UpdateBlockSyncedPacket packet) {
        BlockDefinition definition = packet.getDefinition();

        BlockDefinition newBlockDefinition = this.proxy.getClientBlockDefinitions().getDefinition(this.proxy.getRIDReplacementsServerToClient().getOrDefault(definition.getRuntimeId(), 0));
        packet.setDefinition(newBlockDefinition);

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddEntityPacket packet) {
        EntityDataMap entityMetadata = packet.getMetadata();
        
        if (entityMetadata.containsKey(EntityDataTypes.BLOCK) && entityMetadata.get(EntityDataTypes.BLOCK) != null) {
            BlockDefinition oldBlockDefinition = (BlockDefinition) entityMetadata.get(EntityDataTypes.BLOCK);

            BlockDefinition newBlockDefinition = this.proxy.getClientBlockDefinitions().getDefinition(this.proxy.getRIDReplacementsServerToClient().getOrDefault(oldBlockDefinition.getRuntimeId(), 0));
            entityMetadata.put(EntityDataTypes.BLOCK, newBlockDefinition);
        }

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(UpdateSubChunkBlocksPacket packet) {
        packet.getStandardBlocks();

        List<BlockChangeEntry> standardBlocks = packet.getStandardBlocks();
        List<BlockChangeEntry> extraBlocks = packet.getExtraBlocks();

        for (int i=0; i < standardBlocks.size(); i++) {
            BlockChangeEntry blockChangeEntry = standardBlocks.get(i);

            // Create new block definition
            BlockDefinition newBlockDefinition = this.proxy.getClientBlockDefinitions().getDefinition(this.proxy.getRIDReplacementsServerToClient().getOrDefault(blockChangeEntry.getDefinition().getRuntimeId(), 0));

            // Create new change entry
            BlockChangeEntry newBlockChangeEntry = new BlockChangeEntry(blockChangeEntry.getPosition(), newBlockDefinition, blockChangeEntry.getUpdateFlags(), blockChangeEntry.getMessageEntityId(), blockChangeEntry.getMessageType());

            standardBlocks.set(i, newBlockChangeEntry);
        }

        for (int i=0; i < extraBlocks.size(); i++) {
            BlockChangeEntry blockChangeEntry = extraBlocks.get(i);

            // Create new block definition
            BlockDefinition newBlockDefinition = this.proxy.getClientBlockDefinitions().getDefinition(this.proxy.getRIDReplacementsServerToClient().getOrDefault(blockChangeEntry.getDefinition().getRuntimeId(), 0));

            // Create new change entry
            BlockChangeEntry newBlockChangeEntry = new BlockChangeEntry(blockChangeEntry.getPosition(), newBlockDefinition, blockChangeEntry.getUpdateFlags(), blockChangeEntry.getMessageEntityId(), blockChangeEntry.getMessageType());

            extraBlocks.set(i, newBlockChangeEntry);
        }


        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(StartGamePacket packet) {
        List<DataEntry> itemData = new ArrayList<>();
        LinkedHashMap<String, Integer> legacyItems = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> legacyBlocks = new LinkedHashMap<>();

        for (ItemDefinition entry : packet.getItemDefinitions()) {
            if (entry.getRuntimeId() > 255) {
                legacyItems.putIfAbsent(entry.getIdentifier(), entry.getRuntimeId());
            } else {
                String id = entry.getIdentifier();
                if (id.contains(":item.")) {
                    id = id.replace(":item.", ":");
                }
                if (entry.getRuntimeId() > 0) {
                    legacyBlocks.putIfAbsent(id, entry.getRuntimeId());
                } else {
                    legacyBlocks.putIfAbsent(id, 255 - entry.getRuntimeId());
                }
            }

            itemData.add(new DataEntry(entry.getIdentifier(), entry.getRuntimeId()));
            ProxyPass.legacyIdMap.put(entry.getRuntimeId(), entry.getIdentifier());
        }

        SimpleDefinitionRegistry<ItemDefinition> itemDefinitions = SimpleDefinitionRegistry.<ItemDefinition>builder()
                .addAll(packet.getItemDefinitions())
                .add(new SimpleItemDefinition("minecraft:empty", 0, false))
                .build();

        this.session.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
        player.getUpstream().getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);

        // This work maybe?
        // (probably not)
        this.session.getPeer().getCodecHelper().setBlockDefinitions(this.proxy.getClientBlockDefinitions());
        player.getUpstream().getPeer().getCodecHelper().setBlockDefinitions(this.proxy.getServerBlockDefinitions());

        itemData.sort(Comparator.comparing(o -> o.name));

        proxy.saveJson("legacy_block_ids.json", sortMap(legacyBlocks));
        proxy.saveJson("legacy_item_ids.json", sortMap(legacyItems));
        proxy.saveJson("runtime_item_states.json", itemData);

        packet.setBlockRegistryChecksum(-7810975316988886033L);
        packet.setClientSideGenerationEnabled(false); // Broken in ProxyPass
        packet.setServerEngine("1.20.31");

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CraftingDataPacket packet) {
        RecipeUtils.writeRecipes(packet, this.proxy);
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        this.session.disconnect();
        // Let the client see the reason too.
        return PacketSignal.UNHANDLED;
    }

    private void dumpCreativeItems(ItemData[] contents) {
        List<CreativeItemEntry> entries = new ArrayList<>();
        for (ItemData data : contents) {
            ItemDefinition entry = data.getDefinition();
            String id = entry.getIdentifier();
            Integer damage = data.getDamage() == 0 ? null : (int) data.getDamage();

            String blockTag = null;
            Integer blockRuntimeId = null;
            if (data.getBlockDefinition() instanceof NbtBlockDefinitionRegistry.NbtBlockDefinition definition) {
                blockTag = encodeNbtToString(definition.tag());
            } else if (data.getBlockDefinition() != null) {
                blockRuntimeId = data.getBlockDefinition().getRuntimeId();
            }

            NbtMap tag = data.getTag();
            String tagData = null;
            if (tag != null) {
                tagData = encodeNbtToString(tag);
            }
            entries.add(new CreativeItemEntry(id, damage, blockRuntimeId, blockTag, tagData));
        }

        CreativeItems items = new CreativeItems(entries);

        proxy.saveJson("creative_items.json", items);
    }

    @Override
    public PacketSignal handle(CreativeContentPacket packet) {
        try {
            dumpCreativeItems(packet.getContents());
        } catch (Exception e) {
            log.error("Failed to dump creative contents", e);
        }
        return PacketSignal.UNHANDLED;
    }

    // Handles creative items for versions prior to 1.16
    @Override
    public PacketSignal handle(InventoryContentPacket packet) {
        if (packet.getContainerId() == ContainerId.CREATIVE) {
            dumpCreativeItems(packet.getContents().toArray(new ItemData[0]));
            return PacketSignal.UNHANDLED;
        }

        // TODO
        /*
        List<ItemData> inventoryContents = packet.getContents();

        for (int i=0; i < inventoryContents.size(); i++) {
            ItemData item = inventoryContents.get(i);

            BlockDefinition fixedBlockDefinition = this.proxy.getClientBlockDefinitions().getDefinition(this.proxy.getRIDReplacementsServerToClient().getOrDefault(item.getBlockDefinition().getRuntimeId(), 0));
        }*/

        return PacketSignal.UNHANDLED;
    }

    private static Map<String, Integer> sortMap(Map<String, Integer> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new));
    }

    private static String encodeNbtToString(NbtMap tag) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             NBTOutputStream stream = new NBTOutputStream(new LittleEndianDataOutputStream(byteArrayOutputStream))) {
            stream.writeTag(tag);
            return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class CreativeItemEntry {
        private final String id;
        private final Integer damage;
        private final Integer blockRuntimeId;
        @JsonProperty("block_state_b64")
        private final String blockTag;
        @JsonProperty("nbt_b64")
        private final String nbt;
    }

    @Value
    private static class CreativeItems {
        private final List<CreativeItemEntry> items;
    }

    @Value
    private static class RuntimeEntry {
        private static final Comparator<RuntimeEntry> COMPARATOR = Comparator.comparingInt(RuntimeEntry::getId)
                .thenComparingInt(RuntimeEntry::getData);

        private final String name;
        private final int id;
        private final int data;
    }

    @Value
    private static class DataEntry {
        private final String name;
        private final int id;
    }
}
