package studio.ERM.war.BattleManagers.core;

import studio.ERM.war.BattleManagers.cards.SlotPayload;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitRole;
import studio.ERM.war.rival.RivalCityConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v7 — Converts UnitCard + level into slot payloads using configurable settings.
 *
 * Reads payload types from RivalCityConfig so modpack makers can change
 * what formation carriers release without editing code.
 *
 * Config fields:
 *   CARRIER_MELEE_PAYLOAD   = "aw2:soldier"
 *   CARRIER_RANGED_PAYLOAD  = "aw2:archer"
 *   CARRIER_HEAVY_PAYLOAD   = "aw2:elite"
 *   CARRIER_SPECIAL_PAYLOAD = "aw2:leader"
 *   CARRIER_LEVEL_OVERRIDES = {8: "aw2:elite", 10: "aw2:leader"}
 */
public final class UnitCompositionResolver {

    private UnitCompositionResolver() {}

    public static List<SlotPayload> buildSlotPayloads(UnitCard card, int warLevel) {
        int count = card.getSlotCount();
        List<SlotPayload> out = new ArrayList<>(count);

        WeightedPicker<UnitRole> rolePicker = new WeightedPicker<>();
        for (Map.Entry<UnitRole, Integer> e : card.getRoleWeights().entrySet()) {
            rolePicker.add(e.getKey(), e.getValue());
        }

        for (int i = 0; i < count; i++) {
            UnitRole role = rolePicker.pick(i * 9973L + warLevel * 1337L);
            out.add(payloadForRole(role, warLevel, i));
        }

        return out;
    }

    private static SlotPayload payloadForRole(UnitRole role, int warLevel, int idx) {
        if (role == null) role = UnitRole.MELEE;

        // Use config-driven payload lookup
        String payload = RivalCityConfig.getCarrierPayload(warLevel, role.name());
        return new SlotPayload(payload);
    }
}
