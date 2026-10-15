package de.stashesp;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Zeigt oben links, welche Funktionen an/aus sind (inkl. der aktuell belegten Taste). */
public final class HudOverlay {
	private static final int GREEN = 0xFF55FF55;
	private static final int GRAY = 0xFFAAAAAA;

	private HudOverlay() { }

	public static void init() {
		HudElementRegistry.addLast(StashEspClient.id("status"), HudOverlay::extract);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		int y = 4;
		line(graphics, mc, y, "Stash ESP", StashEspClient.toggleKey, StashEspClient.enabled);
		y += 11;
		line(graphics, mc, y, "Freecam", StashEspClient.freecamKey, Freecam.isActive());
	}

	private static void line(GuiGraphicsExtractor graphics, Minecraft mc, int y,
			String name, KeyMapping key, boolean on) {
		Component text = Component.literal(name + " [")
			.append(key.getTranslatedKeyMessage())
			.append("]: " + (on ? "AN" : "AUS"));
		graphics.text(mc.font, text, 4, y, on ? GREEN : GRAY, true);
	}
}
