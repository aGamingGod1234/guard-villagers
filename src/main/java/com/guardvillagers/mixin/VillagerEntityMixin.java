package com.guardvillagers.mixin;

import com.guardvillagers.GuardReputationManager;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.village.Merchant;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {
	@Inject(method = "afterUsing", at = @At("TAIL"))
	private void guardvillagers$recordCompletedVillagerTrade(TradeOffer offer, CallbackInfo ci) {
		VillagerEntity villager = (VillagerEntity) (Object) this;
		if (villager.getEntityWorld().isClient()) {
			return;
		}

		PlayerEntity customer = ((Merchant) villager).getCustomer();
		if (customer instanceof ServerPlayerEntity serverPlayer) {
			GuardReputationManager.recordCompletedTrade(serverPlayer, villager);
		}
	}
}
