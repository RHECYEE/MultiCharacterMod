package studio.ERM.war.districts;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * THE DISTRICT DEPOT — the persistent heart of a district. Placed by BlockDistrictMarker inside a
 * drawn district polygon and bound to it (districtUid). NPCs are replaceable labor; this block is
 * what survives them: the depot inventory, the courier IN/OUT request templates, and the desired
 * worker count all live here.
 *
 *  - depot:        27 real slots. Workers deposit district yields; workers pull needed supplies.
 *  - inTemplates:  20 GHOST slots — items the district wants KEPT STOCKED (courier deliveries).
 *  - outTemplates: 20 GHOST slots — items the district EXPORTS (courier pickups).
 *    Templates are patterns only; no items are ever consumed by setting them.
 *  - desiredWorkers: how many citizens the district system should staff here.
 *  - districtUid:  uid of the owning CivilMarker polygon (-1 = unbound).
 *
 * The legacy districtType label is kept for older block-marker code paths.
 */
public class TileEntityDistrictMarker extends TileEntity {

    public static final int DEPOT_SLOTS = 27;
    public static final int TEMPLATE_SLOTS = 20;

    private DistrictType districtType = DistrictType.NONE;

    public final ItemStackHandler depot = new ItemStackHandler(DEPOT_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) { markDirty(); }
    };
    // Ghost templates: patterns only — set/cleared by GUI clicks, never touched by automation.
    public final ItemStackHandler inTemplates = new ItemStackHandler(TEMPLATE_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) { markDirty(); }
    };
    public final ItemStackHandler outTemplates = new ItemStackHandler(TEMPLATE_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) { markDirty(); }
    };

    private int desiredWorkers = 4;
    private int districtUid = -1;
    /** District sub-mode toggle (currently: LUMBER 0 = Tree Farm, 1 = Fruit Farm). */
    private int subMode = 0;

    public DistrictType getDistrictType() {
        return districtType;
    }

    public void setDistrictType(DistrictType type) {
        this.districtType = type != null ? type : DistrictType.NONE;
        markDirty();
    }

    public int getDesiredWorkers() {
        return desiredWorkers;
    }

    public void setDesiredWorkers(int n) {
        this.desiredWorkers = Math.max(0, Math.min(64, n));
        markDirty();
    }

    public int getDistrictUid() {
        return districtUid;
    }

    public void setDistrictUid(int uid) {
        this.districtUid = uid;
        markDirty();
    }

    public boolean isBound() {
        return districtUid != -1;
    }

    public int getSubMode() { return subMode; }

    public void setSubMode(int m) { this.subMode = Math.max(0, m); markDirty(); }

    // ------------------------------------------------------------------
    // Capabilities: hoppers/pipes see ONLY the real depot inventory.
    // ------------------------------------------------------------------

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                || super.hasCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return (T) depot;
        return super.getCapability(capability, facing);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("districtType", districtType.name());
        compound.setTag("depot", depot.serializeNBT());
        compound.setTag("inTpl", inTemplates.serializeNBT());
        compound.setTag("outTpl", outTemplates.serializeNBT());
        compound.setInteger("workers", desiredWorkers);
        compound.setInteger("districtUid", districtUid);
        compound.setInteger("subMode", subMode);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("districtType")) {
            try {
                districtType = DistrictType.valueOf(compound.getString("districtType"));
            } catch (IllegalArgumentException e) {
                districtType = DistrictType.NONE;
            }
        }
        if (compound.hasKey("depot")) depot.deserializeNBT(compound.getCompoundTag("depot"));
        if (compound.hasKey("inTpl")) inTemplates.deserializeNBT(compound.getCompoundTag("inTpl"));
        if (compound.hasKey("outTpl")) outTemplates.deserializeNBT(compound.getCompoundTag("outTpl"));
        if (compound.hasKey("workers")) desiredWorkers = compound.getInteger("workers");
        districtUid = compound.hasKey("districtUid") ? compound.getInteger("districtUid") : -1;
        subMode = compound.getInteger("subMode");
    }
}
