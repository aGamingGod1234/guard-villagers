package com.guardvillagers.mixin;

import com.guardvillagers.GuardReputationManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerEntityMixin {
	@Inject(method = "rewardTradeXp", at = @At("TAIL"))
	private void guardvillagers$recordCompletedVillagerTrade(MerchantOffer offer, CallbackInfo ci) {
		Villager villager = (Villager) (Object) this;
		if (villager.level().isClientSide()) {
			return;
		}

		Player customer = ((Merchant) villager).getTradingPlayer();
		if (customer instanceof ServerPlayer serverPlayer) {
			GuardReputationManager.recordCompletedTrade(serverPlayer, villager);
		}
	}
}
