package studio.ERM.handlers;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Random;

public class StructureLoader {

    // --- Core Spawning Method ---
    public static void spawnStructure(World world, BlockPos pos, File file, String format, WorldBackupManager.StructureBackup backup) {
        if (!file.exists()) {
            EpochRunnerMod.logger.warn("Structure file not found: " + file.getAbsolutePath());
            return;
        }

        if (format.equalsIgnoreCase("NBT")) {
            spawnNbtStructure(world, pos, file, backup);
        } else if (format.equalsIgnoreCase("SCHEMATIC")) {
            spawnSchematicStructure(world, pos, file, backup);
        } else {
            EpochRunnerMod.logger.error("Unknown structure format: " + format);
        }
    }

    // --- Vanilla NBT Structure Spawner ---
    private static void spawnNbtStructure(World world, BlockPos pos, File file, WorldBackupManager.StructureBackup backup) {
        try (InputStream is = new FileInputStream(file)) {
            NBTTagCompound nbt = CompressedStreamTools.readCompressed(is);
            Template template = new Template();
            template.read(nbt);

            BlockPos size = template.getSize();
            EpochRunnerMod.logger.info("Spawning NBT structure: " + file.getName() + " Size: " + size.getX() + "x" + size.getY() + "x" + size.getZ());

            // 1. BACKUP PHASE
            // Determine the boundary box based on the template's size
            if (backup != null) {
                for (BlockPos p : BlockPos.getAllInBox(pos, pos.add(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
                    backup.originalBlocks.put(p.toImmutable(), world.getBlockState(p));
                    TileEntity te = world.getTileEntity(p);
                    if (te != null) {
                        backup.originalTiles.put(p.toImmutable(), te.writeToNBT(new NBTTagCompound()));
                    }
                }
            }

            // 2. PLACEMENT PHASE
            PlacementSettings settings = new PlacementSettings();

            // Randomly rotate the structure for variety
            settings.setRotation(Rotation.values()[new Random().nextInt(Rotation.values().length)]);

            // Setting integrity to 1.0 ensures all blocks are placed (NUKE-proof)
            settings.setIntegrity(1.0F);

            // Flag 2 = Send to client, but DO NOT update neighbors (prevents lag/water flow updates)
            template.addBlocksToWorld(world, pos, settings, 2);

        } catch (Exception e) {
            EpochRunnerMod.logger.error("Failed to load NBT structure: " + file.getName(), e);
        }
    }

    // --- WorldEdit Schematic Spawner (Original Logic, kept for compatibility) ---
    private static void spawnSchematicStructure(World world, BlockPos pos, File file, WorldBackupManager.StructureBackup backup) {
        try (InputStream is = new FileInputStream(file)) {
            NBTTagCompound nbt = CompressedStreamTools.readCompressed(is);

            short width = nbt.getShort("Width");   // X axis
            short height = nbt.getShort("Height"); // Y axis
            short length = nbt.getShort("Length"); // Z axis

            byte[] blocks = nbt.getByteArray("Blocks");
            byte[] data = nbt.getByteArray("Data");
            byte[] addBlocks = nbt.hasKey("AddBlocks") ? nbt.getByteArray("AddBlocks") : new byte[0];

            EpochRunnerMod.logger.info("Spawning schematic: " + file.getName() + " Size: " + width + "x" + height + "x" + length);

            // 1. BACKUP PHASE
            if (backup != null) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < length; z++) {
                        for (int x = 0; x < width; x++) {
                            BlockPos p = pos.add(x, y, z);
                            backup.originalBlocks.put(p.toImmutable(), world.getBlockState(p));

                            TileEntity te = world.getTileEntity(p);
                            if (te != null) {
                                backup.originalTiles.put(p.toImmutable(), te.writeToNBT(new NBTTagCompound()));
                            }
                        }
                    }
                }
            }

            // 2. PLACEMENT PHASE
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        int index = (y * length + z) * width + x;

                        // Calculate Block ID (Support for 4096 IDs using "AddBlocks" layer)
                        int blockID = (blocks[index] & 0xFF);
                        if (addBlocks.length > 0 && index < addBlocks.length * 2) {
                            if ((index & 1) == 0) {
                                blockID |= (((addBlocks[index >> 1] & 0x0F) << 8));
                            } else {
                                blockID |= (((addBlocks[index >> 1] & 0xF0) << 4));
                            }
                        }

                        int meta = (data[index] & 0xFF);
                        Block block = Block.getBlockById(blockID);

                        BlockPos placePos = pos.add(x, y, z);
                        IBlockState state = block.getStateFromMeta(meta);

                        // Flag 2 = Send to client, but DO NOT update neighbors
                        world.setBlockState(placePos, state, 2);
                    }
                }
            }

            // 3. TILE ENTITIES
            if (nbt.hasKey("TileEntities")) {
                NBTTagList tileEntities = nbt.getTagList("TileEntities", 10);
                for (int i = 0; i < tileEntities.tagCount(); i++) {
                    NBTTagCompound teTag = tileEntities.getCompoundTagAt(i);

                    int x = teTag.getInteger("x");
                    int y = teTag.getInteger("y");
                    int z = teTag.getInteger("z");
                    BlockPos tePos = pos.add(x, y, z);

                    TileEntity te = world.getTileEntity(tePos);
                    if (te != null) {
                        teTag.setInteger("x", tePos.getX());
                        teTag.setInteger("y", tePos.getY());
                        teTag.setInteger("z", tePos.getZ());
                        te.readFromNBT(teTag);
                    }
                }
            }

        } catch (Exception e) {
            EpochRunnerMod.logger.error("Failed to load schematic: " + file.getName(), e);
        }
    }
}