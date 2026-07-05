package studio.ERM.war.strategy;

/**
 * What a formation is doing with its weapons right now -- a named engagement posture the director assigns
 * so squads have visibly different JOBS instead of all free-firing at the nearest target.
 *
 *   HOLD_FIRE           don't shoot (engineers/escort focused on the work, not the fight).
 *   SUPPRESS_FROM_SLOT  fire on the wall/defenders from a held standoff slot -- do NOT advance.
 *   ADVANCE_AND_FIRE    move toward the objective and fire on contact (the assault element).
 *   MELEE_CONTACT_ONLY  only engage an adjacent enemy (reserved -- no distinct visible action yet).
 *   BREACH_CLEARING     commit hard at the breach mouth -- the visible kill-zone as squads funnel through.
 *   FREE_FIRE           default vanilla engagement (today's behavior when no sector is assigned).
 *
 * Only HOLD_FIRE / SUPPRESS_FROM_SLOT / ADVANCE_AND_FIRE / BREACH_CLEARING map to a visible behavior;
 * FREE_FIRE == today's default and MELEE_CONTACT_ONLY is defined-but-reserved (no invisible RTS depth).
 */
public enum FireSector {
    HOLD_FIRE,
    SUPPRESS_FROM_SLOT,
    ADVANCE_AND_FIRE,
    MELEE_CONTACT_ONLY,
    BREACH_CLEARING,
    FREE_FIRE
}
