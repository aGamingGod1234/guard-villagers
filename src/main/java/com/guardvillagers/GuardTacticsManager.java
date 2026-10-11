package com.guardvillagers;

import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

public final class GuardTacticsManager {
	private GuardTacticsManager() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof GuardEntity guard && guard.getOwnerUuid() != null) {
				GuardTacticsState.PlayerTactics tactics = getState(level.getServer()).getOrDefault(guard.getOwnerUuid());
				int row = guard.getGroupIndex();
				if (row >= 0 && row < tactics.groupCount()) {
					guard.setGroupName(tactics.getGroupName(row));
					guard.updateGroupNameplate();
				}
			}
		});
	}

	public static GuardTacticsState getState(MinecraftServer server) {
		MinecraftServer checkedServer = Objects.requireNonNull(server, "server");
		if (checkedServer.overworld() == null) {
			throw new IllegalStateException("Cannot access guard tactics state before overworld is available");
		}
		return checkedServer.getDataStorage().computeIfAbsent(GuardTacticsState.TYPE);
	}
}
