package co.runed.multicharacter.combat;

import net.minecraft.entity.EntityLivingBase;

public class CombatFeatures {

    /**
     * Aim inaccuracy multiplier applied to AI gunners. Returns 1.0 (no
     * modification) by default; hook point for future combat tuning.
     */
    public static double getAimInaccuracyMultiplier(EntityLivingBase shooter) {
        return 1.0;
    }
}
