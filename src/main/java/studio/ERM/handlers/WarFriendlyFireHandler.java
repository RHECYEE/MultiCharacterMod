package studio.ERM.handlers;

import net.minecraft.entity.Entity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import studio.ERM.war.air.EntityGhostAircraft;
import studio.ERM.war.vehicle.EntityAIPilot;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.BattleManagers.entities.EntitySoldierPuppet;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;

/**
 * Global FRIENDLY-FIRE guard for the war army. The whole mod has exactly two sides: RIVAL (the
 * attacking siege army) and PLAYER. Same-side war entities must NEVER damage each other -- tanks
 * were killing each other, soldiers shot down their own planes/helicopters, and infantry shot each
 * other. This cancels any {@link LivingAttackEvent} whose attacker AND victim are both war entities
 * on the SAME side (covers bullets, explosions, melee -- anything that produces the attack event).
 *
 * Non-war entities are untouched: the rival army can still hurt the player, and the player (and
 * player-called airstrikes, which are team PLAYER) can still kill the rival army.
 *
 * Registered on the Forge EVENT_BUS by EpochRunnerMod.init() (see the unregistered-handler family of
 * bugs -- this MUST be registered to do anything).
 */
public class WarFriendlyFireHandler {

    /** RIVAL / PLAYER for a war entity, or null if {@code e} is not a war entity at all. */
    private static String warTeam(Entity e) {
        if (e == null) return null;
        String t;
        if (e instanceof EntityGhostAircraft) {
            t = ((EntityGhostAircraft) e).getMcmTeam();
        } else if (e instanceof EntityAIPilot) {
            t = ((EntityAIPilot) e).getMcmTeam();
        } else if (e instanceof EntitySoldier || e instanceof EntitySoldierPuppet
                || e instanceof EntityFormationCarrier) {
            return "RIVAL"; // the siege army's infantry/puppets/carriers are always the rival side
        } else {
            return null;    // players, citizens, vanilla mobs, etc. -- not part of the FF rule
        }
        if (t == null) return "RIVAL";
        return t.toUpperCase().contains("PLAYER") ? "PLAYER" : "RIVAL"; // empire/rival/enemy/none -> RIVAL
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLivingAttack(LivingAttackEvent e) {
        // War infantry are FALL-immune: they fast-rope out of helis (at heli height) and pour down breach
        // ramps/stairs -- they must never die to the drop.
        if (e.getSource() == net.minecraft.util.DamageSource.FALL
                && (e.getEntity() instanceof EntitySoldier || e.getEntity() instanceof EntitySoldierPuppet)) {
            e.setCanceled(true);
            return;
        }
        Entity attacker = e.getSource().getTrueSource();
        if (attacker == null) attacker = e.getSource().getImmediateSource();
        if (attacker == null) return;
        String at = warTeam(attacker);
        if (at == null) return;
        String vt = warTeam(e.getEntity());
        if (vt != null && vt.equals(at)) {
            e.setCanceled(true); // FRIENDLY FIRE: same war side -> no damage
        }
    }
}
