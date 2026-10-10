package com.guardvillagers.command.hierarchy;

import com.guardvillagers.command.commander.CommanderService;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Single place the chain of command, the order pipeline and the commander brain hook into the game. One call from
 * {@code GuardVillagersMod.onInitialize}.
 */
public final class CommandModule {
	private static boolean initialized;

	private CommandModule() {
	}

	public static synchronized void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		GuardHierarchy.setListener(CommanderService::onCommandChange);
		com.guardvillagers.command.commander.VoiceCommandBridge.init();
		// One pass per server tick for all owners (elections, then each owner's brain on its staggered cadence).
		ServerTickEvents.END_SERVER_TICK.register(CommanderService::tick);
		ServerPlayerEvents.LEAVE.register(player -> CommanderService.forget(player.level().getServer(), player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CommanderService.clearSessionState());
	}
}
