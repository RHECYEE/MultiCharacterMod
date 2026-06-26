package studio.ERM.war.districts;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * Tile entity placed by BlockDistrictMarker to store the district type.
 */
public class TileEntityDistrictMarker extends TileEntity {

    private DistrictType districtType = DistrictType.NONE;

    public DistrictType getDistrictType() {
        return districtType;
    }

    public void setDistrictType(DistrictType type) {
        this.districtType = type != null ? type : DistrictType.NONE;
        markDirty();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("districtType", districtType.name());
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
    }
}
