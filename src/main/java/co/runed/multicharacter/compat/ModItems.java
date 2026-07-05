package co.runed.multicharacter.compat;

import co.runed.multicharacter.MultiCharacterMod;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraft.init.Items;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import studio.ERM.war.items.ItemBase;
import studio.ERM.war.items.ItemInfantryMag;
import studio.ERM.war.recipes.RecipeMagReload;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = MultiCharacterMod.MODID)
public class ModItems {

    public static Item INFANTRY_AMMO;
    public static Item MAG_5;
    public static Item MAG_10;
    public static Item MAG_15;
    public static Item MAG_20; // New Item
    public static Item MAG_25;
    public static Item MAG_EMPTY;

    private static final List<Item> ITEMS = new ArrayList<>();

    static {
        INFANTRY_AMMO = new ItemBase(MultiCharacterMod.MODID, "infantry_ammo");
        MAG_5 = new ItemInfantryMag(MultiCharacterMod.MODID, "infantry_mag_5", 5);
        MAG_10 = new ItemInfantryMag(MultiCharacterMod.MODID, "infantry_mag_10", 10);
        MAG_15 = new ItemInfantryMag(MultiCharacterMod.MODID, "infantry_mag_15", 15);
        MAG_20 = new ItemInfantryMag(MultiCharacterMod.MODID, "infantry_mag_20", 20); // Initialize MAG_20
        MAG_25 = new ItemInfantryMag(MultiCharacterMod.MODID, "infantry_mag_25", 25);
        MAG_EMPTY = new ItemBase(MultiCharacterMod.MODID, "infantry_mag_empty");

        INFANTRY_AMMO.setCreativeTab(CreativeTabs.COMBAT);
        MAG_5.setCreativeTab(CreativeTabs.COMBAT);
        MAG_10.setCreativeTab(CreativeTabs.COMBAT);
        MAG_15.setCreativeTab(CreativeTabs.COMBAT);
        MAG_20.setCreativeTab(CreativeTabs.COMBAT);
        MAG_25.setCreativeTab(CreativeTabs.COMBAT);
        MAG_EMPTY.setCreativeTab(CreativeTabs.COMBAT);
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {

        RecipeMagReload reloadRecipe = new RecipeMagReload();
        reloadRecipe.setRegistryName(new ResourceLocation(MultiCharacterMod.MODID, "infantry_mag_reload"));
        event.getRegistry().register(reloadRecipe);

        GameRegistry.addShapelessRecipe(new ResourceLocation("multicharacter:craft_ammo"), null,
                new ItemStack(INFANTRY_AMMO, 8),
                Ingredient.fromItem(Items.IRON_INGOT),
                Ingredient.fromItem(Items.IRON_NUGGET),
                Ingredient.fromItem(Items.GUNPOWDER)
        );

        GameRegistry.addShapedRecipe(new ResourceLocation("multicharacter:craft_mag_25"), null,
                new ItemStack(MAG_25), "III", "I I", "III", 'I', Items.IRON_INGOT
        );

        GameRegistry.addShapedRecipe(new ResourceLocation("multicharacter:craft_mag_10"), null,
                new ItemStack(MAG_10), "NNN", "N N", "NNN", 'N', Items.IRON_NUGGET
        );

        // Updated filling recipes using 5-round increments
        // 1. Empty Mag + 5 Ammo -> Mag 5
        GameRegistry.addShapelessRecipe(new ResourceLocation(MultiCharacterMod.MODID, "fill_mag_5"), null,
                new ItemStack(MAG_5), Ingredient.fromItem(MAG_EMPTY),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO)
        );

        // 2. Mag 5 + 5 Ammo -> Mag 10
        GameRegistry.addShapelessRecipe(new ResourceLocation(MultiCharacterMod.MODID, "fill_mag_10"), null,
                new ItemStack(MAG_10), Ingredient.fromItem(MAG_5),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO)
        );

        // 3. Mag 10 + 5 Ammo -> Mag 15
        GameRegistry.addShapelessRecipe(new ResourceLocation(MultiCharacterMod.MODID, "fill_mag_15"), null,
                new ItemStack(MAG_15), Ingredient.fromItem(MAG_10),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO)
        );

        // 4. Mag 15 + 5 Ammo -> Mag 20
        GameRegistry.addShapelessRecipe(new ResourceLocation(MultiCharacterMod.MODID, "fill_mag_20"), null,
                new ItemStack(MAG_20), Ingredient.fromItem(MAG_15),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO)
        );

        // 5. Mag 20 + 5 Ammo -> Mag 25
        GameRegistry.addShapelessRecipe(new ResourceLocation(MultiCharacterMod.MODID, "fill_mag_25"), null,
                new ItemStack(MAG_25), Ingredient.fromItem(MAG_20),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO), Ingredient.fromItem(INFANTRY_AMMO),
                Ingredient.fromItem(INFANTRY_AMMO)
        );
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void registerRenders(ModelRegistryEvent event) {
        // Textures shifted as requested
        hijackTexture(INFANTRY_AMMO, "flansmod:shell");
        hijackTexture(MAG_5, "flansmod:metalwingsection"); // Used old empty texture
        hijackTexture(MAG_10, "flansmod:g43ammo");         // Shifted up from 5
        hijackTexture(MAG_15, "flansmod:type100ammo");    // Shifted up from 10
        hijackTexture(MAG_20, "flansmod:brenammo100");     // Shifted up from 15
        hijackTexture(MAG_25, "flansmod:browningammo");   // Shifted up from 20/Top Tier
        hijackTexture(MAG_EMPTY, "flansmod:metalwingsection");
    }

    @SideOnly(Side.CLIENT)
    private static void hijackTexture(Item item, String textureName) {
        ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(textureName, "inventory"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        ITEMS.clear();
        ITEMS.add(INFANTRY_AMMO);
        ITEMS.add(MAG_5);
        ITEMS.add(MAG_10);
        ITEMS.add(MAG_15);
        ITEMS.add(MAG_20); // Register MAG_20
        ITEMS.add(MAG_25);
        ITEMS.add(MAG_EMPTY);

        event.getRegistry().registerAll(ITEMS.toArray(new Item[0]));
        System.out.println("[MultiCharacter] Registered infantry items: " + ITEMS.size());
    }
}