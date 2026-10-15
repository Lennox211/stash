package de.stashesp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Scannt geladene Chunks (inkrementell, ein Teil pro Tick) nach interessanten Block-Entities
 * und Blöcken. Entities (Rüstungsständer, Loren) werden pro Frame direkt abgefragt.
 *
 * Neue Ziele hinzufügen: einfach in den Sets / in isInterestingState() ergänzen.
 */
public final class TargetScanner {
	/** Ein gefundenes Ziel; red = rot einfärben (Spawner), sonst gelb. */
	public record Target(AABB box, boolean red) { }

	private static final int CHUNKS_PER_TICK = 160;

	/** Block-Entities, die gefunden werden sollen (Kisten, Enderkiste, Trichter, ...). */
	private static final Set<String> BLOCK_ENTITY_IDS = Set.of(
		"minecraft:chest",
		"minecraft:trapped_chest",
		"minecraft:ender_chest",
		"minecraft:barrel",
		"minecraft:shulker_box",
		"minecraft:hopper",
		"minecraft:dispenser",
		"minecraft:dropper"
	);

	/** Entities, die gefunden werden sollen. */
	private static final Set<String> ENTITY_IDS = Set.of(
		"minecraft:armor_stand",
		"minecraft:chest_minecart",
		"minecraft:hopper_minecart"
	);

	private final Map<Long, List<Target>> cache = new HashMap<>();
	private final int[][] offsets = buildOffsets();
	private int cursor = 0;

	public void clear() {
		cache.clear();
		cursor = 0;
	}

	/** Blöcke (keine Block-Entities), die gefunden werden sollen: Schienen + Redstone-Zeug. */
	private static boolean isInterestingState(BlockState s) {
		return s.is(BlockTags.RAILS)
			|| s.is(Blocks.REDSTONE_WIRE)
			|| s.is(Blocks.REPEATER)
			|| s.is(Blocks.COMPARATOR)
			|| s.is(Blocks.OBSERVER)
			|| s.is(Blocks.PISTON)
			|| s.is(Blocks.STICKY_PISTON)
			|| s.is(Blocks.REDSTONE_LAMP)
			|| s.is(Blocks.REDSTONE_TORCH)
			|| s.is(Blocks.REDSTONE_BLOCK)
			|| s.is(Blocks.TNT)
			|| s.is(Blocks.TARGET)
			|| s.is(Blocks.TRIPWIRE_HOOK)
			|| isSpawner(s);
	}

	/** Spawner werden rot gefärbt. */
	private static boolean isSpawner(BlockState s) {
		return s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER);
	}

	private static int[][] buildOffsets() {
		int r = StashEspClient.RANGE_CHUNKS;
		List<int[]> list = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz <= r * r) {
					list.add(new int[] {dx, dz});
				}
			}
		}
		list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
		return list.toArray(new int[0][]);
	}

	private static long key(int cx, int cz) {
		return (cx & 0xffffffffL) | ((long) cz << 32);
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			clear();
			return;
		}
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;

		for (int i = 0; i < CHUNKS_PER_TICK; i++) {
			int[] o = offsets[cursor];
			cursor++;
			if (cursor >= offsets.length) {
				cursor = 0;
				prune(pcx, pcz);
			}
			int cx = pcx + o[0];
			int cz = pcz + o[1];
			ChunkAccess access = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
			if (!(access instanceof LevelChunk chunk)) {
				cache.remove(key(cx, cz));
				continue;
			}
			List<Target> found = scanChunk(level, chunk, cx, cz);
			if (found.isEmpty()) {
				cache.remove(key(cx, cz));
			} else {
				cache.put(key(cx, cz), found);
			}
		}
	}

	private void prune(int pcx, int pcz) {
		int limit = StashEspClient.RANGE_CHUNKS + 2;
		cache.keySet().removeIf(k -> {
			int cx = (int) (long) k;
			int cz = (int) (k >> 32);
			return Math.abs(cx - pcx) > limit || Math.abs(cz - pcz) > limit;
		});
	}

	private List<Target> scanChunk(ClientLevel level, LevelChunk chunk, int cx, int cz) {
		List<Target> out = new ArrayList<>();

		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (BLOCK_ENTITY_IDS.contains(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())))) {
				out.add(new Target(boxOf(level, be.getBlockPos(), be.getBlockState()), false));
			}
		}

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(TargetScanner::isInterestingState)) {
				continue;
			}
			int baseY = minY + i * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (isInterestingState(state)) {
							BlockPos pos = new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z);
							out.add(new Target(boxOf(level, pos, state), isSpawner(state)));
						}
					}
				}
			}
		}
		return out;
	}

	private static AABB boxOf(ClientLevel level, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getShape(level, pos);
		return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
	}

	/** Alle Ziele innerhalb der Reichweite sammeln. */
	public void collect(ClientLevel level, Vec3 origin, List<Target> out) {
		double max = StashEspClient.RANGE_CHUNKS * 16.0;
		double max2 = max * max;

		for (List<Target> list : cache.values()) {
			for (Target t : list) {
				if (t.box().getCenter().distanceToSqr(origin) <= max2) {
					out.add(t);
				}
			}
		}
		for (Entity e : level.entitiesForRendering()) {
			if (ENTITY_IDS.contains(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))) && e.position().distanceToSqr(origin) <= max2) {
				out.add(new Target(e.getBoundingBox(), false));
			}
		}
	}
}
