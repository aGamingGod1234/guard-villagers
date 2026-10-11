package com.guardvillagers.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public final class GuardSkinResolver {
	// Guards wear the vanilla villager skin; the guard look comes from the robe and outfit layers drawn over it.
	private static final Identifier VILLAGER_SKIN = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");

	private GuardSkinResolver() {
	}

	public static Identifier resolveTexture(String skinProfileId) {
		// Placeholder for future custom skin profile resolution; every profile currently uses the villager skin.
		return VILLAGER_SKIN;
	}

	/** Called only for skin meshes when the renderer is rebuilt, including after resource reloads. */
	static ModelPart prepareSkinModel(ModelPart root, ResourceManager resources) {
		try (TextureContents contents = TextureContents.load(resources, VILLAGER_SKIN)) {
			skipTransparentParts(root, contents);
		} catch (IOException ignored) {
			// Keep the full mesh if the texture could not be inspected; TextureManager handles the missing texture.
		}
		return root;
	}

	private static void skipTransparentParts(ModelPart root, TextureContents contents) {
		NativeImage image = contents.image();
		// Linear filtering can blend opaque texels across UV boundaries. Retain those meshes conservatively.
		if (contents.blur() || image.getWidth() % 64 != 0 || image.getHeight() % 64 != 0) return;
		ModelPart body = root.getChild("body");
		root.getChild("head").getChild("hat").skipDraw = transparent(image, 32, 0, 64, 18);
		boolean robe = transparent(image, 0, 38, 28, 56);
		body.getChild("robe").skipDraw = robe;
		body.getChild("robe_fitted").skipDraw = robe;
		body.getChild("skirt").skipDraw = transparent(image, 0, 50, 28, 64);
	}

	private static boolean transparent(NativeImage image, int left, int top, int right, int bottom) {
		int scaleX = image.getWidth() / 64;
		int scaleY = image.getHeight() / 64;
		for (int y = top * scaleY; y < bottom * scaleY; y++) {
			for (int x = left * scaleX; x < right * scaleX; x++) {
				if ((image.getPixel(x, y) >>> 24) != 0) return false;
			}
		}
		return true;
	}
}
