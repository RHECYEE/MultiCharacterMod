package studio.ERM.war.items;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.world.WarWorldData;
import studio.ERM.war.air.AirStrikeController;

import javax.annotation.Nullable;
import java.util.List;

/**
 * AIR TARGET DESIGNATOR v8.1 (Fixed)
 * Throwable item that calls in friendly air strikes.
 * Logic fixed to handle Client/Server data synchronization.
 */
public class ItemAirTargetDesignator extends Item {

    // Aircraft types MUST be real Flan's content-pack ShortNames, otherwise the client cannot
    // resolve a PlaneType and the ghost aircraft renders as an invisible/placeholder box
    // (see RenderGhostAircraft.resolvePlaneType). The previous list used made-up names
    // (p51/b17/f16/f4phantom/b2/sr71/ac130) that exist in no installed pack -- that is why
    // most designator strikes "did damage but showed no plane". These are verified against the
    // installed WW2 + Modern Warfare packs.
    public enum StrikePackage {
        RECON_FLYOVER(1, "Recon Flyover", 5, 0, "BF109"),
        LIGHT_STRAFE(2, "Light Strafe", 10, 10, "Mustang"),
        BOMBING_RUN(3, "Bombing Run", 20, 20, "Lancaster"),
        HEAVY_STRAFE(4, "Heavy Strafe", 25, 25, "A10"),
        PRECISION_STRIKE(5, "Precision Strike", 35, 30, "tornado"),
        NAPALM_RUN(6, "Napalm Run", 40, 35, "SU25"),
        CLUSTER_BOMB(7, "Cluster Bomb", 50, 40, "B52"),
        CARPET_BOMBING(8, "Carpet Bombing", 65, 50, "B52"),
        TACTICAL_NUKE(9, "Tactical Strike", 80, 60, "f22"),
        STRATEGIC_STRIKE(10, "Strategic Strike", 100, 75, "B52");

        public final int level;
        public final String name;
        public final int cpCost;
        public final int minAirDefense;
        public final String aircraftType;

        StrikePackage(int level, String name, int cpCost, int minAirDefense, String aircraftType) {
            this.level = level;
            this.name = name;
            this.cpCost = cpCost;
            this.minAirDefense = minAirDefense;
            this.aircraftType = aircraftType;
        }

        public static StrikePackage fromLevel(int level) {
            for (StrikePackage sp : values()) {
                if (sp.level == level) return sp;
            }
            return RECON_FLYOVER;
        }
    }

    public ItemAirTargetDesignator() {
        setMaxStackSize(16);
        setHasSubtypes(true);
        setMaxDamage(0);
        setCreativeTab(CreativeTabs.COMBAT);
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (this.isInCreativeTab(tab)) {
            for (int i = 1; i <= 10; i++) {
                ItemStack stack = new ItemStack(this);
                setStrikeLevel(stack, i);
                items.add(stack);
            }
        }
    }

    @Override
    public String getTranslationKey(ItemStack stack) {
        int level = getStrikeLevel(stack);
        return super.getTranslationKey() + "_level" + level;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        int level = getStrikeLevel(stack);
        StrikePackage pkg = StrikePackage.fromLevel(level);

        // If we are on the client, just return SUCCESS to let the server handle it
        if (world.isRemote) {
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        }

        // SERVER-SIDE LOGIC START
        WarWorldData data = WarWorldData.get(world);
        // Use player UUID for faction stats, not generic "PLAYER"
        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
        boolean bypassRequirements = player.isCreative();

        // Get air defense from player NBT (AirDefenseHandler stores it there)
        int playerAirDefense = studio.ERM.war.AirDefenseHandler.getScore(player);

        if (!bypassRequirements) {
            if (playerAirDefense < pkg.minAirDefense) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "Air defense too low! Need " + pkg.minAirDefense + "%, have " + playerAirDefense + "%"));
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
            if (stats.commandPoints < pkg.cpCost) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "Not enough Command Points! Need " + pkg.cpCost + ", have " + stats.commandPoints));
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
        }

        // EXECUTION
        world.playSound(null, player.posX, player.posY, player.posZ,
                SoundEvent.REGISTRY.getObject(new ResourceLocation("entity.snowball.throw")),
                SoundCategory.NEUTRAL, 0.5F, 0.4F / (itemRand.nextFloat() * 0.4F + 0.8F));

        EntityAirDesignatorProjectile projectile = new EntityAirDesignatorProjectile(world, player, level);
        projectile.shoot(player, player.rotationPitch, player.rotationYaw, 0.0F, 1.5F, 1.0F);
        world.spawnEntity(projectile);

        stats.commandPoints -= pkg.cpCost;
        data.markDirty();

        player.sendMessage(new TextComponentString(TextFormatting.GREEN + "✈ " + pkg.name + " inbound! (-" + pkg.cpCost + " CP)"));

        if (!player.isCreative()) {
            stack.shrink(1);
        }

        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int level = getStrikeLevel(stack);
        StrikePackage pkg = StrikePackage.fromLevel(level);

        tooltip.add(TextFormatting.GOLD + "Strike Package: " + pkg.name);
        tooltip.add(TextFormatting.GRAY + "War Level: " + level);
        tooltip.add("");
        tooltip.add(TextFormatting.YELLOW + "CP Cost: " + pkg.cpCost);
        tooltip.add(TextFormatting.AQUA + "Min Air Defense: " + pkg.minAirDefense + "%");
        tooltip.add(TextFormatting.WHITE + "Aircraft: " + pkg.aircraftType);
        tooltip.add("");
        tooltip.add(TextFormatting.DARK_GRAY + "Right-click to throw");
        tooltip.add(TextFormatting.DARK_GRAY + "Impact point = strike target");
    }

    public static void setStrikeLevel(ItemStack stack, int level) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger("strikeLevel", Math.max(1, Math.min(10, level)));
    }

    public static int getStrikeLevel(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && tag.hasKey("strikeLevel")) {
            return tag.getInteger("strikeLevel");
        }
        return 1;
    }

    // --- PROJECTILE ENTITY ---

    public static class EntityAirDesignatorProjectile extends EntitySnowball {
        private int strikeLevel = 1;

        public EntityAirDesignatorProjectile(World worldIn) {
            super(worldIn);
        }

        public EntityAirDesignatorProjectile(World worldIn, EntityLivingBase thrower, int level) {
            super(worldIn, thrower);
            this.strikeLevel = level;
        }

        @Override
        protected void onImpact(RayTraceResult result) {
            if (!world.isRemote && result.typeOfHit != RayTraceResult.Type.MISS) {
                BlockPos target;
                if (result.typeOfHit == RayTraceResult.Type.BLOCK) {
                    target = result.getBlockPos().up();
                } else {
                    target = new BlockPos(posX, posY, posZ);
                }
                spawnSmokeGrenadeEffect(target);
                callAirStrike(target);
            }
            this.setDead();
        }

        private void spawnSmokeGrenadeEffect(BlockPos target) {
            // Spawn standard red/smoke particles for visual confirmation
            EntitySmokeMarker marker = new EntitySmokeMarker(world, target);
            world.spawnEntity(marker);
        }

        private void callAirStrike(BlockPos target) {
            StrikePackage pkg = StrikePackage.fromLevel(strikeLevel);
            EpochRunnerMod.logger.info("[AIR-DESIGNATOR] Strike called at " + target +
                    " (package: " + pkg.name + ", aircraft: " + pkg.aircraftType + ")");

            // This triggers the logic in AirStrikeController
            AirStrikeController.launchFriendlyStrike(world, target, pkg.aircraftType, strikeLevel);
        }

        @Override
        public void writeEntityToNBT(NBTTagCompound compound) {
            super.writeEntityToNBT(compound);
            compound.setInteger("strikeLevel", strikeLevel);
        }

        @Override
        public void readEntityFromNBT(NBTTagCompound compound) {
            super.readEntityFromNBT(compound);
            strikeLevel = compound.getInteger("strikeLevel");
        }
    }

    // --- VISUAL SMOKE MARKER ---

    public static class EntitySmokeMarker extends net.minecraft.entity.Entity {
        private int ticksAlive = 0;
        private static final int LIFETIME_TICKS = 160; // Slightly longer for aircraft travel time
        private BlockPos markerPos;

        public EntitySmokeMarker(World world) {
            super(world);
            this.setSize(0.1F, 0.1F);
            this.noClip = true;
            this.setInvisible(true);
        }

        public EntitySmokeMarker(World world, BlockPos pos) {
            this(world);
            this.markerPos = pos;
            this.setPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }

        @Override
        protected void entityInit() {}

        @Override
        public void onUpdate() {
            super.onUpdate();
            ticksAlive++;

            if (ticksAlive > LIFETIME_TICKS) {
                this.setDead();
                return;
            }

            // Client-side particle spawning
            if (world.isRemote && ticksAlive % 2 == 0 && markerPos != null) {
                for (int i = 0; i < 3; i++) {
                    world.spawnParticle(net.minecraft.util.EnumParticleTypes.SMOKE_LARGE,
                            posX + (rand.nextDouble() - 0.5) * 1.5,
                            posY + rand.nextDouble() * 3,
                            posZ + (rand.nextDouble() - 0.5) * 1.5,
                            (rand.nextDouble() - 0.5) * 0.02, 0.08, (rand.nextDouble() - 0.5) * 0.02);
                }

                if (ticksAlive % 4 == 0) {
                    world.spawnParticle(net.minecraft.util.EnumParticleTypes.REDSTONE,
                            posX + (rand.nextDouble() - 0.5),
                            posY + 1.5 + rand.nextDouble() * 2,
                            posZ + (rand.nextDouble() - 0.5),
                            1.0, 0.0, 0.0);
                }
            }
        }

        @Override
        protected void readEntityFromNBT(NBTTagCompound compound) {
            ticksAlive = compound.getInteger("ticksAlive");
        }

        @Override
        protected void writeEntityToNBT(NBTTagCompound compound) {
            compound.setInteger("ticksAlive", ticksAlive);
        }
    }
}