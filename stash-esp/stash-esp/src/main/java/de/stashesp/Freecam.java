package de.stashesp;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Freecam: Die Kamera wird an eine unsichtbare Client-Entity gehängt, die frei (ohne Kollision)
 * fliegt. Der echte Körper bleibt stehen (Eingabe wird stillgelegt), die Maus dreht aber weiterhin
 * die Blickrichtung; beim Beenden werden Blickrichtung und Kameramodus wiederhergestellt.
 *
 * Steuerung: WASD, Leertaste = hoch, Shift = runter, Sprint = 3x schneller.
 */
public final class Freecam {
	private static final double SPEED = 15.0; // Blöcke pro Sekunde

	private static boolean active;
	private static Entity camEntity;
	private static LocalPlayer savedPlayer;
	private static ClientInput savedInput;
	private static CameraType savedCameraType;
	private static float savedYaw, savedPitch;
	private static double x, y, z; // Augenposition der Kamera
	private static long lastNanos;

	private Freecam() { }

	public static boolean isActive() {
		return active;
	}

	public static void init() {
		// Jeden Frame (vor dem ESP, wegen Registrierungsreihenfolge) die Kamera bewegen.
		LevelExtractionEvents.END_EXTRACTION.register(ctx -> update(Minecraft.getInstance()));
	}

	public static void toggle(Minecraft mc) {
		if (active) {
			disable(mc, true);
		} else {
			enable(mc);
		}
	}

	private static void enable(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			return;
		}
		EntityType<?> markerType = BuiltInRegistries.ENTITY_TYPE
			.getOptional(Identifier.fromNamespaceAndPath("minecraft", "marker")).orElse(null);
		if (markerType == null) {
			return;
		}
		Entity e = markerType.create(level, EntitySpawnReason.COMMAND);
		if (e == null) {
			return;
		}
		camEntity = e;
		savedPlayer = p;
		savedInput = p.input;
		savedCameraType = mc.options.getCameraType();
		savedYaw = p.getYRot();
		savedPitch = p.getXRot();

		Vec3 eye = p.getEyePosition();
		x = eye.x;
		y = eye.y;
		z = eye.z;

		p.input = new ClientInput(); // Körper bleibt stehen
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		lastNanos = System.nanoTime();
		active = true;
		apply(p);
		mc.setCameraEntity(e);
	}

	private static void disable(Minecraft mc, boolean message) {
		LocalPlayer p = mc.player;
		if (p != null && p == savedPlayer) {
			p.input = savedInput;
			p.setYRot(savedYaw);
			p.setXRot(savedPitch);
			if (mc.getCameraEntity() == camEntity) {
				mc.setCameraEntity(p);
			}
		}
		if (savedCameraType != null) {
			mc.options.setCameraType(savedCameraType);
		}
		active = false;
		camEntity = null;
		savedPlayer = null;
		savedInput = null;
	}

	private static void update(Minecraft mc) {
		if (!active) {
			return;
		}
		LocalPlayer p = mc.player;
		// Spieler weg / gestorben / neue Welt / Kamera von außen geändert -> sauber beenden
		if (p == null || mc.level == null || p != savedPlayer || !p.isAlive() || mc.getCameraEntity() != camEntity) {
			disable(mc, false);
			return;
		}

		long now = System.nanoTime();
		double dt = Math.min(0.1, (now - lastNanos) / 1.0e9);
		lastNanos = now;

		double yr = Math.toRadians(p.getYRot());
		double pr = Math.toRadians(p.getXRot());
		double lx = -Math.sin(yr) * Math.cos(pr);
		double ly = -Math.sin(pr);
		double lz = Math.cos(yr) * Math.cos(pr);
		double rx = -Math.cos(yr);
		double rz = -Math.sin(yr);

		Options o = mc.options;
		double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
		double s = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
		double u = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);

		double mx = lx * f + rx * s;
		double my = ly * f + u;
		double mz = lz * f + rz * s;
		double len = Math.sqrt(mx * mx + my * my + mz * mz);
		if (len > 1e-6) {
			double speed = (o.keySprint.isDown() ? SPEED * 3 : SPEED) * dt;
			double k = speed / Math.max(1.0, len);
			x += mx * k;
			y += my * k;
			z += mz * k;
		}
		apply(p);
	}

	private static void apply(LocalPlayer p) {
		camEntity.setPos(x, y - camEntity.getEyeHeight(), z);
		camEntity.setYRot(p.getYRot());
		camEntity.setXRot(p.getXRot());
		camEntity.setOldPosAndRot(); // keine Interpolation -> ruckelfrei pro Frame
	}
}
