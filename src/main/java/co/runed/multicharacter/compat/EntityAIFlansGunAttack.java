package co.runed.multicharacter.compat;

import com.flansmod.common.driveables.EntitySeat;
import com.flansmod.common.driveables.EntityDriveable; // Added missing import
import com.flansmod.common.guns.*;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.inventory.EntityEquipmentSlot;
import studio.ERM.war.vehicle.EntityAIPilot;
import studio.ERM.war.items.ItemInfantryMag;

import java.util.Random;

public class EntityAIFlansGunAttack extends EntityAIBase {
    private final EntityLiving entityHost;
    private final double moveSpeedAmp;
    private final int attackCooldown;
    private final float maxAttackDistanceSq;
    private int rangedAttackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;
    private int reloadTimer = 0;

    public EntityAIFlansGunAttack(EntityLiving host, double speed, int delay, float maxDist) {
        this.entityHost = host;
        this.moveSpeedAmp = speed;
        this.attackCooldown = delay * 2;
        // Engagement range. Capped generously (was 60) so gun infantry actually trade fire across a
        // siege field instead of standing around taking the bombardment because the enemy is "too far".
        this.maxAttackDistanceSq = Math.min(maxDist, 110.0F) * Math.min(maxDist, 110.0F);
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        // Proactive target acquisition: do not wait until we are hit
        if (this.entityHost.getAttackTarget() == null || !this.entityHost.getAttackTarget().isEntityAlive()) {
            double range = Math.sqrt(this.maxAttackDistanceSq);
            EntityLivingBase found = findNearestGunTarget(range);
            if (found != null) {
                this.entityHost.setAttackTarget(found);
            }
        }

        EntityLivingBase target = this.entityHost.getAttackTarget();
        if (target == null) return false;

        ItemStack stack = this.entityHost.getHeldItemMainhand();
        if (!isGun(stack)) return false;

        // DRIVER CHECK: Drivers of vehicles can't shoot handheld guns
        if (this.entityHost.isRiding() && this.entityHost.getRidingEntity() instanceof EntitySeat) {
            EntitySeat seat = (EntitySeat) this.entityHost.getRidingEntity();
            if (seat.seatInfo.id == 0 && seat.driveable != null && !seat.driveable.isDead) return false;
        }

        // AMMO CHECK: Only attack if we have loaded ammo or a physical magazine to reload
        int ammo = this.entityHost.getEntityData().getInteger("Infantry_CurrentAmmo");
        return ammo > 0 || hasMagazine();
    }

    @Override
    public boolean shouldContinueExecuting() { return this.shouldExecute() || !this.entityHost.getNavigator().noPath(); }

    private boolean hasMagazine() {
        ItemStack offhand = this.entityHost.getHeldItemOffhand();
        // Specifically check for loaded magazine item type
        return !offhand.isEmpty() && offhand.getItem() instanceof ItemInfantryMag;
    }

    @Override
    public void resetTask() { this.seeTime = 0; this.rangedAttackTime = -1; this.entityHost.getNavigator().clearPath(); }

    @Override
    public void updateTask() {
        EntityLivingBase target = this.entityHost.getAttackTarget();
        if (target == null) return;
        if (this.reloadTimer > 0) { this.reloadTimer--; return; }

        if (this.entityHost.isRiding()) {
            this.entityHost.getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);
            this.entityHost.rotationYawHead = this.entityHost.rotationYaw;
            this.entityHost.renderYawOffset = this.entityHost.rotationYaw;
        } else {
            performInfantryMovement(target);
        }
        performShooting(target);
    }

    private void performInfantryMovement(EntityLivingBase target) {
        double distSq = this.entityHost.getDistanceSq(target.posX, target.getEntityBoundingBox().minY, target.posZ);
        boolean canSee = this.entityHost.getEntitySenses().canSee(target);
        if (canSee != (this.seeTime > 0)) this.seeTime = 0;
        if (canSee) this.seeTime++; else this.seeTime--;

        if (distSq <= (double)this.maxAttackDistanceSq && this.seeTime >= 20) { this.entityHost.getNavigator().clearPath(); ++this.strafingTime; }
        else { this.entityHost.getNavigator().tryMoveToEntityLiving(target, this.moveSpeedAmp); this.strafingTime = -1; }

        if (this.strafingTime >= 20) {
            if (entityHost.getRNG().nextFloat() < 0.3D) this.strafingClockwise = !this.strafingClockwise;
            if (entityHost.getRNG().nextFloat() < 0.3D) this.strafingBackwards = !this.strafingBackwards;
            this.strafingTime = 0;
        }

        if (this.strafingTime > -1) {
            if (distSq > (maxAttackDistanceSq * 0.75F)) this.strafingBackwards = false;
            else if (distSq < (maxAttackDistanceSq * 0.25F)) this.strafingBackwards = true;
            this.entityHost.getMoveHelper().strafe(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
            this.entityHost.faceEntity(target, 30.0F, 30.0F);
        } else { this.entityHost.getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F); }
    }

    private void performShooting(EntityLivingBase target) {
        ItemStack gunStack = this.entityHost.getHeldItemMainhand();
        if (!(gunStack.getItem() instanceof ItemGun)) return;
        GunType gunType = ((ItemGun)gunStack.getItem()).GetType();

        NBTTagCompound nbt = this.entityHost.getEntityData();
        int ammoInGun = nbt.getInteger("Infantry_CurrentAmmo");

        if (ammoInGun <= 0) {
            if (hasMagazine()) {
                ItemStack magStack = this.entityHost.getHeldItemOffhand();

                // Track empty magazines for later drop-off (instead of littering the ground)
                int empties = nbt.getInteger("Infantry_EmptyMags");
                nbt.setInteger("Infantry_EmptyMags", empties + 1);

                // Consume one loaded mag
                magStack.shrink(1);
                if (magStack.isEmpty()) this.entityHost.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);

                // Load rounds based on the magazine capacity
                int cap = 30;
                if (!magStack.isEmpty() && magStack.getItem() instanceof ItemInfantryMag) {
                    cap = ((ItemInfantryMag) magStack.getItem()).capacity;
                } else if (this.entityHost.getHeldItemOffhand().getItem() instanceof ItemInfantryMag) {
                    cap = ((ItemInfantryMag) this.entityHost.getHeldItemOffhand().getItem()).capacity;
                }

                nbt.setInteger("Infantry_CurrentAmmo", cap);
                nbt.setBoolean("Infantry_NeedsAmmo", false);
                this.reloadTimer = 40;
                if (gunType.reloadSound != null) this.entityHost.world.playSound(null, this.entityHost.getPosition(), new SoundEvent(new ResourceLocation(gunType.reloadSound)), SoundCategory.PLAYERS, 1.0F, 1.0F);
            } else {
                // No magazine available - request resupply
                nbt.setBoolean("Infantry_NeedsAmmo", true);
            }
            return;
        }

        if (--this.rangedAttackTime <= 0 && this.entityHost.getEntitySenses().canSee(target)) {
            shootGun(gunType, gunStack, target);
            nbt.setInteger("Infantry_CurrentAmmo", ammoInGun - 1);
            this.rangedAttackTime = gunType.shootDelay > 0 ? (int)(gunType.shootDelay * 2) : this.attackCooldown;
        }
    }

    private boolean isGun(ItemStack stack) { return !stack.isEmpty() && (stack.getItem() instanceof ItemGun || stack.getItem().getRegistryName().toString().contains("gun")); }

    private void shootGun(GunType type, ItemStack stack, EntityLivingBase target) {
        Vec3d look = this.entityHost.getLookVec();
        Vec3d origin = new Vec3d(this.entityHost.posX + (look.x * 0.8), this.entityHost.posY + this.entityHost.getEyeHeight() + (look.y * 0.8), this.entityHost.posZ + (look.z * 0.8));
        Vec3d dir = applySpread(target.getPositionVector().add(0, target.height / 2.0, 0).subtract(origin).normalize(), 3.0f + (Math.sqrt(entityHost.motionX*entityHost.motionX + entityHost.motionZ*entityHost.motionZ) > 0.05D ? 10.0f : 0.0f));

        BulletType bt = (type.ammo != null && !type.ammo.isEmpty() && type.ammo.get(0) instanceof BulletType) ? (BulletType)type.ammo.get(0) : null;
        if (bt != null) {
            EntityBullet bullet = new EntityBullet(this.entityHost.world, new FiredShot(new FireableGun(type, type.damage, type.bulletSpeed, 3.0f, null), bt, this.entityHost), origin, dir);
            if (this.entityHost.isRiding() && this.entityHost.getRidingEntity() instanceof EntitySeat) {
                EntityDriveable d = ((EntitySeat)this.entityHost.getRidingEntity()).driveable;
                if (d != null) bullet.getEntityData().setString("ParentVehicleUUID", d.getUniqueID().toString());
            }
            float speed = type.bulletSpeed > 0.1f ? type.bulletSpeed : 3.0f;
            bullet.motionX = dir.x * speed; bullet.motionY = dir.y * speed; bullet.motionZ = dir.z * speed;
            this.entityHost.world.spawnEntity(bullet);
            // Fixed: Accessed world via this.entityHost.world
            if (type.shootSound != null) this.entityHost.world.playSound(null, this.entityHost.getPosition(), new SoundEvent(new ResourceLocation(type.shootSound)), SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
    }

    private Vec3d applySpread(Vec3d vec, float spread) { Random r = new Random(); float f = spread * 0.017453292F; return vec.add(r.nextGaussian() * f, r.nextGaussian() * f, r.nextGaussian() * f).normalize(); }

    private EntityLivingBase findNearestGunTarget(double range) {
        AxisAlignedBB box = this.entityHost.getEntityBoundingBox().grow(range, range, range);

        EntityLivingBase best = null;
        double bestScore = -1.0D;

        for (EntityLivingBase e : this.entityHost.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (e == null || !e.isEntityAlive()) continue;
            if (e == this.entityHost) continue;

            // Skip same-team entities when teams exist
            if (this.entityHost.isOnSameTeam(e)) continue;

            double distSq = this.entityHost.getDistanceSq(e);
            if (distSq < 0.01D) continue;

            boolean isPlayer = (e instanceof EntityPlayer);
            boolean isPilot = (e instanceof EntityAIPilot);
            boolean isHostileMob = (e instanceof IMob);
            // War combatants (siege invaders / defenders). The isOnSameTeam() check above already
            // skips our own side, so this only ever matches the ENEMY army -- which is exactly what
            // was missing: gun infantry would target stray skeletons/zombies but ignore the actual
            // invasion force. Scored just under players so the enemy army is engaged over wildlife.
            boolean isWarSoldier = (e instanceof studio.ERM.war.BattleManagers.entities.EntitySoldier);

            boolean isAggroingPlayerOrUs = false;
            if (e instanceof EntityLiving) {
                EntityLiving living = (EntityLiving) e;
                EntityLivingBase at = living.getAttackTarget();
                if (at != null && (at instanceof EntityPlayer || at == this.entityHost)) {
                    isAggroingPlayerOrUs = true;
                }
            }

            // Engage: players, hostile mobs, pilots, enemy war soldiers, or anything aggroing a player/us
            if (!(isPlayer || isPilot || isHostileMob || isWarSoldier || isAggroingPlayerOrUs)) continue;

            boolean canSee = this.entityHost.canEntityBeSeen(e);

            double score = 0.0D;
            if (isPilot) score += 60.0D;
            if (isPlayer) score += 50.0D;
            if (isWarSoldier) score += 45.0D;
            if (isHostileMob) score += 40.0D;
            if (isAggroingPlayerOrUs) score += 25.0D;
            if (canSee) score += 10.0D;

            // Closer is better
            double rangeSq = range * range;
            score += (rangeSq - distSq) / rangeSq * 15.0D;

            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }

        return best;
    }

}