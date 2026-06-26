package studio.ERM.war.map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import studio.ERM.EpochRunnerMod;

/**
 * Client-side handler for the Tactical War Map keybind.
 * Press M to open the map. Auto-registered on the client event bus.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID, value = Side.CLIENT)
public class TacticalWarMapClient {

    private static final String CATEGORY = "key.categories.homosapien";
    public static final KeyBinding KEY_OPEN_MAP = new KeyBinding("key.war.tactical_map", Keyboard.KEY_M, CATEGORY);

    private static boolean registered = false;

    /**
     * Call during client-side init to register the keybind.
     */
    public static void registerKeybind() {
        if (!registered) {
            ClientRegistry.registerKeyBinding(KEY_OPEN_MAP);
            registered = true;
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        if (KEY_OPEN_MAP.isPressed()) {
            // Real-logger trace (System.out does NOT reach latest.log in this runtime): proves the
            // M keybind actually fires and the GUI is being opened. If this line is absent from the
            // log when the user presses M, the keybind itself (conflict / not registered) is the bug.
            EpochRunnerMod.logger.info("[ERM-Map] M key pressed -> opening GuiTacticalWarMap");
            mc.displayGuiScreen(new GuiTacticalWarMap());
        }
    }
}
