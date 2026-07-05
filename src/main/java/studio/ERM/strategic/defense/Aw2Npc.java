package studio.ERM.strategic.defense;

import net.minecraft.entity.Entity;

/**
 * AW2 NPC CLASSIFIER — runtime entity state is the source of truth (NEVER spawner items / item ids,
 * and no registry-name string matching). Mirrors AW2's identity model:
 *
 *   NpcBase.getNpcType()/getNpcSubType()/getNpcFullType(); NpcBase.getOwner() -> Owner(getName/getUUID)
 *   Player-owned combat NPCs: NpcCombat extends NpcPlayerOwned. A player-owned "archer" is NOT its own
 *     class — it's an NpcCombat whose getNpcSubType() is "archer" (bow in mainhand); fullType ~ "combat.archer".
 *   Faction NPCs: NpcFaction (NpcFactionArcher/Soldier/... subclasses) with getFaction(); fullType
 *     prepends the faction, e.g. "empire.archer".
 *
 * Everything resolves reflectively ONCE (AW2 npc module is a hard dep in this pack, but nothing here
 * can crash class-load if it changes) and each accessor degrades to NONE/"" on any failure.
 */
public final class Aw2Npc {

    public enum Allegiance { PLAYER_OWNED, AW2_FACTION, NONE }

    private static boolean resolved = false;
    private static boolean available = false;
    private static Class<?> npcBase, npcPlayerOwned, npcFaction;
    private static java.lang.reflect.Method mType, mSubType, mFullType, mOwner, mFaction, mOwnerName, mOwnerUuid;

    private Aw2Npc() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            npcBase = Class.forName("net.shadowmage.ancientwarfare.npc.entity.NpcBase");
            npcPlayerOwned = Class.forName("net.shadowmage.ancientwarfare.npc.entity.NpcPlayerOwned");
            npcFaction = Class.forName("net.shadowmage.ancientwarfare.npc.entity.faction.NpcFaction");
            mType = npcBase.getMethod("getNpcType");
            mSubType = npcBase.getMethod("getNpcSubType");
            mFullType = npcBase.getMethod("getNpcFullType");
            mOwner = npcBase.getMethod("getOwner");
            mFaction = npcFaction.getMethod("getFaction");
            Class<?> owner = Class.forName("net.shadowmage.ancientwarfare.core.owner.Owner");
            mOwnerName = owner.getMethod("getName");
            mOwnerUuid = owner.getMethod("getUUID");
            available = true;
            studio.ERM.EpochRunnerMod.logger.info("[DefenseAI] Aw2Npc classifier RESOLVED (NpcBase/"
                    + "NpcPlayerOwned/NpcFaction reachable)");
        } catch (Throwable t) {
            available = false;
            // If this ever logs, EVERY classification silently degrades to NONE -- the #1 suspect when
            // "no defenders are ever found". The exact failure is printed so it's diagnosable.
            studio.ERM.EpochRunnerMod.logger.error("[DefenseAI] Aw2Npc classifier FAILED to resolve: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** Full instanceof/identity breakdown for one entity (debug protocol check L). */
    public static String describe(Entity e) {
        resolve();
        if (e == null) return "entity=null";
        StringBuilder sb = new StringBuilder();
        sb.append("class=").append(e.getClass().getName());
        sb.append(" available=").append(available);
        if (available) {
            sb.append(" isNpcBase=").append(npcBase.isInstance(e));
            sb.append(" isPlayerOwned=").append(npcPlayerOwned.isInstance(e));
            sb.append(" isFaction=").append(npcFaction.isInstance(e));
        }
        sb.append(" allegiance=").append(allegiance(e));
        sb.append(" type=").append(type(e));
        sb.append(" subType=").append(subType(e));
        sb.append(" fullType=").append(fullType(e));
        sb.append(" owner=").append(ownerName(e));
        return sb.toString();
    }

    /** Is this entity an AW2 NPC at all (NpcBase)? */
    public static boolean isAw2Npc(Entity e) {
        resolve();
        return available && e != null && npcBase.isInstance(e);
    }

    public static Allegiance allegiance(Entity e) {
        resolve();
        if (!available || e == null) return Allegiance.NONE;
        if (npcFaction.isInstance(e)) return Allegiance.AW2_FACTION;
        if (npcPlayerOwned.isInstance(e)) return Allegiance.PLAYER_OWNED;
        return Allegiance.NONE;
    }

    /** Faction id for AW2_FACTION npcs ("empire", "norska"...); "" otherwise. */
    public static String faction(Entity e) {
        resolve();
        if (!available || e == null || !npcFaction.isInstance(e)) return "";
        try {
            Object f = mFaction.invoke(e);
            return f != null ? f.toString() : "";
        } catch (Throwable t) { return ""; }
    }

    /** Namespaced faction id per the integration notes: "aw2:empire"; "" for non-faction. */
    public static String factionId(Entity e) {
        String f = faction(e);
        return f.isEmpty() ? "" : "aw2:" + f;
    }

    public static String type(Entity e)     { return call(mType, e); }
    public static String subType(Entity e)  { return call(mSubType, e); }
    public static String fullType(Entity e) { return call(mFullType, e); }

    /** Owner NAME of a player-owned npc; "" for faction/none. */
    public static String ownerName(Entity e) {
        resolve();
        if (!available || e == null || !npcPlayerOwned.isInstance(e)) return "";
        try {
            Object owner = mOwner.invoke(e);
            if (owner == null) return "";
            Object n = mOwnerName.invoke(owner);
            return n != null ? n.toString() : "";
        } catch (Throwable t) { return ""; }
    }

    /** Owner UUID of a player-owned npc; null for faction/none. */
    public static java.util.UUID ownerUuid(Entity e) {
        resolve();
        if (!available || e == null || !npcPlayerOwned.isInstance(e)) return null;
        try {
            Object owner = mOwner.invoke(e);
            if (owner == null) return null;
            Object u = mOwnerUuid.invoke(owner);
            return (u instanceof java.util.UUID) ? (java.util.UUID) u : null;
        } catch (Throwable t) { return null; }
    }

    /** The player's ARMY: a player-owned COMBAT npc (includes bow "archer" subtype — same class). */
    public static boolean isPlayerOwnedCombat(Entity e) {
        return allegiance(e) == Allegiance.PLAYER_OWNED && "combat".equalsIgnoreCase(type(e));
    }

    private static String call(java.lang.reflect.Method m, Entity e) {
        resolve();
        if (!available || e == null || m == null || !npcBase.isInstance(e)) return "";
        try {
            Object v = m.invoke(e);
            return v != null ? v.toString() : "";
        } catch (Throwable t) { return ""; }
    }
}
