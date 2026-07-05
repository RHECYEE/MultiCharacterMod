package studio.ERM.war.BattleManagers.directors;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Invisible position marker spawned when a battle starts; removed when it ends.
 *
 * Historically this entity drove BattleEngine.tick() from its own onUpdate, but that stalled
 * battles whenever the anchor's chunk unloaded. Ticking now lives in DeployedBattleTicker
 * (one authoritative server-tick path), so this entity no longer drives the engine.
 */
public class EntityBattleDirectorAnchor extends Entity {

    private boolean bound = false;

    public EntityBattleDirectorAnchor(World worldIn) {
        super(worldIn);
        this.setSize(0.1F, 0.1F);
        this.noClip = true;
        this.isImmuneToFire = true;
    }

    public void bindToEngine() {
        this.bound = true;
    }

    @Override
    protected void entityInit() {
        // no data parameters
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) return;

        if (!bound) return;

        // Keep it cheap and non-colliding.
        this.motionX = 0;
        this.motionY = 0;
        this.motionZ = 0;

        // Engine ticking moved to DeployedBattleTicker; this anchor is now a passive marker.
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        this.bound = compound.getBoolean("bm_bound");
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setBoolean("bm_bound", bound);
    }

    @Override
    public AxisAlignedBB getEntityBoundingBox() {
        // Reduce interactions further.
        return new AxisAlignedBB(posX, posY, posZ, posX, posY, posZ);
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
