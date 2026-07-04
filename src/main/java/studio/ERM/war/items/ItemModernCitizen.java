package studio.ERM.war.items;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.GameRegistry;
import studio.ERM.war.entities.EntityModernCitizen;

public class ItemModernCitizen extends Item {
    public ItemModernCitizen() {
        // Registry name ("modern_citizen_item") + translation key are assigned centrally in
        // EpochRunnerMod.RegistrationHandler.registerItems(). Setting them here too double-set the
        // registry name and crashed mod loading. The lang files key off "modern_citizen_item".
        setMaxStackSize(16);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            // Spawn the main bot at the clicked location
            EntityModernCitizen citizen = new EntityModernCitizen(world);
            citizen.setPosition(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            world.spawnEntity(citizen);

            // Roll a skin from the citizen's DEFAULT pool (era + role aware: worker vs soldier by job
            // and rival level). setSkinKey() is DataParameter-backed, so it propagates to clients.
            studio.ERM.war.skins.SkinPoolManager.applySkinFromPool(citizen, world.rand);

            if (!player.capabilities.isCreativeMode) {
                player.getHeldItem(hand).shrink(1);
            }
        }
        return EnumActionResult.SUCCESS;
    }

    public static void registerRecipe() {
        // Recipe: Apple + Bread + Wood (Planks)
        GameRegistry.addShapelessRecipe(
                new net.minecraft.util.ResourceLocation("epochrunner", "citizen_recipe"),
                null,
                new ItemStack(studio.ERM.EpochRunnerMod.modern_citizen_item),
                net.minecraft.item.crafting.Ingredient.fromItem(Items.APPLE),
                net.minecraft.item.crafting.Ingredient.fromItem(Items.BREAD),
                net.minecraft.item.crafting.Ingredient.fromItem(Item.getItemFromBlock(Blocks.PLANKS))
        );
    }
}