package com.guardvillagers;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/** Server-wide ownership policy, including guards parked in other dimensions. */
public final class GuardGameRules {
	public static final int DEFAULT_MAX_PERSONAL_GUARDS = 0;
	public static final GameRule<Integer> MAX_PERSONAL_GUARDS = GameRuleBuilder
			.forInteger(DEFAULT_MAX_PERSONAL_GUARDS).minValue(0).category(GameRuleCategory.MOBS)
			.buildAndRegister(Identifier.fromNamespaceAndPath(GuardVillagersMod.MOD_ID, "max_personal_guards"));

	private GuardGameRules() { }

	public static void initialize() { }

	public static int personalGuardLimit(ServerLevel level) {
		return level.getServer().overworld().getGameRules().get(MAX_PERSONAL_GUARDS);
	}

	public static String displayLimit(ServerLevel level) {
		int limit = personalGuardLimit(level);
		return limit == 0 ? "?" : Integer.toString(limit);
	}
}
