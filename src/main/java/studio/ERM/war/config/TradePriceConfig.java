package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * TRADE DEPOT PRICES (config/Homosapien/trade_prices.json)
 *
 * The catalogue the Trade Depot buys and sells: items grouped into categories (Resources, Industrial,
 * Military, Food, ...), each with a base Command-Buck price and a minimum RIVAL level to unlock. The
 * live price a player sees is this base run through market saturation + rival level in
 * {@link studio.ERM.strategic.civil.trade.TradeMarketData} — this config is just the anchor.
 *
 * Rows whose item id can't be resolved at runtime (a missing optional mod) are simply hidden from the
 * menu, never fatal — so the default catalogue can reference IE/Flan items safely.
 */
public final class TradePriceConfig {

    private static final String CONFIG_FILE = "Homosapien/trade_prices.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ConfigData data = new ConfigData();

    private TradePriceConfig() {}

    public static class ItemPrice {
        public String id = "";
        public double price = 1.0;   // base Command Bucks per item
        public int minLevel = 0;     // RIVAL level to unlock buying

        public ItemPrice() {}
        public ItemPrice(String id, double price, int minLevel) {
            this.id = id; this.price = price; this.minLevel = minLevel;
        }
    }

    public static class Category {
        public String name = "";
        public List<ItemPrice> items = new ArrayList<>();
        public Category() {}
        public Category(String name) { this.name = name; }
        Category add(String id, double price, int minLevel) {
            items.add(new ItemPrice(id, price, minLevel));
            return this;
        }
    }

    public static class ConfigData {
        public List<Category> categories = defaults();
        /** How much one sold item raises that item's saturation (drives the price down). */
        public double saturationPerSell = 0.02;
        /** Saturation removed per real minute (recovery toward base price). */
        public double saturationDecayPerMin = 0.05;
        /** Per-RIVAL-level multiplier added to prices (0.05 = +5% value per level). */
        public double rivalPriceBonusPerLevel = 0.05;
        /** Real-seconds a merchant takes to deliver an import / carry off an export. */
        public int shipmentSeconds = 90;
        /** Daily export limit before diminishing returns kick in (refreshed each MC day). */
        public int exportDailyBase = 64;
        /** Each rival level above 1 raises the daily export cap by this fraction of the base. */
        public double exportCapLevelMultiplier = 0.5;
        /** TAXES: Command Bucks generated per housed citizen per night slept. */
        public double taxPerSleep = 2.0;
        /** Minimum hunger multiplier on taxes for a starving citizen (1.0 = well fed). */
        public double taxHungerMin = 0.25;

        void sanitize() {
            if (categories == null || categories.isEmpty()) categories = defaults();
            for (Category c : categories) {
                if (c.items == null) c.items = new ArrayList<>();
                c.items.removeIf(i -> i == null || i.id == null || i.id.isEmpty());
                for (ItemPrice i : c.items) { if (i.price < 0) i.price = 0; if (i.minLevel < 0) i.minLevel = 0; }
            }
            if (saturationPerSell < 0) saturationPerSell = 0.02;
            if (saturationDecayPerMin < 0) saturationDecayPerMin = 0.05;
            if (shipmentSeconds < 1) shipmentSeconds = 90;
            if (exportDailyBase < 1) exportDailyBase = 64;
            if (exportCapLevelMultiplier < 0) exportCapLevelMultiplier = 0.5;
            if (taxPerSleep < 0) taxPerSleep = 2.0;
            if (taxHungerMin < 0 || taxHungerMin > 1) taxHungerMin = 0.25;
        }
    }

    private static List<Category> defaults() {
        List<Category> cats = new ArrayList<>();
        cats.add(new Category("Resources")
                .add("minecraft:log", 2.0, 0)
                .add("minecraft:cobblestone", 0.2, 0)
                .add("minecraft:iron_ingot", 6.0, 0)
                .add("minecraft:coal", 3.0, 0)
                .add("minecraft:gold_ingot", 12.0, 1)
                .add("minecraft:redstone", 2.0, 1)
                .add("minecraft:diamond", 40.0, 3));
        cats.add(new Category("Industrial")
                .add("immersiveengineering:metal:8", 18.0, 2)      // steel ingot
                .add("immersiveengineering:material:8", 10.0, 2)   // component steel / machine part
                .add("immersiveengineering:material:27", 24.0, 3)  // electron tube / electronics
                .add("minecraft:iron_block", 54.0, 1));
        cats.add(new Category("Military")
                .add("multicharacter:infantry_mag_25", 20.0, 1)    // magazine
                .add("minecraft:gunpowder", 4.0, 0)                // ammunition base
                .add("minecraft:lava_bucket", 30.0, 2)             // fuel (stand-in)
                .add("minecraft:golden_apple", 45.0, 2));          // medical supplies (stand-in)
        cats.add(new Category("Food")
                .add("minecraft:bread", 2.0, 0)
                .add("minecraft:beef", 3.0, 0)
                .add("minecraft:fish", 2.0, 0)
                .add("minecraft:wheat", 1.0, 0)
                .add("minecraft:porkchop", 3.0, 0));
        return cats;
    }

    // ---- accessors ----

    public static List<Category> categories() { return data.categories; }

    public static ItemPrice find(String id) {
        for (Category c : data.categories) for (ItemPrice i : c.items) if (i.id.equals(id)) return i;
        return null;
    }

    public static double basePrice(String id) {
        ItemPrice p = find(id);
        return p != null ? p.price : emcFallback(id);
    }

    public static int minLevel(String id) {
        ItemPrice p = find(id);
        return p != null ? p.minLevel : 0;
    }

    public static String categoryOf(String id) {
        for (Category c : data.categories) for (ItemPrice i : c.items) if (i.id.equals(id)) return c.name;
        return "";
    }

    /** Resolve "modid:name" or "modid:name:meta" to a stack, EMPTY if the mod/item is absent. */
    public static ItemStack resolve(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        String[] parts = id.split(":");
        if (parts.length < 2) return ItemStack.EMPTY;
        Item item = Item.getByNameOrId(parts[0] + ":" + parts[1]);
        if (item == null) return ItemStack.EMPTY;
        int meta = parts.length >= 3 ? parseMeta(parts[2]) : 0;
        return new ItemStack(item, 1, meta);
    }

    private static int parseMeta(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Optional ProjectE EMC as a base for items not in the config (best-effort, absent = 1.0). */
    private static double emcFallback(String id) {
        try {
            ItemStack st = resolve(id);
            if (st.isEmpty()) return 1.0;
            Class<?> api = Class.forName("moze_intel.projecte.api.ProjectEAPI");
            Object proxy = api.getMethod("getEMCProxy").invoke(null);
            long emc = (long) proxy.getClass().getMethod("getValue", ItemStack.class).invoke(proxy, st);
            return emc > 0 ? Math.max(0.1, emc / 64.0) : 1.0; // 64 EMC ~ 1 CB, tunable
        } catch (Throwable t) {
            return 1.0;
        }
    }

    // ---- load / save (manual GSON, mirrors DistrictOutputConfig) ----

    public static void load(File configDir) {
        if (configDir == null) return;
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!file.exists()) { data = new ConfigData(); save(configDir); return; }
        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) { data = new ConfigData(); save(configDir); }
            else { loaded.sanitize(); data = loaded; }
            EpochRunnerMod.logger.info("[TRADE-CONFIG] loaded " + CONFIG_FILE
                    + " (" + data.categories.size() + " categories)");
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            EpochRunnerMod.logger.error("[TRADE-CONFIG] failed to load; regenerated defaults", e);
        }
    }

    public static void save(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            EpochRunnerMod.logger.error("[TRADE-CONFIG] failed to save", e);
        }
    }
}
