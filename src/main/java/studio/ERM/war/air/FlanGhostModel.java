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
            // NOTE: ModelRendererTurbo.render multiplies each rotateAngle by 57.29578 (rad->deg) before
            // GlStateManager.rotate, so these angle fields are RADIANS. 0.6 rad/tick (~34 deg/tick) is a
            // fast, clearly visible spin -- tune this single constant to taste, same value = same speed.
            float spin = ((float) ghost.ticksExisted + pt) * 0.6F;
            // ROTORS: render(DriveableType) draws heli rotor parts via renderPart WITHOUT setting any angle,
            // so pre-setting their rotateAngle survives and each part spins about its own rotationPoint.
            // Main rotor spins about Y (vertical); tail rotor about Z (lateral) -- axes per RenderPlane.
            if (mp.heliMainRotorModels != null) {
                for (com.flansmod.client.tmt.ModelRendererTurbo[] arr : mp.heliMainRotorModels) {
                    if (arr == null) continue;
                    for (com.flansmod.client.tmt.ModelRendererTurbo part : arr) {
                        if (part != null) part.rotateAngleY = spin;
                    }
                }
            }
            if (mp.heliTailRotorModels != null) {
                for (com.flansmod.client.tmt.ModelRendererTurbo[] arr : mp.heliTailRotorModels) {
                    if (arr == null) continue;
                    for (com.flansmod.client.tmt.ModelRendererTurbo part : arr) {
                        if (part != null) part.rotateAngleZ = spin;
                    }
                }
            }
            // Static model render -- no EntityPlane needed, nothing spawned. (PlaneType is a DriveableType,
            // so this resolves to ModelPlane.render(DriveableType): the entity-free model pass.) This pass
            // OVERWRITES every propellerModels part's rotateAngleX with a static fan-spread, so props are
            // re-spun in a second pass below.
            mp.render(type);
            // PROPELLERS: render(type) just overwrote rotateAngleX with a fan-spread, so set our spin (X axis)
            // and redraw ONLY the propeller blades over the top via the public renderPart pass.
            if (mp.propellerModels != null) {
                for (com.flansmod.client.tmt.ModelRendererTurbo[] arr : mp.propellerModels) {
                    if (arr == null || arr.length == 0) continue;
                    for (com.flansmod.client.tmt.ModelRendererTurbo part : arr) {
                        if (part != null) part.rotateAngleX = spin;
                    }
                    mp.renderPart(arr);
                }
            }
        } catch (Throwable t) {
            GlStateManager.popMatrix();
            return false;
        }
        GlStateManager.popMatrix();
        return true;
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
