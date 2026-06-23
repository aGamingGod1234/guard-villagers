package com.guardvillagers.client;

import net.minecraft.util.Identifier;

public final class GuardSkinResolver {
	private static final Identifier GUARD_TEXTURE = Identifier.of("guardvillagers",
			"textures/entity/guard_villager.png");

	private GuardSkinResolver() {
	}

	public static Identifier resolveTexture(String skinProfileId) {
		if (skinProfileId == null || skinProfileId.isBlank()) {
			return GUARD_TEXTURE;
		}
		// Placeholder for future custom skin profile resolution.
		return GUARD_TEXTURE;
	}
}
