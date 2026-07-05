package studio.ERM.war.items;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.districts.DistrictType;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Expertise Item (Option A):
 * Four separate registry items (industry/agriculture/defense/resource).
 */
public class ItemExpertise extends Item {

    public enum ExpertiseKind {
        INDUSTRY,
        AGRICULTURE,
        DEFENSE,
        RESOURCE
    }

    private final ExpertiseKind kind;

    public ItemExpertise(ExpertiseKind kind) {
        super();
        this.kind = kind;
        this.setMaxStackSize(1);
        this.setCreativeTab(CreativeTabs.MISC);
    }

    public ExpertiseKind getKind() {
        return kind;
    }

    /**
     * Compatibility helper used by district reward logic.
     * Returns the correct ItemStack for the given district type.
     */
    public static ItemStack createFromDistrict(DistrictType type) {
        if (type == null) return ItemStack.EMPTY;

        Item item;
        switch (type) {
            case INDUSTRY:
                item = EpochRunnerMod.expertise_industry;
                break;
            case AGRICULTURE:
                item = EpochRunnerMod.expertise_agriculture;
                break;
            case DEFENSE:
                item = EpochRunnerMod.expertise_defense;
                break;
            case RESOURCE:
                item = EpochRunnerMod.expertise_resource;
                break;
            default:
                return ItemStack.EMPTY;
        }

        if (item == null) return ItemStack.EMPTY;
        return new ItemStack(item, 1);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add("Expertise: " + kind.name().toLowerCase());
    }
}
