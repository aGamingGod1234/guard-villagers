package com.guardvillagers.command.commander;

import java.util.Locale;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;

/** One hostile the commander knows about, scored for assignment. Rebuilt every snapshot. */
public final class Threat {
	public enum Kind {
		MELEE(10, 2),
		RANGED(12, 2),
		CREEPER(14, 1),
		RAIDER(13, 2),
		BOSS(30, Integer.MAX_VALUE);

		private final int weight;
		private final int guardsNeeded;

		Kind(int weight, int guardsNeeded) {
			this.weight = weight;
			this.guardsNeeded = guardsNeeded;
		}

		public int weight() {
			return this.weight;
		}

		public int guardsNeeded() {
			return this.guardsNeeded;
		}

		public static Kind of(LivingEntity entity) {
			if (entity instanceof WitherBoss || entity instanceof EnderDragon || entity instanceof Warden) {
				return BOSS;
			}
			if (entity instanceof Creeper) {
				return CREEPER;
			}
			if (entity instanceof Raider) {
				return RAIDER;
			}
			if (entity instanceof RangedAttackMob || entity.getMainHandItem().is(Items.BOW)
					|| entity.getMainHandItem().is(Items.CROSSBOW) || entity.getMainHandItem().is(Items.TRIDENT)) {
				return RANGED;
			}
			return MELEE;
		}
	}

	/** 8-way compass, 0 = north (-Z), clockwise. */
	static final String[] SECTORS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

	final LivingEntity entity;
	final Kind kind;
	final double ownerDistanceSq;
	final int sector;
	int need;
	int assigned;
	int score;
	/** Sum and count of health ratios of the guards fighting it, for the reinforce rule. */
	float engagedHealth;
	int engagedCount;

	Threat(LivingEntity entity, double ownerDistanceSq, int sector) {
		this.entity = entity;
		this.kind = Kind.of(entity);
		this.ownerDistanceSq = ownerDistanceSq;
		this.sector = sector;
		this.need = this.kind.guardsNeeded();
	}

	public LivingEntity entity() {
		return this.entity;
	}

	public Kind kind() {
		return this.kind;
	}

	public String sectorName() {
		return SECTORS[this.sector];
	}

	/** Lower-case display name, e.g. "zombie". */
	String noun() {
		return this.entity.getType().getDescription().getString().toLowerCase(Locale.ROOT);
	}

	boolean isMob() {
		return this.entity instanceof Mob;
	}

	/** Compass bucket of {@code (dx, dz)} seen from the reference point. */
	static int sectorOf(double dx, double dz) {
		double degrees = Math.toDegrees(Math.atan2(dx, -dz));
		return Math.floorMod((int) Math.round(degrees / 45.0D), 8);
	}
}
