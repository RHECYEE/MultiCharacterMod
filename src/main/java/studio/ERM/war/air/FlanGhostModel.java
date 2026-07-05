package studio.ERM.war.air;

import com.flansmod.client.handlers.FlansModResourceHandler;
import com.flansmod.client.model.ModelPlane;
import com.flansmod.common.driveables.PlaneType;
import com.flansmod.common.types.InfoType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * ISOLATED real-Flan-model renderer for ghost aircraft. Kept SEPARATE from the registered renderer
 * ({@link RenderGhostAircraftSafe}) so that if any Flan class here fails to load/resolve, only THIS
 * class fails -- the box renderer (no Flan imports) still draws, so aircraft are never invisible.
 *
 * APPROACH (the SAFE one the user picked): draw the real model with Flan's STATIC model render --
 * {@code ModelPlane.render(DriveableType)} -- which only renders the model's own part arrays (nose,
 * wings, tail, propellers, rotors) and touches NO EntityPlane, wheels or seats. So there is NO live
 * driveable, NO client-tick dummy, and NO orphan wheel/seat entities spawned into the world (the old
 * dummy-EntityPlane path called a constructor that spawned EntityWheel/EntitySeat via World.spawnEntity
 * every build -- that litter is gone). Zero server-crash risk: nothing is ever spawned. The plane is
 * "dragged across the sky" because the {@link EntityGhostAircraft} carrying it moves; we just paint its
 * real model on top each frame. Planes AND helicopters are Flan {@code PlaneType}, so both draw.
 */
@SideOnly(Side.CLIENT)
public final class FlanGhostModel {

    private FlanGhostModel() {}

    /** Draw the real model in a static pose at the ghost's transform. Return false (-> box) on any failure. */
    public static boolean render(EntityGhostAircraft ghost, double x, double y, double z, float pt) {
        PlaneType type = resolve(ghost);
        if (type == null || type.model == null || !(type.model instanceof ModelPlane)) return false;

        bindTexture(getTexture(type));

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        float yaw = ghost.prevRotationYaw + (ghost.rotationYaw - ghost.prevRotationYaw) * pt;
        // Model offset (-90) folded into the yaw rotation so the airframe points along its flight path,
        // then pitch (climb/dive) and roll (bank) on the local axes -- same orientation the entity render used.
        GlStateManager.rotate(180F - yaw - 90F, 0F, 1F, 0F);
        GlStateManager.rotate(ghost.getPitchAngle(), 0F, 0F, 1F);
        GlStateManager.rotate(ghost.getBankAngle(), 1F, 0F, 0F);
        float sc = type.modelScale <= 0F ? 1F : type.modelScale;
        GlStateManager.scale(sc, sc, sc);
        GlStateManager.color(1F, 1F, 1F, 1F);
        try {
            ModelPlane mp = (ModelPlane) type.model;
            // One constant spin angle for ALL props/rotors, advanced by entity age + partial tick.
            // ModelRendererTurbo.render multiplies each rotateAngle by 57.29578 (rad->deg) before
            // GlStateManager.rotate, so these fields are RADIANS. ~0.6 rad/tick is a fast, visible spin.
            float spin = ((float) ghost.ticksExisted + pt) * 0.6F;
            boolean heli = isHeliName(ghost.getAircraftType());

            // Draw the STATIC model FIRST. render(type) sets every prop/rotor part's angle to a fixed
            // fan-spread internally, so setting our spin BEFORE it gets overwritten -- THAT is why the
            // Apache/EC665 main rotors weren't moving (the heli pass used to run before this). Spin AFTER,
            // then redraw just the spinning parts on top.
            mp.render(type);

            // Main rotor about Y (vertical), tail rotor about Z. Some heli models (Apache/EC665) store the
            // MAIN rotor in propellerModels, so spin those about Y on a heli (about X on a fixed-wing prop).
            // Multi-part blades are fanned evenly so 3-4 distinct blades show.
            spinParts(mp, mp.heliMainRotorModels, 'Y', spin);
            spinParts(mp, mp.heliTailRotorModels, 'Z', spin);
            spinParts(mp, mp.propellerModels, heli ? 'Y' : 'X', spin);
        } catch (Throwable t) {
            GlStateManager.popMatrix();
            return false;
        }
        GlStateManager.popMatrix();
        return true;
    }

    /**
     * Spin a set of rotor/prop part GROUPS about an axis and redraw them on top of the static model so the
     * blades visibly turn (the static {@code ModelPlane.render} freezes them). Each group's parts are fanned
     * evenly (j*2PI/len) so a multi-part rotor shows its distinct blades. {@code rotateAngle*} are RADIANS
     * (TMT multiplies by 57.29578 before the GL rotate). Null-safe; per-part failures are swallowed.
     */
    private static void spinParts(ModelPlane mp, com.flansmod.client.tmt.ModelRendererTurbo[][] groups,
                                  char axis, float spin) {
        if (groups == null) return;
        for (com.flansmod.client.tmt.ModelRendererTurbo[] group : groups) {
            if (group == null) continue;
            int n = group.length;
            for (int j = 0; j < n; j++) {
                com.flansmod.client.tmt.ModelRendererTurbo part = group[j];
                if (part == null) continue;
                float a = spin + (n > 1 ? (float) (j * 2.0 * Math.PI / n) : 0F); // fan distinct blades
                if (axis == 'X') part.rotateAngleX = a;
                else if (axis == 'Z') part.rotateAngleZ = a;
                else part.rotateAngleY = a;
                try { part.render(0.0625F); } catch (Throwable ignored) {}
            }
        }
    }

    /** True if the aircraft ShortName is a HELICOPTER (rotor model), per the same set WarAirstrikeHelper uses. */
    private static boolean isHeliName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("apache") || n.contains("cobra") || n.contains("tiger") || n.contains("ec665")
                || n.contains("hind") || n.contains("blackhawk") || n.contains("chinook")
                || n.contains("littlebird");
    }

    private static PlaneType resolve(EntityGhostAircraft ghost) {
        String name = ghost.getAircraftType();
        if (name == null) return null;
        if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
        name = name.trim();
        if (name.isEmpty()) return null;
        try { PlaneType t = PlaneType.getPlane(name); if (t != null) return t; } catch (Throwable ignored) {}
        try {
            InfoType it = InfoType.getType(name);
            if (it instanceof PlaneType) return (PlaneType) it;
        } catch (Throwable ignored) {}
        // Case-insensitive fallback: spawn ids are lower-cased ("flansmod:bf109"), but pack ShortNames
        // are mixed-case ("BF109", "ApacheAH64"), so an exact getPlane() miss is expected -- match here.
        try {
            if (InfoType.infoTypes != null) {
                for (InfoType it : InfoType.infoTypes.values()) {
                    if (it instanceof PlaneType && it.shortName != null && it.shortName.equalsIgnoreCase(name)) return (PlaneType) it;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ResourceLocation getTexture(PlaneType type) {
        try {
            ResourceLocation rl = FlansModResourceHandler.getTexture(type); // PlaneType is an InfoType
            if (rl != null) return rl;
        } catch (Throwable ignored) {}
        return TextureMap.LOCATION_MISSING_TEXTURE;
    }

    private static void bindTexture(ResourceLocation rl) {
        try { Minecraft.getMinecraft().getTextureManager().bindTexture(rl); } catch (Throwable ignored) {}
    }
}
