package com.guardvillagers.entity.ai;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.AABB;

/** Exact live bounds for guard collision/pushing queries; ordinary AI queries retain vanilla ordering. */
public final class GuardCollisionQueries {
	private static final Map<ServerLevel, State> LEVELS = new IdentityHashMap<>();

	static {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LEVELS.clear());
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			State state = LEVELS.get(level);
			if (state != null && state.tick == level.getGameTime()) state.update(entity);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			State state = LEVELS.get(level);
			if (state != null) state.index.remove(entity);
		});
	}

	private static final class State {
		final EntitySpatialIndex<Entity> index = new EntitySpatialIndex<>();
		long tick = Long.MIN_VALUE;

		void update(Entity entity) {
			AABB box = entity.getBoundingBox();
			this.index.update(entity, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
		}

		void refresh(ServerLevel level) {
			if (this.tick == level.getGameTime()) return;
			this.tick = level.getGameTime();
			this.index.beginRefresh();
			for (Entity entity : level.getAllEntities()) this.update(entity);
			this.index.endRefresh();
		}
	}

	private GuardCollisionQueries() {}

	/** Called after all bounds changes, including movement, teleports, pose changes and resizing. */
	public static void boundsChanged(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) return;
		State state = LEVELS.get(level);
		if (state != null && state.tick == level.getGameTime() && state.index.contains(entity)) state.update(entity);
	}

	public static List<Entity> query(ServerLevel level, Entity except, AABB box, Predicate<? super Entity> selector) {
		State state = LEVELS.computeIfAbsent(level, ignored -> new State());
		state.refresh(level);
		List<Entity> result = new ArrayList<>();
		// Match vanilla's accessible-section envelope as well as the exact bounds, including
		// its treatment of oversized entities whose origin lies outside the queried sections.
		int minX = SectionPos.posToSectionCoord(box.minX - 2), maxX = SectionPos.posToSectionCoord(box.maxX + 2);
		int minY = SectionPos.posToSectionCoord(box.minY - 4), maxY = SectionPos.posToSectionCoord(box.maxY);
		int minZ = SectionPos.posToSectionCoord(box.minZ - 2), maxZ = SectionPos.posToSectionCoord(box.maxZ + 2);
		state.index.query(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
				entity -> entity != except
						&& (entity.getBlockX() >> 4) >= minX && (entity.getBlockX() >> 4) <= maxX
						&& (entity.getBlockY() >> 4) >= minY && (entity.getBlockY() >> 4) <= maxY
						&& (entity.getBlockZ() >> 4) >= minZ && (entity.getBlockZ() >> 4) <= maxZ
						&& selector.test(entity), result);
		// Parts have their own bounds and are not in the level's regular entity storage.
		for (EnderDragonPart part : level.dragonParts()) {
			if (part != except && part.parentMob != except && selector.test(part) && box.intersects(part.getBoundingBox())) result.add(part);
		}
		return result;
	}
}
