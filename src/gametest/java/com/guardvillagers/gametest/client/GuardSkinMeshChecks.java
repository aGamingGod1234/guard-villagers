package com.guardvillagers.gametest.client;

import com.guardvillagers.client.GuardEntityModel;
import com.guardvillagers.client.GuardSkinResolver;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;

/** Check actual texture UV regions, HD packs, filtering, and renderer-reload reconstruction. */
final class GuardSkinMeshChecks {
	static void run(Minecraft client) {
		try {
			Method prepare = GuardSkinResolver.class.getDeclaredMethod("prepareSkinModel", ModelPart.class,
					net.minecraft.server.packs.resources.ResourceManager.class);
			prepare.setAccessible(true);
			ModelPart vanilla = mesh();
			prepare.invoke(null, vanilla, client.getResourceManager());
			check(vanilla, true, true, true, "vanilla transparent skin");
			vanilla.getAllParts().forEach(ModelPart::resetPose);
			check(vanilla, true, true, true, "animation reset retains optimization");
			check(mesh(), false, false, false, "clothing and armor meshes are unchanged");
			for (boolean chestArmor : new boolean[]{false, true}) {
				int saved = vertices(mesh(), chestArmor) - vertices(vanilla, chestArmor);
				if (saved != 72) throw new AssertionError("Expected 72 fewer skin vertices, saved " + saved);
			}

			Method inspect = GuardSkinResolver.class.getDeclaredMethod("skipTransparentParts", ModelPart.class, TextureContents.class);
			inspect.setAccessible(true);
			for (int scale : new int[]{1, 2}) {
				try (TextureContents contents = new TextureContents(new NativeImage(64 * scale, 64 * scale, true), null)) {
					// A resource pack can paint even one translucent texel at the far edge of a region.
					ModelPart transparent = mesh();
					inspect.invoke(null, transparent, contents);
					check(transparent, true, true, true, "transparent HD mesh");
					contents.image().setPixel(64 * scale - 1, 18 * scale - 1, 0x01000000);
					contents.image().setPixel(28 * scale - 1, 56 * scale - 1, 0x01000000);
					contents.image().setPixel(28 * scale - 1, 64 * scale - 1, 0x01000000);
					ModelPart painted = mesh();
					inspect.invoke(null, painted, contents);
					check(painted, false, false, false, "painted pack restored on rebuild");
				}
			}
			try (TextureContents blurred = new TextureContents(new NativeImage(64, 64, true),
					new TextureMetadataSection(true, false, MipmapStrategy.AUTO, 0))) {
				ModelPart root = mesh();
				inspect.invoke(null, root, blurred);
				check(root, false, false, false, "linear filtering keeps full geometry");
			}
			try (TextureContents unusual = new TextureContents(new NativeImage(65, 64, true), null)) {
				ModelPart root = mesh();
				inspect.invoke(null, root, unusual);
				check(root, false, false, false, "unusual texture dimensions keep full geometry");
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Cannot inspect skin mesh", exception);
		}
		System.out.println("GUARD_CLIENT_SKIN_CHECKS passed: 72 fewer vertices/guard, vanilla, animation reset, painted/HD packs, blur, dimensions");
	}

	private static int vertices(ModelPart root, boolean chestArmor) {
		root.getChild("body").getChild("robe").visible = !chestArmor;
		root.getChild("body").getChild("robe_fitted").visible = chestArmor;
		try (ByteBufferBuilder buffer = new ByteBufferBuilder(16384)) {
			BufferBuilder builder = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.ENTITY);
			root.render(new PoseStack(), builder, 0, 0);
			try (var mesh = builder.buildOrThrow()) {
				return mesh.drawState().vertexCount();
			}
		}
	}

	private static ModelPart mesh() {
		return GuardEntityModel.getTexturedModelData().bakeRoot();
	}

	private static void check(ModelPart root, boolean hat, boolean robe, boolean skirt, String message) {
		ModelPart body = root.getChild("body");
		if (root.getChild("head").getChild("hat").skipDraw != hat
				|| body.getChild("robe").skipDraw != robe || body.getChild("robe_fitted").skipDraw != robe
				|| body.getChild("skirt").skipDraw != skirt) throw new AssertionError(message);
	}
}
