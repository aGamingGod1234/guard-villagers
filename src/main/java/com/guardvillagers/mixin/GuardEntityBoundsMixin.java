package com.guardvillagers.mixin;

import com.guardvillagers.entity.ai.GuardCollisionQueries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class GuardEntityBoundsMixin {
	@Inject(method = "setBoundingBox", at = @At("TAIL"))
	private void guardvillagers$updateCollisionBounds(AABB box, CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (entity.level() instanceof ServerLevel) GuardCollisionQueries.boundsChanged(entity);
	}
}
