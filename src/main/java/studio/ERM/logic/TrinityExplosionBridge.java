package studio.ERM.logic;

import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class TrinityExplosionBridge {

    public static void forceDetonation(World world, BlockPos pos, String bombId) {
        if (world.isRemote) return;

        Block bombBlock = Block.REGISTRY.getObject(new ResourceLocation(bombId));
        if (bombBlock == null) return;

        try {
            // Locates the Trinity NuclearCore class via reflection to bypass build errors
            Class<?> coreClass = Class.forName("trinity.blocks.NuclearCore");

            if (coreClass.isInstance(bombBlock)) {
                // public static void AtomicBomb(World world, BlockPos pos, int blastRadius, boolean salted)
                Method atomicBombMethod = coreClass.getMethod("AtomicBomb", World.class, BlockPos.class, int.class, boolean.class);

                Field radiusField = coreClass.getField("blastRadius");
                Field saltedField = coreClass.getField("salted");

                int radius = radiusField.getInt(bombBlock);
                boolean isSalted = saltedField.getBoolean(bombBlock);

                // This triggers mushrooms, radiation, and blast entity directly
                atomicBombMethod.invoke(null, world, pos, radius, isSalted);
                EpochRunnerMod.logger.info("TRINITY BRIDGE: Reflection trigger successful for " + bombId);
            }
        } catch (Exception e) {
            EpochRunnerMod.logger.error("TRINITY BRIDGE: Detonation failed: " + e.getMessage());
        }
    }
}