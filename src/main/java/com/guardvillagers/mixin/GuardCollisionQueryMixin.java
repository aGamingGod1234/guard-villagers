package com.guardvillagers.mixin;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardCollisionQueries;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class GuardCollisionQueryMixin {
	@Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
	private void guardvillagers$collisionCandidates(Entity except, AABB box, Predicate<? super Entity> selector,
			CallbackInfoReturnable<List<Entity>> cir) {
		if (except instanceof GuardEntity && (Object) this instanceof ServerLevel level) {
			cir.setReturnValue(GuardCollisionQueries.query(level, except, box, selector));
		}
	}
}
