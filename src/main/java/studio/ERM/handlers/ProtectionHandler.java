package studio.ERM.handlers;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.world.WarWorldData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Global block protection and restoration system.
 * Updated to support War Engine synergy and District exclusion.
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class ProtectionHandler extends WorldSavedData {
    private static final String DATA_NAME = "ERM_ProtectionData";

    private final Map<BlockPos, IBlockState> protectedStates = new HashMap<>();
    private final Map<BlockPos, NBTTagCompound> protectedNBT = new HashMap<>();

    public ProtectionHandler() {
        super(DATA_NAME);
    }

    public ProtectionHandler(String name) {
        super(name);
    }

    public static ProtectionHandler get(World world) {
        ProtectionHandler instance = (ProtectionHandler) world.getMapStorage().getOrLoadData(ProtectionHandler.class, DATA_NAME);
        if (instance == null) {
            instance = new ProtectionHandler();
            world.getMapStorage().setData(DATA_NAME, instance);
        }
        return instance;
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getWorld().isRemote) return;
        ProtectionHandler instance = get(event.getWorld());

        // Remove protected blocks from explosion damage list
        event.getAffectedBlocks().removeIf(pos -> instance.protectedStates.containsKey(pos.toImmutable()));
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) return;

        ProtectionHandler instance = get(event.world);
        instance.checkAndRestore(event.world);
    }

    /**
     * Core loop to ensure protected blocks remain in their saved state.
     */
    public void checkAndRestore(World world) {
        WarWorldData warData = WarWorldData.get(world);
        Iterator<Map.Entry<BlockPos, IBlockState>> iterator = protectedStates.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, IBlockState> entry = iterator.next();
            BlockPos pos = entry.getKey();
            IBlockState savedState = entry.getValue();

            // --- 0. WAR ENGINE CHECK: SCAFFOLDS & REPAIRS ---
            // If the War Engine has placed a scaffold or repair order here,
            // the ProtectionHandler MUST stand down to allow the Builder to work.
            if (warData.getRepairMap().containsKey(pos) || world.getBlockState(pos).getBlock() == EpochRunnerMod.scaffold) {
                continue;
            }

            // --- 1. CRASH FIX: RE-CAPTURE DATA ---
            if (savedState == null) {
                IBlockState currentState = world.getBlockState(pos);
                if (!world.isAirBlock(pos)) {
                    entry.setValue(currentState);
                    TileEntity te = world.getTileEntity(pos);
                    if (te != null) {
                        protectedNBT.put(pos, te.writeToNBT(new NBTTagCompound()));
                    }
                } else {
                    iterator.remove();
                    protectedNBT.remove(pos);
                    EpochRunnerMod.logger.warn("ERM PROTECTION: Removed invalid protection at " + pos);
                }
                continue;
            }

            // --- 2. "BLACK HOLE" CLEANUP ---
            AxisAlignedBB searchBox = new AxisAlignedBB(pos).grow(10.0);
            List<EntityItem> drops = world.getEntitiesWithinAABB(EntityItem.class, searchBox);
            if (!drops.isEmpty()) {
                for (EntityItem drop : drops) { drop.setDead(); }
            }

            IBlockState currentState = world.getBlockState(pos);

            // --- 3. PASSIVE NBT SYNC ---
            if (currentState.getBlock() == savedState.getBlock()) {
                if (world.getTotalWorldTime() % 100 == 0) {
                    TileEntity te = world.getTileEntity(pos);
                    if (te != null) {
                        protectedNBT.put(pos, te.writeToNBT(new NBTTagCompound()));
                        markDirty();
                    }
                }
                continue;
            }

            // --- 4. RESTORATION ---
            if (world.isAirBlock(pos) || currentState.getBlock() != savedState.getBlock()) {
                // Beds: place with flag 2 (client-only, NO neighbour update) and don't pre-air the
                // slot, so restoring one half can't trigger BlockBed validation that pops the other
                // half. Both halves are protected, so each is restored independently into a whole bed.
                boolean isBed = savedState.getBlock() instanceof BlockBed;
                if (!isBed && world.getBlockState(pos).getBlock() != Blocks.AIR) { world.setBlockToAir(pos); }
                world.setBlockState(pos, savedState, isBed ? 2 : 3);

                if (protectedNBT.containsKey(pos)) {
                    TileEntity te = world.getTileEntity(pos);
                    if (te != null) {
                        NBTTagCompound nbt = protectedNBT.get(pos);
                        nbt.setInteger("x", pos.getX());
                        nbt.setInteger("y", pos.getY());
                        nbt.setInteger("z", pos.getZ());
                        te.readFromNBT(nbt);
                        te.markDirty();
                        world.notifyBlockUpdate(pos, savedState, savedState, 3);
                    }
                }
                EpochRunnerMod.logger.info("ERM PROTECTION: Restored block at " + pos);
            }
        }
    }

    /** True if this exact block is under protector-stick protection (so antigrief must not touch it). */
    public static boolean isProtected(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        try {
            return get(world).protectedStates.containsKey(pos.toImmutable());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void toggleProtection(EntityPlayer player, BlockPos pos) {
        ProtectionHandler instance = get(player.world);
        BlockPos immutablePos = pos.toImmutable();
        // Multiblock (bed): the head + foot are one object. Protecting/restoring only the clicked half
        // leaves the other half unprotected and BlockBed's own neighbour validation then pops the
        // restored half right back off -> the "buggy half bed". Always handle BOTH halves together.
        BlockPos other = bedOtherHalf(player.world, immutablePos);

        if (instance.protectedStates.containsKey(immutablePos)) {
            instance.unprotectBlock(immutablePos);
            if (other != null) instance.unprotectBlock(other.toImmutable());
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Protection Disabled."));
        } else {
            instance.protectBlock(player.world, immutablePos);
            if (other != null) instance.protectBlock(player.world, other.toImmutable());
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Protection Enabled."));
        }
        instance.markDirty();
    }

    private void protectBlock(World world, BlockPos pos) {
        BlockPos p = pos.toImmutable();
        protectedStates.put(p, world.getBlockState(p));
        TileEntity te = world.getTileEntity(p);
        if (te != null) protectedNBT.put(p, te.writeToNBT(new NBTTagCompound()));
    }

    private void unprotectBlock(BlockPos pos) {
        BlockPos p = pos.toImmutable();
        protectedStates.remove(p);
        protectedNBT.remove(p);
    }

    /** The linked half of a bed (head&lt;-&gt;foot), or null if {@code pos} is not a bed. */
    private static BlockPos bedOtherHalf(World world, BlockPos pos) {
        try {
            IBlockState s = world.getBlockState(pos);
            if (!(s.getBlock() instanceof BlockBed)) return null;
            EnumFacing facing = s.getValue(BlockBed.FACING);
            boolean isHead = s.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD;
            return isHead ? pos.offset(facing.getOpposite()) : pos.offset(facing);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        NBTTagList list = nbt.getTagList("ProtectedBlocks", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"));
            IBlockState state = Block.getStateById(tag.getInteger("state"));
            protectedStates.put(pos, state);
            if (tag.hasKey("nbt")) {
                protectedNBT.put(pos, tag.getCompoundTag("nbt"));
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<BlockPos, IBlockState> entry : protectedStates.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", entry.getKey().getX());
            tag.setInteger("y", entry.getKey().getY());
            tag.setInteger("z", entry.getKey().getZ());
            tag.setInteger("state", Block.getStateId(entry.getValue()));
            if (protectedNBT.containsKey(entry.getKey())) {
                tag.setTag("nbt", protectedNBT.get(entry.getKey()));
            }
            list.appendTag(tag);
        }
        nbt.setTag("ProtectedBlocks", list);
        return nbt;
    }
}