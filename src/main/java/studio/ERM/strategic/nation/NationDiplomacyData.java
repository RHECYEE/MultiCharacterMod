package studio.ERM.strategic.nation;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * DIPLOMATIC STATE with the Nation States — keyed by nation name. Nations start UNMET; the player's
 * simple actions (Send Envoy / Purchase Trade Agreement / Buy Claim) advance the relationship. Read
 * by the Trade Depot / reaction systems later; written by the claim-map diplomacy dropdown.
 */
public class NationDiplomacyData extends WorldSavedData {

    private static final String NAME = "erm_nation_diplomacy";

    public static final int UNMET = 0, CONTACTED = 1, TRADE_PARTNER = 2;

    public static class Relation {
        public int status = UNMET;
        public boolean tradeAgreement = false;
        public boolean claimBought = false;
    }

    public final Map<String, Relation> relations = new HashMap<>();

    public NationDiplomacyData() { super(NAME); }
    public NationDiplomacyData(String name) { super(name); }

    public static NationDiplomacyData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        NationDiplomacyData data = (NationDiplomacyData) storage.getOrLoadData(NationDiplomacyData.class, NAME);
        if (data == null) {
            data = new NationDiplomacyData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    public Relation forNation(String name) {
        return relations.computeIfAbsent(name, k -> new Relation());
    }

    public static String statusName(int s) {
        switch (s) {
            case CONTACTED: return "Contacted";
            case TRADE_PARTNER: return "Trade Partner";
            default: return "Unmet";
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        relations.clear();
        NBTTagCompound m = nbt.getCompoundTag("rel");
        for (String k : m.getKeySet()) {
            NBTTagCompound e = m.getCompoundTag(k);
            Relation r = new Relation();
            r.status = e.getInteger("s");
            r.tradeAgreement = e.getBoolean("t");
            r.claimBought = e.getBoolean("c");
            relations.put(k, r);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound m = new NBTTagCompound();
        for (Map.Entry<String, Relation> en : relations.entrySet()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setInteger("s", en.getValue().status);
            e.setBoolean("t", en.getValue().tradeAgreement);
            e.setBoolean("c", en.getValue().claimBought);
            m.setTag(en.getKey(), e);
        }
        nbt.setTag("rel", m);
        return nbt;
    }
}
