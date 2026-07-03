package studio.ERM.war.BattleManagers.entities;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Lightweight visual puppet that is "dragged" by a formation carrier.
 * No AI. It simply sticks to an offset around the carrier with slight jitter.
 *
 * Skin key is synced to clients via DataWatcher so the renderer can apply
 * the correct AW2 skin pack texture.
 */
public class EntitySoldierPuppet extends EntityCreature implements studio.ERM.war.skins.ISkinnable {

    // DataWatcher key — automatically synced to all clients watching this entity.
    private static final DataParameter<String> DW_SKIN_KEY =
            EntityDataManager.createKey(EntitySoldierPuppet.class, DataSerializers.STRING);

    private int carrierEntityId = -1;
    private int slotIndex = -1;

    private double baseOffsetX = 0;
    private double baseOffsetY = 0;
    private double baseOffsetZ = 0;

    public EntitySoldierPuppet(World worldIn) {
        super(worldIn);
        this.setSize(0.55F, 1.75F);
        this.enablePersistence();
        this.setNoAI(true);
        // Puppets are visual-only and must never die to collision/suffocation.
        this.noClip = true;
        this.experienceValue = 0;
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(DW_SKIN_KEY, "");
    }

    @Override
    protected void initEntityAI() {
        // No AI
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        // Health is irrelevant because we make puppets invulnerable, but keep a sane value.
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.0D);
    }

    public void bindToCarrier(int carrierEntityId, int slotIndex, Vec3d baseOffset) {
        this.carrierEntityId = carrierEntityId;
        this.slotIndex = slotIndex;
        if (baseOffset != null) {
            this.baseOffsetX = baseOffset.x;
            this.baseOffsetY = baseOffset.y;
            this.baseOffsetZ = baseOffset.z;
        }
    }

    public int getCarrierEntityId() {
        return carrierEntityId;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setTexturePathNoExt(String domainAndPathNoExt) {
        String key = domainAndPathNoExt == null ? "" : domainAndPathNoExt;
        this.dataManager.set(DW_SKIN_KEY, key);
    }

    public String getTexturePathNoExt() {
        return this.dataManager.get(DW_SKIN_KEY);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) return;

        if (carrierEntityId <= 0) return;

        EntityFormationCarrier carrier = null;
        try {
            if (world.getEntityByID(carrierEntityId) instanceof EntityFormationCarrier) {
                carrier = (EntityFormationCarrier) world.getEntityByID(carrierEntityId);
            }
        } catch (Throwable ignored) { }

        if (carrier == null || carrier.isDead) {
            setDead();
            return;
        }

        // Dynamic jitter to keep it from looking rigid and to hide imperfect pathing.
        double t = (ticksExisted + (slotIndex * 13)) * 0.14D;
        double jx = Math.sin(t) * 0.12D;
        double jz = Math.cos(t * 1.17D) * 0.12D;

        Vec3d rotated = carrier.rotateOffset(new Vec3d(baseOffsetX + jx, baseOffsetY, baseOffsetZ + jz));

        double tx = carrier.posX + rotated.x;
        double ty = carrier.posY + rotated.y;
        double tz = carrier.posZ + rotated.z;

        // PHYSICAL MARCH: chase the slot at a bounded walking pace instead of hard-snapping to it every
        // tick. When the carrier turns or crosses rough ground the squad visibly JOGS back into position
        // ("reforming") instead of whipping around rigidly -- and the small per-tick steps are what make
        // the client animate their legs as a real march. Feet conform to the ground under the puppet (it
        // is noClip, so previously it hovered/embedded at the carrier's exact Y on any slope).
        double dx = tx - this.posX, dz = tz - this.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 24.0) {
            this.setPosition(tx, ty, tz); // lost the formation entirely -> the old snap as a last resort
            this.rotationYaw = carrier.rotationYaw;
            this.renderYawOffset = carrier.rotationYaw;
            this.rotationYawHead = carrier.rotationYaw;
            return;
        }
        double step = Math.min(dist, 0.38); // max chase speed: a brisk jog, always able to catch the carrier
        double nx = (dist > 0.001) ? this.posX + dx / dist * step : tx;
        double nz = (dist > 0.001) ? this.posZ + dz / dist * step : tz;
        this.setPosition(nx, groundYFor(nx, ty, nz), nz);

        if (step > 0.08) {
            // Catching up: face the direction of movement (the squad visibly turns and jogs to reform).
            float moveYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            this.rotationYaw = moveYaw;
            this.renderYawOffset = moveYaw;
            this.rotationYawHead = moveYaw;
        } else {
            // In slot: face the same way as the carrier (the held formation).
            this.rotationYaw = carrier.rotationYaw;
            this.renderYawOffset = carrier.rotationYaw;
            this.rotationYawHead = carrier.rotationYaw;
        }
    }

    /** The Y to stand at near (x, z): the top of the highest standable block within a small band around
     *  the carrier's level, so puppets walk the terrain under THEM (stairs, ramps, bridge decks) instead
     *  of hovering at the carrier's exact Y across every slope. Falls back to the carrier level. */
    private double groundYFor(double x, double baseY, double z) {
        int bx = net.minecraft.util.math.MathHelper.floor(x);
        int bz = net.minecraft.util.math.MathHelper.floor(z);
        int by = net.minecraft.util.math.MathHelper.floor(baseY);
        try {
            for (int dy = 2; dy >= -3; dy--) {
                net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(bx, by + dy, bz);
                if (world.getBlockState(p).getMaterial().isSolid()
                        && !world.getBlockState(p.up()).getMaterial().isSolid()) {
                    return by + dy + 1;
                }
            }
        } catch (Throwable ignored) {}
        return baseY;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        // Visual-only entity; never take damage.
        return false;
    }

    @Override
    public boolean isEntityInvulnerable(DamageSource source) {
        return true;
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        carrierEntityId = compound.getInteger("bm_carrierId");
        slotIndex = compound.getInteger("bm_slotIndex");
        baseOffsetX = compound.getDouble("bm_offX");
        baseOffsetY = compound.getDouble("bm_offY");
        baseOffsetZ = compound.getDouble("bm_offZ");
        // Support both legacy fields.
        String skinKey = compound.getString("ermSkinKey");
        if (skinKey == null || skinKey.trim().isEmpty()) {
            skinKey = compound.getString("erm_texturePathNoExt");
        }
        this.dataManager.set(DW_SKIN_KEY, skinKey != null ? skinKey : "");
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setInteger("bm_carrierId", carrierEntityId);
        compound.setInteger("bm_slotIndex", slotIndex);
        compound.setDouble("bm_offX", baseOffsetX);
        compound.setDouble("bm_offY", baseOffsetY);
        compound.setDouble("bm_offZ", baseOffsetZ);
        String key = this.dataManager.get(DW_SKIN_KEY);
        compound.setString("erm_texturePathNoExt", key);
        compound.setString("ermSkinKey", key);
    }

    // ISkinnable

    @Override
    public String getSkinKey() {
        return this.dataManager.get(DW_SKIN_KEY);
    }

    @Override
    public void setSkinKey(String key) {
        this.dataManager.set(DW_SKIN_KEY, key != null ? key : "");
    }

    @Override
    public String getDefaultPoolName() {
        return "soldiers";
    }

    @Override
    public ResourceLocation getFallbackTexture() {
        // Vanilla Steve.
        return new ResourceLocation("minecraft", "textures/entity/steve.png");
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }
}
