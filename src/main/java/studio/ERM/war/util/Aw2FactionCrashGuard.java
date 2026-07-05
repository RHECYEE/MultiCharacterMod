package studio.ERM.war.util;

import net.minecraft.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import studio.ERM.EpochRunnerMod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * THE "Ticking entity" NPE KILLER. AW2 faction NPCs spawned from template ADVANCED SPAWNERS can
 * carry a faction string this install's FactionRegistry can't resolve (MohawkyPack spawner NBT —
 * the same class of crash the rival city hit originally). The first targeting tick then dies in
 * NpcFaction.isHostileTowards -> NullPointerException: Ticking entity -> server down.
 *
 * This guard checks EVERY NpcFaction as it joins the world (fresh spawns AND chunk loads, so
 * already-broken NPCs in an existing world get fixed the moment their chunk returns):
 *   - faction resolves -> untouched
 *   - faction null/empty/unknown -> REPAIRED via NpcFaction.setFactionNameAndDefaults to a faction
 *     the registry actually has ("empire" preferred — the rival's own — else the first registered)
 *   - repair impossible -> the NPC is removed (a missing guard beats a dead server)
 *
 * All AW2 access is reflective + guarded: without AW2 every call no-ops. Each bad faction name is
 * logged once. Registered on the Forge EVENT_BUS in EpochRunnerMod.
 */
public final class Aw2FactionCrashGuard {

    private Aw2FactionCrashGuard() {}

    private static boolean resolved = false;
    private static boolean available = false;
    private static Class<?> npcFactionClass;
    private static Field factionNameField;
    private static Method setFactionNameAndDefaults;
    private static Method getFaction;       // FactionRegistry.getFaction(String) -> FactionDefinition
    private static Method getFactionNames;  // FactionRegistry.getFactionNames() -> Set<String>
    private static Object emptyFaction;     // FactionRegistry.EMPTY_FACTION (unknowns may map here)

    private static final Set<String> LOGGED = new HashSet<>();
    private static String cachedRepairName = null;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            npcFactionClass = Class.forName("net.shadowmage.ancientwarfare.npc.entity.faction.NpcFaction");
            factionNameField = npcFactionClass.getDeclaredField("factionName");
            factionNameField.setAccessible(true);
            setFactionNameAndDefaults = npcFactionClass.getMethod("setFactionNameAndDefaults", String.class);
            Class<?> reg = Class.forName("net.shadowmage.ancientwarfare.npc.registry.FactionRegistry");
            getFaction = reg.getMethod("getFaction", String.class);
            getFactionNames = reg.getMethod("getFactionNames");
            try { emptyFaction = reg.getField("EMPTY_FACTION").get(null); } catch (Throwable ignored) {}
            available = true;
            EpochRunnerMod.logger.info("[FactionGuard] AW2 faction crash guard ARMED");
        } catch (Throwable t) {
            available = false;
            EpochRunnerMod.logger.info("[FactionGuard] AW2 not resolvable (" + t.getClass().getSimpleName()
                    + ") — guard idle");
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinWorldEvent e) {
        Entity ent = e.getEntity();
        if (ent == null || ent.world == null || ent.world.isRemote) return;
        resolve();
        if (!available || !npcFactionClass.isInstance(ent)) return;
        try {
            String name = (String) factionNameField.get(ent);
            if (factionResolves(name)) return;

            String repair = repairName();
            if (repair != null) {
                setFactionNameAndDefaults.invoke(ent, repair);
                if (LOGGED.add(String.valueOf(name))) {
                    EpochRunnerMod.logger.warn("[FactionGuard] repaired NPC with unresolvable faction '"
                            + name + "' -> '" + repair + "' (" + ent.getName()
                            + " @ " + (int) ent.posX + "," + (int) ent.posY + "," + (int) ent.posZ
                            + ") — this was the 'Ticking entity' NPE source");
                }
            } else {
                ent.setDead();
                if (LOGGED.add(String.valueOf(name))) {
                    EpochRunnerMod.logger.warn("[FactionGuard] removed NPC with unresolvable faction '"
                            + name + "' (no registered factions to repair to)");
                }
            }
        } catch (Throwable t) {
            // Absolute backstop: an NPC we can't even INSPECT must never reach the tick loop.
            try { ent.setDead(); } catch (Throwable ignored) {}
            if (LOGGED.add("inspect-fail")) {
                EpochRunnerMod.logger.warn("[FactionGuard] removed uninspectable faction NPC: " + t);
            }
        }
    }

    /** True when the registry can actually answer for this faction name. */
    public static boolean factionResolves(String name) {
        resolve();
        if (!available || name == null || name.isEmpty()) return false;
        try {
            Object def = getFaction.invoke(null, name);
            return def != null && (emptyFaction == null || def != emptyFaction);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A faction name that definitely exists: "empire" (the rival's) when registered, else the first. */
    @SuppressWarnings("unchecked")
    public static String repairName() {
        resolve();
        if (!available) return null;
        if (cachedRepairName != null) return cachedRepairName;
        try {
            Set<String> names = (Set<String>) getFactionNames.invoke(null);
            if (names == null || names.isEmpty()) return null;
            cachedRepairName = names.contains("empire") ? "empire" : names.iterator().next();
            return cachedRepairName;
        } catch (Throwable t) {
            return null;
        }
    }
}
