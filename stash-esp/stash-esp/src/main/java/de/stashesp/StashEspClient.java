package de.stashesp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class StashEspClient implements ClientModInitializer {
	public static final String MOD_ID = "stashesp";

	/** Reichweite in Chunks (32 Chunks = 512 Blöcke). */
	public static final int RANGE_CHUNKS = 32;

	public static volatile boolean enabled = true;
	public static final TargetScanner SCANNER = new TargetScanner();

	static KeyMapping toggleKey;
	static KeyMapping freecamKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.stashesp.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

		freecamKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.stashesp.freecam", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		Freecam.init(); // vor EspRenderer registrieren
		HudOverlay.init();
		EspRenderer.init();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> EspRenderer.close());
	}

	private void onTick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			enabled = !enabled;
		}
		while (freecamKey.consumeClick()) {
			Freecam.toggle(mc);
		}
		if (enabled) {
			SCANNER.tick(mc);
		} else {
			SCANNER.clear();
		}
	}
}
