package de.stashesp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.world.entity.Entity;
import de.stashesp.TargetScanner.Target;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Zeichnet gelbe Umrandungen + Tracer-Linien OHNE Tiefentest (also durch Wände).
 * Basiert auf dem offiziellen Fabric-Docs-Beispiel "CustomRenderPipeline".
 * Linien werden als dünne Quads gezeichnet (Kanten = dünne Boxen, Tracer = Billboard-Quad),
 * Dicke wächst mit der Entfernung, damit sie auch weit weg sichtbar bleiben.
 */
public final class EspRenderer {
	private static final int MAX_OUTLINES = 256;
	private static final int MAX_TRACERS = 64;
	private static final double SCREEN_THICKNESS = 0.0012; // Linienbreite pro Block Entfernung
	private static final float EDGE_MIN = 0.02f;
	private static final float EDGE_MAX = 0.15f;

	// aktuelle Farbe (wird pro Ziel gesetzt): gelb = (1,1,0), Spawner rot = (1,0,0)
	private static float R = 1f, G = 1f, B = 0f;
	private static final float A = 1f;

	private static final RenderPipeline PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(StashEspClient.id("pipeline/esp_through_walls"))
			.withDepthStencilState(Optional.empty())
			.build()
	);

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	private static final StagedVertexBuffer BUFFER =
		new StagedVertexBuffer(() -> "Stash ESP Buffer", 4 * 1024 * 1024);

	private record Candidate(Target target, double dist2) { }

	private static List<Target> targets = List.of();
	private static Vec3 lookDir = Vec3.ZERO;

	private EspRenderer() { }

	public static void init() {
		LevelExtractionEvents.END_EXTRACTION.register(EspRenderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(EspRenderer::draw);
	}

	public static void close() {
		BUFFER.close();
	}

	// ---------------------------------------------------------------- Extraktion

	private static void extract(LevelExtractionContext context) {
		targets = List.of();
		if (!StashEspClient.enabled) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Entity camEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : player;
		Vec3 eye = camEntity.getEyePosition(partial);
		lookDir = camEntity.getViewVector(partial);

		List<Target> raw = new ArrayList<>();
		StashEspClient.SCANNER.collect(level, eye, raw);
		if (raw.isEmpty()) {
			return;
		}
		List<Candidate> cands = new ArrayList<>(raw.size());
		for (Target t : raw) {
			cands.add(new Candidate(t, t.box().getCenter().distanceToSqr(eye)));
		}
		// Spawner (rot) zuerst, danach nach Entfernung
		cands.sort(Comparator.<Candidate>comparingInt(c -> c.target().red() ? 0 : 1)
			.thenComparingDouble(Candidate::dist2));

		int n = Math.min(MAX_OUTLINES, cands.size());
		List<Target> result = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			result.add(cands.get(i).target());
		}
		targets = result; // nach Entfernung sortiert (nah -> fern)
	}

	// ---------------------------------------------------------------- Zeichnen

	private static void draw(LevelRenderContext context) {
		List<Target> list = targets;
		if (list.isEmpty()) {
			return;
		}
		VertexFormat format = PIPELINE.getVertexFormatBinding(0);
		assert format != null;
		PrimitiveTopology primitive = PIPELINE.getPrimitiveTopology();

		StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, primitive,
			primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

		build(context, draw, list);

		BUFFER.upload();
		StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
		if (info != null) {
			execute(Minecraft.getInstance(), info);
		}
		BUFFER.endFrame();
	}

	private static void build(LevelRenderContext context, StagedVertexBuffer.Draw draw, List<Target> list) {
		PoseStack matrices = context.poseStack();
		Vec3 cam = context.levelState().cameraRenderState.pos;
		VertexConsumer b = BUFFER.getVertexBuilder(draw);

		matrices.pushPose();
		Matrix4fc m = matrices.last().pose();

		// Tracer starten kurz vor der Kamera in Blickrichtung (= Fadenkreuz)
		Vec3 start = lookDir.scale(0.6);

		for (int i = 0; i < list.size(); i++) {
			Target target = list.get(i);
			AABB box = target.box();
			R = 1f;
			G = target.red() ? 0f : 1f;
			B = 0f;
			// Koordinaten relativ zur Kamera (genauer als große Weltkoordinaten als float)
			double x0 = box.minX - cam.x, y0 = box.minY - cam.y, z0 = box.minZ - cam.z;
			double x1 = box.maxX - cam.x, y1 = box.maxY - cam.y, z1 = box.maxZ - cam.z;
			Vec3 center = new Vec3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);

			float t = (float) Math.min(EDGE_MAX, Math.max(EDGE_MIN, center.length() * SCREEN_THICKNESS));
			outline(m, b, (float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, t);

			if (i < MAX_TRACERS) {
				tracer(m, b, start, center);
			}
		}
		matrices.popPose();
	}

	private static void outline(Matrix4fc m, VertexConsumer b,
			float x0, float y0, float z0, float x1, float y1, float z1, float t) {
		for (int a = 0; a < 2; a++) {
			float y = a == 0 ? y0 : y1;
			for (int c = 0; c < 2; c++) {
				float z = c == 0 ? z0 : z1;
				box(m, b, x0 - t, y - t, z - t, x1 + t, y + t, z + t); // Kanten entlang X
			}
		}
		for (int a = 0; a < 2; a++) {
			float x = a == 0 ? x0 : x1;
			for (int c = 0; c < 2; c++) {
				float z = c == 0 ? z0 : z1;
				box(m, b, x - t, y0 - t, z - t, x + t, y1 + t, z + t); // Kanten entlang Y
			}
		}
		for (int a = 0; a < 2; a++) {
			float x = a == 0 ? x0 : x1;
			for (int c = 0; c < 2; c++) {
				float y = c == 0 ? y0 : y1;
				box(m, b, x - t, y - t, z0 - t, x + t, y + t, z1 + t); // Kanten entlang Z
			}
		}
	}

	/** Linie als Billboard-Quad (beidseitig), Breite proportional zur Entfernung. */
	private static void tracer(Matrix4fc m, VertexConsumer b, Vec3 a, Vec3 c) {
		Vec3 dir = c.subtract(a);
		Vec3 mid = a.add(c).scale(0.5);
		Vec3 side = dir.cross(mid);
		double len = side.length();
		if (len < 1e-6) {
			return;
		}
		side = side.scale(1.0 / len);
		double wa = Math.max(0.0007, a.length() * SCREEN_THICKNESS);
		double wc = Math.max(0.0007, c.length() * SCREEN_THICKNESS);

		Vec3 a1 = a.subtract(side.scale(wa)), a2 = a.add(side.scale(wa));
		Vec3 c1 = c.subtract(side.scale(wc)), c2 = c.add(side.scale(wc));

		vertex(m, b, a1); vertex(m, b, a2); vertex(m, b, c2); vertex(m, b, c1);
		vertex(m, b, c1); vertex(m, b, c2); vertex(m, b, a2); vertex(m, b, a1);
	}

	private static void vertex(Matrix4fc m, VertexConsumer b, Vec3 v) {
		b.addVertex(m, (float) v.x, (float) v.y, (float) v.z).setColor(R, G, B, A);
	}

	private static void v(Matrix4fc m, VertexConsumer b, float x, float y, float z) {
		b.addVertex(m, x, y, z).setColor(R, G, B, A);
	}

	/** Gefüllte Box (Face-Reihenfolge wie im Fabric-Docs-Beispiel). */
	private static void box(Matrix4fc m, VertexConsumer b,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		v(m, b, minX, minY, maxZ); v(m, b, maxX, minY, maxZ); v(m, b, maxX, maxY, maxZ); v(m, b, minX, maxY, maxZ);
		v(m, b, maxX, minY, minZ); v(m, b, minX, minY, minZ); v(m, b, minX, maxY, minZ); v(m, b, maxX, maxY, minZ);
		v(m, b, minX, minY, minZ); v(m, b, minX, minY, maxZ); v(m, b, minX, maxY, maxZ); v(m, b, minX, maxY, minZ);
		v(m, b, maxX, minY, maxZ); v(m, b, maxX, minY, minZ); v(m, b, maxX, maxY, minZ); v(m, b, maxX, maxY, maxZ);
		v(m, b, minX, maxY, maxZ); v(m, b, maxX, maxY, maxZ); v(m, b, maxX, maxY, minZ); v(m, b, minX, maxY, minZ);
		v(m, b, minX, minY, minZ); v(m, b, maxX, minY, minZ); v(m, b, maxX, minY, maxZ); v(m, b, minX, minY, maxZ);
	}

	private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info) {
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
		GpuTextureView colorTexture = mainTarget.getColorTextureView();
		assert colorTexture != null;

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> StashEspClient.MOD_ID + " esp", colorTexture, Optional.empty(),
				mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", dynamicTransforms);
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}
}
