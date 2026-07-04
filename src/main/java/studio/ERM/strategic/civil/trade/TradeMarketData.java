package studio.ERM.strategic.civil.trade;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.war.config.TradePriceConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * THE MARKET — per-item saturation that bends the Trade Depot's live prices around the config base:
 *
 *   price = base × rivalFactor × saturationFactor
 *     rivalFactor      = 1 + rivalLevel × rivalPriceBonusPerLevel   (higher tech world = dearer goods)
 *     saturationFactor = 1 / (1 + ln(1 + saturation))               (logarithmic decay as you dump supply)
 *
 * Selling raises an item's saturation (each unit floods the market a little, so the price falls the
 * more you export); saturation decays back over real time (recovery). This is what makes endlessly
 * exporting cobblestone worthless and manufactured steel lucrative — exactly the spec's intent.
 */
public class TradeMarketData extends WorldSavedData {

    private static final String NAME = "erm_trade_market";

    public final Map<String, Double> saturation = new HashMap<>();
    private long lastDecayTick = 0;

    /** STANDING SALE ORDERS (item id -> remaining count): couriers stock the Trade Depot with
     *  these; the once-daily trader trip sells whatever is actually stocked and decrements. */
    public final Map<String, Integer> saleOrders = new HashMap<>();
    /** Items exported so far today — drives the diminishing-returns payout curve. */
    public int exportedToday = 0;
    /** The day (totalWorldTime/24000) the daily trader last departed / counter last reset. */
    public long lastTradeDay = -1;

    /** The daily export cap: starts at the config base (64) and rises per rival level. */
    public static int dailyExportCap(World world) {
        int level = DistrictRegistry.rivalLevel(world);
        return (int) Math.max(1, TradePriceConfig.data.exportDailyBase
                * (1.0 + TradePriceConfig.data.exportCapLevelMultiplier * Math.max(0, level - 1)));
    }

    /** Diminishing-returns payout factor for the NEXT {@code count} items exported today:
     *  full price inside the cap, then cap/exported beyond it (asymptotically to zero). */
    public double exportPayoutFactor(World world, int countAfter) {
        int cap = dailyExportCap(world);
        if (countAfter <= cap) return 1.0;
        return cap / (double) countAfter;
    }

    public TradeMarketData() { super(NAME); }
    public TradeMarketData(String name) { super(name); }

    public static TradeMarketData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        TradeMarketData data = (TradeMarketData) storage.getOrLoadData(TradeMarketData.class, NAME);
        if (data == null) {
            data = new TradeMarketData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    /** Live unit price in Command Bucks (never below 0.1). */
    public double price(World world, String id) {
        double base = TradePriceConfig.basePrice(id);
        int level = DistrictRegistry.rivalLevel(world);
        double rivalFactor = 1.0 + level * TradePriceConfig.data.rivalPriceBonusPerLevel;
        double sat = saturation.getOrDefault(id, 0.0);
        double saturationFactor = 1.0 / (1.0 + Math.log(1.0 + Math.max(0, sat)));
        return Math.max(0.1, base * rivalFactor * saturationFactor);
    }

    /** Demand tier from the saturation factor: fresh market = high demand. */
    public String demand(World world, String id) {
        double sat = saturation.getOrDefault(id, 0.0);
        double f = 1.0 / (1.0 + Math.log(1.0 + Math.max(0, sat)));
        if (f > 0.85) return "Very High";
        if (f > 0.6) return "High";
        if (f > 0.35) return "Medium";
        return "Low";
    }

    /** Exporting floods the market: raise saturation so the next unit is worth less. */
    public void recordSell(String id, int count) {
        saturation.merge(id, count * TradePriceConfig.data.saturationPerSell, Double::sum);
        markDirty();
    }

    /** Importing draws down saturation slightly (buying pulls supply out of the market). */
    public void recordBuy(String id, int count) {
        double v = Math.max(0, saturation.getOrDefault(id, 0.0) - count * TradePriceConfig.data.saturationPerSell * 0.5);
        if (v <= 0) saturation.remove(id); else saturation.put(id, v);
        markDirty();
    }

    /** Recover prices over real time — call periodically from the shipment manager tick. */
    public void decay(WorldServer world) {
        long now = world.getTotalWorldTime();
        if (lastDecayTick == 0) { lastDecayTick = now; return; }
        double minutes = (now - lastDecayTick) / 1200.0;
        if (minutes < 1.0) return;
        lastDecayTick = now;
        double dec = minutes * TradePriceConfig.data.saturationDecayPerMin;
        boolean changed = false;
        for (Map.Entry<String, Double> e : new HashMap<>(saturation).entrySet()) {
            double v = e.getValue() - dec;
            if (v <= 0) saturation.remove(e.getKey()); else saturation.put(e.getKey(), v);
            changed = true;
        }
        if (changed) markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        saturation.clear();
        NBTTagCompound s = nbt.getCompoundTag("sat");
        for (String k : s.getKeySet()) saturation.put(k, s.getDouble(k));
        lastDecayTick = nbt.getLong("lastDecay");
        saleOrders.clear();
        NBTTagCompound so = nbt.getCompoundTag("saleOrders");
        for (String k : so.getKeySet()) saleOrders.put(k, so.getInteger(k));
        exportedToday = nbt.getInteger("exportedToday");
        lastTradeDay = nbt.hasKey("lastTradeDay") ? nbt.getLong("lastTradeDay") : -1;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound s = new NBTTagCompound();
        for (Map.Entry<String, Double> e : saturation.entrySet()) s.setDouble(e.getKey(), e.getValue());
        nbt.setTag("sat", s);
        nbt.setLong("lastDecay", lastDecayTick);
        NBTTagCompound so = new NBTTagCompound();
        for (Map.Entry<String, Integer> e : saleOrders.entrySet()) so.setInteger(e.getKey(), e.getValue());
        nbt.setTag("saleOrders", so);
        nbt.setInteger("exportedToday", exportedToday);
        nbt.setLong("lastTradeDay", lastTradeDay);
        return nbt;
    }
}
