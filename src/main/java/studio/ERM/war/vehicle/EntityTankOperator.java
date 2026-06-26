package studio.ERM.war.vehicle;

import com.flansmod.common.FlansMod;
import com.flansmod.common.RotatedAxes;
import com.flansmod.common.driveables.DriveableData;
import com.flansmod.common.driveables.DriveableType;
import com.flansmod.common.driveables.EntityDriveable;
import com.flansmod.common.driveables.EntitySeat;
import com.flansmod.common.driveables.EntityVehicle;
import com.flansmod.common.guns.ItemShootable;
import com.flansmod.common.network.PacketVehicleControl;
import com.flansmod.common.parts.EnumPartCategory;
import com.flansmod.common.parts.ItemPart;
import com.flansmod.common.parts.PartType;
import com.flansmod.common.vector.Vector3f;
import com.google.common.base.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Friendly Flan vehicle operator that can mount + drive vehicles like your pilot AI, but friendly/claimable.
 *
 * Key behaviors:
 * - Mounts nearest Flan EntityDriveable by mounting an EMPTY seat (prefers seat[0]).
 * - If mounted vehicle is EntityVehicle, sets tank.throttle and tank.wheelsYaw to drive toward a goal.
 * - Aims seat toward target and fires using EntityDriveable#shoot() (real Flan path).
 * - Resupplies ammo (DriveableData.ammo[]) and fuel (DriveableData inventory slots) from nearby inventories.
 * - Best-effort AW2 faction friendliness via reflection (safe if AW2 isn't present).
 *
 * Compatibility:
 * - Your mappings expect boolean processInteract(EntityPlayer, EnumHand).
 * - Avoid fork-specific fields like throttleLeft/throttleRight and incorrect setPositionRotationAndMotion signatures.
 */
public class EntityTankOperator extends EntitySkeleton {

    private static final int MOUNT_SCAN_COOLDOWN_TICKS = 40;
    private static final int RESUPPLY_INTERVAL_TICKS = 20;
    private static final int RESUPPLY_RADIUS_BLOCKS = 6;

    private static final int DRIVE_GOAL_MIN_DIST = 4;
    private static final double DRIVE_ENGAGE_DIST_SQ = 40.0D * 40.0D; // if target farther than this, drive closer
    private static final int FIRE_COOLDOWN_MIN_TICKS = 2;

    private static final String NBT_OWNER_UUID = "MCMOwnerUUID";
    private static final String NBT_COMMAND_MODE = "MCMCommandMode";
    private static final String NBT_AW2_FACTION = "MCMAW2Faction";

    // Change if your AW2 friendly faction id differs.
    private static final String DEFAULT_AW2_FACTION = "empire";

    private static Field seatsField;

    private int mountCooldown = 0;
    private int resupplyCooldown = 0;
    private int fireCooldown = 0;

    @Nullable
    private UUID ownerUUID = null;

    private CommandMode commandMode = CommandMode.FOLLOW;

    private String aw2Faction = DEFAULT_AW2_FACTION;

    public enum CommandMode {
        FOLLOW,
        STAY
    }

    public EntityTankOperator(World worldIn) {
        super(worldIn);

        this.tasks.taskEntries.clear();
        this.targetTasks.taskEntries.clear();

        // Default: attack hostile mobs, but never friendly AW2 units (best-effort)
        this.targetTasks.addTask(1, new EntityAINearestAttackableTarget<>(
                this,
                EntityMob.class,
                10,
                true,
                false,
                new Predicate<EntityMob>() {
                    @Override
                    public boolean apply(@Nullable EntityMob target) {
                        if (target == null || target.isDead) return false;
                        return !isFriendlyAw2Unit(target);
                    }
                }
        ));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(40.0D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(96.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.25D);
    }

    @Override
    protected boolean canDespawn() {
        return false;
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        if (compound == null) return;

        if (compound.hasKey(NBT_OWNER_UUID)) {
            try {
                this.ownerUUID = UUID.fromString(compound.getString(NBT_OWNER_UUID));
            } catch (Exception ignored) {
                this.ownerUUID = null;
            }
        }

        if (compound.hasKey(NBT_COMMAND_MODE)) {
            try {
                this.commandMode = CommandMode.valueOf(compound.getString(NBT_COMMAND_MODE));
            } catch (Exception ignored) {
                this.commandMode = CommandMode.FOLLOW;
            }
        }

        if (compound.hasKey(NBT_AW2_FACTION)) {
            String f = compound.getString(NBT_AW2_FACTION);
            if (f != null && !f.trim().isEmpty()) {
                this.aw2Faction = f.trim();
            }
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        if (compound == null) return;

        if (this.ownerUUID != null) {
            compound.setString(NBT_OWNER_UUID, this.ownerUUID.toString());
        }
        if (this.commandMode != null) {
            compound.setString(NBT_COMMAND_MODE, this.commandMode.name());
        }
        if (this.aw2Faction != null && !this.aw2Faction.trim().isEmpty()) {
            compound.setString(NBT_AW2_FACTION, this.aw2Faction.trim());
        }
    }

    /**
     * Your mappings: boolean processInteract(EntityPlayer, EnumHand)
     */
    @Override
    protected boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (player == null || hand == null) {
            return super.processInteract(player, hand);
        }

        if (this.world.isRemote) {
            return true;
        }

        // Sneak-right-click: claim + toggle follow/stay
        if (player.isSneaking()) {
            if (this.ownerUUID == null || !this.ownerUUID.equals(player.getUniqueID())) {
                this.ownerUUID = player.getUniqueID();
                this.commandMode = CommandMode.FOLLOW;
                player.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + "Tank Operator: " + TextFormatting.YELLOW + "Assigned to you (FOLLOW)."
                ));
                return true;
            }

            this.commandMode = (this.commandMode == CommandMode.FOLLOW) ? CommandMode.STAY : CommandMode.FOLLOW;
            player.sendMessage(new TextComponentString(
                    TextFormatting.GREEN + "Tank Operator: " + TextFormatting.YELLOW + "Mode set to " + this.commandMode.name() + "."
            ));
            return true;
        }

        return super.processInteract(player, hand);
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (this.world.isRemote) return;

        // Seed and sync faction NBT lazily (avoids overriding fork-different init hooks)
        NBTTagCompound ed = this.getEntityData();
        if (!ed.hasKey(NBT_AW2_FACTION)) {
            ed.setString(NBT_AW2_FACTION, DEFAULT_AW2_FACTION);
        }
        String f = ed.getString(NBT_AW2_FACTION);
        if (f != null && !f.trim().isEmpty()) {
            this.aw2Faction = f.trim();
        } else {
            this.aw2Faction = DEFAULT_AW2_FACTION;
            ed.setString(NBT_AW2_FACTION, DEFAULT_AW2_FACTION);
        }

        ensureAw2FactionApplied();

        EntityDriveable driveable = getMountedVehicle();
        EntitySeat seat = getMountedSeat();

        // Not mounted: follow owner on foot and try mounting nearest vehicle seat
        if (driveable == null || seat == null) {
            handleOnFootCommandMovement();

            if (mountCooldown-- <= 0) {
                findAndMountNearbyDriveableSeat();
                mountCooldown = MOUNT_SCAN_COOLDOWN_TICKS;
            }
            return;
        }

        // Resupply
        if (resupplyCooldown-- <= 0) {
            resupplyFromNearbyChests(driveable);
            resupplyCooldown = RESUPPLY_INTERVAL_TICKS;
        }

        // Driving: friendly follow / stay and engagement positioning
        applyFriendlyDriving(driveable);

        // Targeting + firing
        EntityLivingBase target = this.getAttackTarget();
        if (target == null || target.isDead || isFriendlyAw2Unit(target)) {
            if (target != null && isFriendlyAw2Unit(target)) {
                this.setAttackTarget(null);
            }
            return;
        }

        // If target is far away, drive closer while still aiming/firing
        if (this.getDistanceSq(target) > DRIVE_ENGAGE_DIST_SQ) {
            driveTowardEntity(driveable, target, 18.0D);
        }

        aimSeatAtTarget(seat, target);

        if (fireCooldown-- <= 0) {
            boolean fired = tryFireVehicleWeapons(driveable);
            fireCooldown = fired ? getFireCooldownForVehicle(driveable) : 10;
        }

        sendVehicleControlPacket(driveable);
    }

    private void handleOnFootCommandMovement() {
        if (this.commandMode != CommandMode.FOLLOW || this.ownerUUID == null) {
            this.getNavigator().clearPath();
            return;
        }

        EntityPlayer owner = this.world.getPlayerEntityByUUID(this.ownerUUID);
        if (owner == null || owner.isDead) {
            this.getNavigator().clearPath();
            return;
        }

        double dist = this.getDistance(owner);
        if (dist > 4.0D) {
            this.getNavigator().tryMoveToEntityLiving(owner, 1.05D);
        } else {
            this.getNavigator().clearPath();
        }
    }

    private void applyFriendlyDriving(EntityDriveable driveable) {
        if (!(driveable instanceof EntityVehicle)) return;

        EntityVehicle tank = (EntityVehicle) driveable;

        // STAY means: stop the vehicle
        if (this.commandMode == CommandMode.STAY) {
            stopVehicle(tank);
            return;
        }

        // FOLLOW: drive toward owner if assigned
        if (this.commandMode == CommandMode.FOLLOW && this.ownerUUID != null) {
            EntityPlayer owner = this.world.getPlayerEntityByUUID(this.ownerUUID);
            if (owner != null && !owner.isDead) {
                double distSq = tank.getDistanceSq(owner);
                if (distSq > (double) (DRIVE_GOAL_MIN_DIST * DRIVE_GOAL_MIN_DIST)) {
                    driveTowardEntity(driveable, owner, 3.0D);
                } else {
                    stopVehicle(tank);
                }
                return;
            }
        }

        // If no owner / no follow target, do not force motion
        stopVehicle(tank);
    }

    private void stopVehicle(EntityVehicle tank) {
        if (tank == null) return;
        tank.throttle = 0.0F;
        tank.wheelsYaw = 0.0F;
    }

    /**
     * Drive a vehicle toward an entity while maintaining steering.
     * stopRadius controls how close we get before stopping.
     */
    private void driveTowardEntity(EntityDriveable driveable, Entity target, double stopRadius) {
        if (!(driveable instanceof EntityVehicle)) return;

        EntityVehicle tank = (EntityVehicle) driveable;
        if (tank.axes == null) return;
        if (target == null || target.isDead) return;

        double stopRadiusSq = stopRadius * stopRadius;
        double distSq = tank.getDistanceSq(target);

        if (distSq <= stopRadiusSq) {
            stopVehicle(tank);
            return;
        }

        double dx = target.posX - tank.posX;
        double dz = target.posZ - tank.posZ;

        Vector3f forward = tank.axes.getXAxis();
        double cross = (forward.x * dz) - (forward.z * dx);
        double dot = (forward.x * dx) + (forward.z * dz);

        float turnPower = 0.0F;
        if (cross > 0.5D) turnPower = 1.0F;
        else if (cross < -0.5D) turnPower = -1.0F;

        // If we're facing away, pick a hard turn to re-orient
        if (dot < 0.0D) {
            turnPower = (cross > 0.0D) ? 1.0F : -1.0F;
        }

        tank.wheelsYaw = turnPower * 25.0F;

        // Throttle tuning: faster when far, slower when near
        float throttle;
        if (distSq > 2500.0D) throttle = 1.0F;
        else if (distSq > 900.0D) throttle = 0.7F;
        else throttle = 0.45F;

        // If the vehicle is nearly facing away, reduce throttle a bit to allow turning
        if (dot < 0.0D) throttle = 0.35F;

        tank.throttle = throttle;
    }

    private EntityDriveable getMountedVehicle() {
        Entity riding = this.getRidingEntity();
        if (riding instanceof EntitySeat) {
            EntitySeat s = (EntitySeat) riding;
            return s.driveable;
        }
        if (riding instanceof EntityDriveable) {
            return (EntityDriveable) riding;
        }
        return null;
    }

    private EntitySeat getMountedSeat() {
        Entity riding = this.getRidingEntity();
        if (riding instanceof EntitySeat) {
            return (EntitySeat) riding;
        }

        EntityDriveable v = getMountedVehicle();
        if (v != null) {
            EntitySeat[] seats = getSeatsSafely(v);
            if (seats != null && seats.length > 0) {
                return seats[0];
            }
        }
        return null;
    }

    /**
     * Finds nearest driveable and mounts an empty seat.
     * Prefers seat[0] (driver/primary seat), otherwise first empty seat.
     */
    private void findAndMountNearbyDriveableSeat() {
        AxisAlignedBB box = this.getEntityBoundingBox().grow(8.0D, 4.0D, 8.0D);

        EntityDriveable best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (EntityDriveable d : this.world.getEntitiesWithinAABB(EntityDriveable.class, box)) {
            if (d == null || d.isDead) continue;

            double dsq = this.getDistanceSq(d);
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                best = d;
            }
        }

        if (best == null) return;

        EntitySeat[] seats = getSeatsSafely(best);
        if (seats == null || seats.length == 0) return;

        EntitySeat preferred = seats[0];
        if (preferred != null && !preferred.isDead && preferred.getPassengers().isEmpty() && preferred.getControllingPassenger() == null) {
            this.startRiding(preferred, true);
            return;
        }

        for (EntitySeat s : seats) {
            if (s == null || s.isDead) continue;
            if (!s.getPassengers().isEmpty()) continue;
            if (s.getControllingPassenger() != null) continue;

            this.startRiding(s, true);
            return;
        }
    }

    private void aimSeatAtTarget(EntitySeat seat, EntityLivingBase target) {
        if (seat == null || target == null) return;

        double dx = target.posX - seat.posX;
        double dz = target.posZ - seat.posZ;

        double targetY = target.posY + target.getEyeHeight() * 0.7D;
        double dy = targetY - (seat.posY + seat.getEyeHeight());

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(dy, distXZ) * 180.0D / Math.PI));

        RotatedAxes looking = seat.looking;
        float currentYaw = looking.getYaw();
        float currentPitch = looking.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = MathHelper.wrapDegrees(targetPitch - currentPitch);

        float smoothing = 0.15F;
        float newYaw = currentYaw + deltaYaw * smoothing;
        float newPitch = currentPitch + deltaPitch * smoothing;
        newPitch = MathHelper.clamp(newPitch, -85.0F, 85.0F);

        seat.looking = new RotatedAxes(newYaw, newPitch, 0);
        seat.prevLooking = new RotatedAxes(newYaw, newPitch, 0);
    }

    private boolean tryFireVehicleWeapons(EntityDriveable vehicle) {
        if (vehicle == null || vehicle.isDead) return false;
        if (!hasAnyLoadedAmmo(vehicle)) return false;

        boolean fired = false;

        try {
            vehicle.shoot(false);
            fired = true;
        } catch (Throwable ignored) {
        }

        try {
            vehicle.shoot(true);
            fired = true;
        } catch (Throwable ignored) {
        }

        return fired;
    }

    private boolean hasAnyLoadedAmmo(EntityDriveable vehicle) {
        DriveableData data = vehicle.getDriveableData();
        if (data == null || data.ammo == null) return false;

        for (ItemStack stack : data.ammo) {
            if (stack != null && !stack.isEmpty()) return true;
        }
        return false;
    }

    private int getFireCooldownForVehicle(EntityDriveable vehicle) {
        DriveableType type = vehicle.getDriveableType();
        if (type == null || type.shortName == null) return 10;

        String name = type.shortName.toLowerCase();

        if (name.contains("mg") || name.contains("machine") || name.contains("browning") || name.contains("coax")) {
            return FIRE_COOLDOWN_MIN_TICKS;
        }
        if (name.contains("aa") || name.contains("flak") || name.contains("bofors")) {
            return 4;
        }
        if (name.contains("tank") || name.contains("cannon") || name.contains("howitzer") || name.contains("artillery")) {
            return 20;
        }
        return 8;
    }

    private void resupplyFromNearbyChests(EntityDriveable vehicle) {
        DriveableData data = vehicle.getDriveableData();
        DriveableType type = vehicle.getDriveableType();
        if (data == null || type == null) return;

        BlockPos center = vehicle.getPosition();
        int r = RESUPPLY_RADIUS_BLOCKS;

        Iterable<BlockPos.MutableBlockPos> positions = BlockPos.getAllInBoxMutable(
                center.add(-r, -r, -r),
                center.add(r, r, r)
        );

        for (BlockPos.MutableBlockPos mpos : positions) {
            TileEntity te = world.getTileEntity(mpos);
            if (te == null) continue;

            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (handler == null) continue;

            moveFuelFromChestIntoVehicleInventory(handler, vehicle);
            moveAmmoFromChestIntoAmmoSlots(handler, vehicle);
        }
    }

    private void moveFuelFromChestIntoVehicleInventory(IItemHandler chest, EntityDriveable vehicle) {
        DriveableData data = vehicle.getDriveableData();
        DriveableType type = vehicle.getDriveableType();
        if (data == null || type == null) return;

        if (type.fuelTankSize <= 0) return;
        if (data.fuelInTank >= type.fuelTankSize) return;

        int emptyVehicleSlot = findFirstEmptyVehicleInventorySlotCompat(data);
        if (emptyVehicleSlot < 0) return;

        for (int slot = 0; slot < chest.getSlots(); slot++) {
            ItemStack stack = chest.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (!(item instanceof ItemPart)) continue;

            PartType part = ((ItemPart) item).type;
            if (part == null || part.category != EnumPartCategory.FUEL) continue;

            ItemStack extracted = chest.extractItem(slot, 1, false);
            if (extracted == null || extracted.isEmpty()) continue;

            boolean placed = setDriveableInventoryStackCompat(data, emptyVehicleSlot, extracted);
            if (!placed) {
                try {
                    chest.insertItem(slot, extracted, false);
                } catch (Throwable ignored) {
                }
            }
            break;
        }
    }

    private void moveAmmoFromChestIntoAmmoSlots(IItemHandler chest, EntityDriveable vehicle) {
        DriveableData data = vehicle.getDriveableData();
        if (data == null || data.ammo == null) return;

        // Top up existing stacks
        for (int i = 0; i < data.ammo.length; i++) {
            ItemStack slotStack = data.ammo[i];
            if (slotStack == null || slotStack.isEmpty()) continue;
            if (!(slotStack.getItem() instanceof ItemShootable)) continue;

            int space = slotStack.getMaxStackSize() - slotStack.getCount();
            if (space <= 0) continue;

            ItemStack pulled = pullExactMatchFromHandler(chest, slotStack, space);
            if (pulled != null && !pulled.isEmpty()) {
                slotStack.grow(pulled.getCount());
                data.ammo[i] = slotStack;
            }
        }

        // Fill empty slots with any shootable ammo
        for (int i = 0; i < data.ammo.length; i++) {
            ItemStack slotStack = data.ammo[i];
            if (slotStack != null && !slotStack.isEmpty()) continue;

            ItemStack pulled = pullFirstShootableFromHandler(chest, 64);
            if (pulled != null && !pulled.isEmpty()) {
                data.ammo[i] = pulled;
            }
        }
    }

    private ItemStack pullExactMatchFromHandler(IItemHandler handler, ItemStack template, int maxToPull) {
        if (handler == null || template == null || template.isEmpty() || maxToPull <= 0) return ItemStack.EMPTY;

        int remaining = maxToPull;
        ItemStack pulledTotal = ItemStack.EMPTY;

        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;

            if (!ItemStack.areItemsEqual(stack, template)) continue;
            if (!ItemStack.areItemStackTagsEqual(stack, template)) continue;

            int toExtract = Math.min(remaining, stack.getCount());
            ItemStack extracted = handler.extractItem(slot, toExtract, false);
            if (extracted == null || extracted.isEmpty()) continue;

            if (pulledTotal.isEmpty()) {
                pulledTotal = extracted.copy();
            } else {
                pulledTotal.grow(extracted.getCount());
            }

            remaining -= extracted.getCount();
        }

        return pulledTotal;
    }

    private ItemStack pullFirstShootableFromHandler(IItemHandler handler, int maxToPull) {
        if (handler == null || maxToPull <= 0) return ItemStack.EMPTY;

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;

            if (!(stack.getItem() instanceof ItemShootable)) continue;

            int toExtract = Math.min(maxToPull, stack.getCount());
            ItemStack extracted = handler.extractItem(slot, toExtract, false);
            if (extracted == null || extracted.isEmpty()) continue;

            return extracted;
        }

        return ItemStack.EMPTY;
    }

    private int findFirstEmptyVehicleInventorySlotCompat(DriveableData data) {
        int size = getDriveableInventorySizeCompat(data);
        if (size <= 0) return -1;

        for (int i = 0; i < size; i++) {
            ItemStack s = getDriveableInventoryStackCompat(data, i);
            if (s == null || s.isEmpty()) return i;
        }
        return -1;
    }

    private int getDriveableInventorySizeCompat(DriveableData data) {
        if (data == null) return 0;

        Integer byMethod = tryInvokeIntMethod(data, "getSizeInventory");
        if (byMethod != null && byMethod > 0) return byMethod;

        byMethod = tryInvokeIntMethod(data, "getInventorySize");
        if (byMethod != null && byMethod > 0) return byMethod;

        Integer byField = tryReadItemStackArrayLength(data, "inventory");
        if (byField != null && byField > 0) return byField;

        byField = tryReadItemStackArrayLength(data, "cargo");
        if (byField != null && byField > 0) return byField;

        byField = tryReadItemStackArrayLength(data, "cargoItems");
        if (byField != null && byField > 0) return byField;

        return 0;
    }

    private ItemStack getDriveableInventoryStackCompat(DriveableData data, int slot) {
        if (data == null || slot < 0) return ItemStack.EMPTY;

        ItemStack byMethod = tryInvokeItemStackMethod(data, "getStackInSlot", slot);
        if (byMethod != null) return byMethod;

        byMethod = tryInvokeItemStackMethod(data, "getInventoryStack", slot);
        if (byMethod != null) return byMethod;

        byMethod = tryInvokeItemStackMethod(data, "getCargoStack", slot);
        if (byMethod != null) return byMethod;

        ItemStack byField = tryReadItemStackArrayField(data, "inventory", slot);
        if (byField != null) return byField;

        byField = tryReadItemStackArrayField(data, "cargo", slot);
        if (byField != null) return byField;

        byField = tryReadItemStackArrayField(data, "cargoItems", slot);
        if (byField != null) return byField;

        return ItemStack.EMPTY;
    }

    private boolean setDriveableInventoryStackCompat(DriveableData data, int slot, ItemStack stack) {
        if (data == null || slot < 0) return false;

        boolean viaMethod = tryInvokeVoidSetMethod(data, "setInventorySlotContents", slot, stack);
        if (viaMethod) return true;

        viaMethod = tryInvokeVoidSetMethod(data, "setStackInSlot", slot, stack);
        if (viaMethod) return true;

        viaMethod = tryInvokeVoidSetMethod(data, "setInventoryStack", slot, stack);
        if (viaMethod) return true;

        boolean viaField = tryWriteItemStackArrayField(data, "inventory", slot, stack);
        if (viaField) return true;

        viaField = tryWriteItemStackArrayField(data, "cargo", slot, stack);
        if (viaField) return true;

        return tryWriteItemStackArrayField(data, "cargoItems", slot, stack);
    }

    private Integer tryInvokeIntMethod(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object o = m.invoke(target);
            if (o instanceof Integer) return (Integer) o;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ItemStack tryInvokeItemStackMethod(Object target, String methodName, int slot) {
        try {
            Method m = target.getClass().getMethod(methodName, int.class);
            Object o = m.invoke(target, slot);
            if (o instanceof ItemStack) {
                ItemStack s = (ItemStack) o;
                return s == null ? ItemStack.EMPTY : s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean tryInvokeVoidSetMethod(Object target, String methodName, int slot, ItemStack stack) {
        try {
            Method m = target.getClass().getMethod(methodName, int.class, ItemStack.class);
            m.invoke(target, slot, stack);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private ItemStack tryReadItemStackArrayField(Object target, String fieldName, int slot) {
        try {
            Field f = target.getClass().getField(fieldName);
            Object o = f.get(target);
            if (o instanceof ItemStack[]) {
                ItemStack[] arr = (ItemStack[]) o;
                if (slot >= 0 && slot < arr.length) {
                    ItemStack s = arr[slot];
                    return s == null ? ItemStack.EMPTY : s;
                }
                return ItemStack.EMPTY;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Integer tryReadItemStackArrayLength(Object target, String fieldName) {
        try {
            Field f = target.getClass().getField(fieldName);
            Object o = f.get(target);
            if (o instanceof ItemStack[]) return ((ItemStack[]) o).length;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean tryWriteItemStackArrayField(Object target, String fieldName, int slot, ItemStack stack) {
        try {
            Field f = target.getClass().getField(fieldName);
            Object o = f.get(target);
            if (o instanceof ItemStack[]) {
                ItemStack[] arr = (ItemStack[]) o;
                if (slot >= 0 && slot < arr.length) {
                    arr[slot] = stack;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void sendVehicleControlPacket(EntityDriveable vehicle) {
        if (vehicle == null || vehicle.world == null || vehicle.world.isRemote) return;

        try {
            FlansMod.getPacketHandler().sendToAllAround(
                    new PacketVehicleControl(vehicle),
                    vehicle.posX,
                    vehicle.posY,
                    vehicle.posZ,
                    FlansMod.driveableUpdateRange,
                    vehicle.world.provider.getDimension()
            );
        } catch (Throwable ignored) {
        }
    }

    private EntitySeat[] getSeatsSafely(EntityDriveable vehicle) {
        try {
            if (seatsField == null) {
                seatsField = EntityDriveable.class.getDeclaredField("seats");
                seatsField.setAccessible(true);
            }
            return (EntitySeat[]) seatsField.get(vehicle);
        } catch (Exception e) {
            return new EntitySeat[0];
        }
    }

    // ============================
    // AW2 "friendly faction" best-effort
    // ============================

    private boolean isFriendlyAw2Unit(EntityLivingBase other) {
        if (other == null) return false;

        if (other instanceof EntityTankOperator) {
            EntityTankOperator op = (EntityTankOperator) other;
            return safeEqualsIgnoreCase(this.aw2Faction, op.aw2Faction);
        }

        String otherFaction = tryGetAw2FactionId(other);
        if (otherFaction == null) return false;
        if (this.aw2Faction == null || this.aw2Faction.trim().isEmpty()) return false;

        return safeEqualsIgnoreCase(this.aw2Faction, otherFaction);
    }

    private void ensureAw2FactionApplied() {
        trySetAw2FactionId(this, this.aw2Faction);
    }

    @Nullable
    private String tryGetAw2FactionId(Entity entity) {
        if (entity == null) return null;

        String s = tryInvokeStringNoArg(entity, "getFactionName");
        if (s != null) return s;

        Object factionObj = tryInvokeNoArg(entity, "getFaction");
        if (factionObj == null) factionObj = tryInvokeNoArg(entity, "getNpcFaction");
        if (factionObj == null) factionObj = tryInvokeNoArg(entity, "getFactionData");

        if (factionObj != null) {
            String name = tryInvokeStringNoArg(factionObj, "getName");
            if (name != null) return name;

            name = tryInvokeStringNoArg(factionObj, "getFactionName");
            if (name != null) return name;

            String byField = tryReadStringField(factionObj, "name");
            if (byField != null) return byField;

            byField = tryReadStringField(factionObj, "factionName");
            if (byField != null) return byField;
        }

        try {
            NBTTagCompound ed = entity.getEntityData();
            if (ed != null) {
                if (ed.hasKey("faction")) return ed.getString("faction");
                if (ed.hasKey("factionName")) return ed.getString("factionName");
                if (ed.hasKey("aw2Faction")) return ed.getString("aw2Faction");
                if (ed.hasKey(NBT_AW2_FACTION)) return ed.getString(NBT_AW2_FACTION);
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private void trySetAw2FactionId(Entity entity, String factionId) {
        if (entity == null) return;
        if (factionId == null || factionId.trim().isEmpty()) return;

        boolean ok = tryInvokeVoidOneString(entity, "setFactionName", factionId);
        if (ok) return;

        ok = tryInvokeVoidOneString(entity, "setFaction", factionId);
        if (ok) return;

        ok = tryInvokeVoidOneString(entity, "setFactionId", factionId);
        if (ok) return;

        try {
            entity.getEntityData().setString(NBT_AW2_FACTION, factionId);
        } catch (Throwable ignored) {
        }
    }

    private boolean safeEqualsIgnoreCase(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    @Nullable
    private Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private String tryInvokeStringNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object o = m.invoke(target);
            if (o instanceof String) {
                String s = (String) o;
                if (s != null && !s.trim().isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean tryInvokeVoidOneString(Object target, String methodName, String arg) {
        try {
            Method m = target.getClass().getMethod(methodName, String.class);
            m.invoke(target, arg);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Nullable
    private String tryReadStringField(Object target, String fieldName) {
        try {
            Field f = target.getClass().getField(fieldName);
            Object o = f.get(target);
            if (o instanceof String) {
                String s = (String) o;
                if (s != null && !s.trim().isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public String getName() {
        if (this.hasCustomName()) return this.getCustomNameTag();
        return "Tank Operator";
    }
}
