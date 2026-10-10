package com.guardvillagers.client;

import net.minecraft.resources.Identifier;

public final class GuardSkinResolver {
	// Guards wear the vanilla villager skin; the guard look comes from the robe and outfit layers drawn over it.
	private static final Identifier VILLAGER_SKIN = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");

	private GuardSkinResolver() {
	}

	public static Identifier resolveTexture(String skinProfileId) {
		// Placeholder for future custom skin profile resolution; every profile currently uses the villager skin.
		return VILLAGER_SKIN;
	}
}
