package com.guardvillagers.entity;

import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardPlayerUpgrades;
import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.goal.CrowdControlGoal;
import com.guardvillagers.entity.goal.ElectLeaderGoal;
import com.guardvillagers.entity.goal.FormationFollowOwnerGoal;
import com.guardvillagers.entity.goal.GuardBowAttackGoal;
import com.guardvillagers.entity.goal.PerimeterPatrolGoal;
import com.guardvillagers.entity.goal.RaidTacticsGoal;
import com.guardvillagers.entity.goal.ReturnToLandGoal;
import com.guardvillagers.entity.goal.TacticalRetreatGoal;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.guardvillagers.entity.projectile.GuardArrowEntity;

public class GuardEntity extends PathAwareEntity implements RangedAttackMob {
	private static final TrackedData<Integer> ROLE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> BEHAVIOR = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> FORMATION = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> SQUAD_LEADER = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> EXPERIENCE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> DEBUG_ACTIVE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Optional<BlockPos>> SYNCED_HOME = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
	private static final TrackedData<Integer> SYNCED_PATROL_RADIUS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);

	private static final Identifier LEVEL_HEALTH_MODIFIER_ID = GuardVillagersMod.id("guard_level_health");
	private static final Identifier LEVEL_DAMAGE_MODIFIER_ID = GuardVillagersMod.id("guard_level_damage");
	private static final Identifier LEVEL_SPEED_MODIFIER_ID = GuardVillagersMod.id("guard_level_speed");

	private static final String OWNER_KEY = "GuardOwner";
	private static final String ROLE_KEY = "GuardRole";
	private static final String BEHAVIOR_KEY = "GuardBehavior";
	private static final String FORMATION_KEY = "GuardFormation";
	private static final String STAYING_KEY = "GuardStaying";
	private static final String SQUAD_ID_KEY = "GuardSquadId";
	private static final String SQUAD_LEADER_KEY = "GuardSquadLeader";
	private static final String EXPERIENCE_KEY = "GuardExperience";
	private static final String PLAYER_MAINHAND_KEY = "PlayerMainHand";
	private static final String PLAYER_HELMET_KEY = "PlayerHelmet";
	private static final String PLAYER_CHEST_KEY = "PlayerChest";
	private static final String PLAYER_LEGS_KEY = "PlayerLegs";
	private static final String PLAYER_FEET_KEY = "PlayerFeet";
	private static final String HAS_HOME_KEY = "HasHome";
	private static final String HOME_X_KEY = "HomeX";
	private static final String HOME_Y_KEY = "HomeY";
	private static final String HOME_Z_KEY = "HomeZ";
	private static final String PATROL_RADIUS_KEY = "PatrolRadius";
	private static final String GROUP_INDEX_KEY = "GroupIndex";
	private static final String GROUP_COLUMN_KEY = "GroupColumn";
	private static final String GROUP_NAME_KEY = "GroupName";
	private static final String SKIN_PROFILE_KEY = "GuardSkinProfile";
	private static final String GENERATED_NAME_KEY = "GeneratedName";
	private static final String DISPLAY_NAME_KEY = "DisplayName";
	private static final String SPECIAL_PROFILE_KEY = "SpecialProfile";
	private static final String NEXT_NOTCH_APPLE_TICK_KEY = "NextNotchAppleTick";
	// Legacy keys for migration
	private static final String LEGACY_HIERARCHY_ROW_KEY = "HierarchyRow";
	private static final String LEGACY_HIERARCHY_COLUMN_KEY = "HierarchyColumn";
	private static final String LEGACY_HIERARCHY_ROLE_KEY = "HierarchyRole";
	private static final String LAST_LAND_X_KEY = "LastLandX";
	private static final String LAST_LAND_Y_KEY = "LastLandY";
	private static final String LAST_LAND_Z_KEY = "LastLandZ";
	private static final String HAS_LAST_LAND_KEY = "HasLastLand";
	private static final int MIN_GROUP_INDEX = -1;
	private static final int MAX_GROUP_INDEX = Integer.MAX_VALUE;
	private static final String DEFAULT_UNASSIGNED_GROUP_NAME = "Unassigned";
	private static final int MAX_SKIN_PROFILE_LENGTH = 64;
	private static final String DEBUG_NAME_PREFIX = "[DBG] ";
	private static final int NOTCH_ROLL_CHANCE = 500;
	private static final int JACK_BLACK_ROLL_CHANCE = 400;
	private static final int JASON_MOMOA_ROLL_CHANCE = 400;
	private static final long NOTCH_APPLE_COOLDOWN_TICKS = 18_000L;
	private static final int NAME_MAX_LENGTH = 32;
	private static final String SPECIAL_NOTCH_NAME = "Notch";
	private static final String SPECIAL_JACK_BLACK_NAME = "Jack Black";
	private static final String SPECIAL_JASON_MOMOA_NAME = "Jason Momoa";

	private static final Map<Item, Integer> SWORD_SCORE = Map.ofEntries(
		Map.entry(Items.WOODEN_SWORD, 1),
		Map.entry(Items.STONE_SWORD, 2),
		Map.entry(Items.GOLDEN_SWORD, 2),
		Map.entry(Items.IRON_SWORD, 3),
		Map.entry(Items.DIAMOND_SWORD, 4),
		Map.entry(Items.NETHERITE_SWORD, 5)
	);

	private static final Map<Item, ArmorDefinition> ARMOR_DEFINITIONS = Map.ofEntries(
		Map.entry(Items.LEATHER_HELMET, new ArmorDefinition(EquipmentSlot.HEAD, 1)),
		Map.entry(Items.CHAINMAIL_HELMET, new ArmorDefinition(EquipmentSlot.HEAD, 2)),
		Map.entry(Items.GOLDEN_HELMET, new ArmorDefinition(EquipmentSlot.HEAD, 2)),
		Map.entry(Items.IRON_HELMET, new ArmorDefinition(EquipmentSlot.HEAD, 3)),
		Map.entry(Items.DIAMOND_HELMET, new ArmorDefinition(EquipmentSlot.HEAD, 4)),
		Map.entry(Items.NETHERITE_HELMET, new ArmorDefinition(EquipmentSlot.HEAD, 5)),
		Map.entry(Items.LEATHER_CHESTPLATE, new ArmorDefinition(EquipmentSlot.CHEST, 1)),
		Map.entry(Items.CHAINMAIL_CHESTPLATE, new ArmorDefinition(EquipmentSlot.CHEST, 2)),
		Map.entry(Items.GOLDEN_CHESTPLATE, new ArmorDefinition(EquipmentSlot.CHEST, 2)),
		Map.entry(Items.IRON_CHESTPLATE, new ArmorDefinition(EquipmentSlot.CHEST, 3)),
		Map.entry(Items.DIAMOND_CHESTPLATE, new ArmorDefinition(EquipmentSlot.CHEST, 4)),
		Map.entry(Items.NETHERITE_CHESTPLATE, new ArmorDefinition(EquipmentSlot.CHEST, 5)),
		Map.entry(Items.LEATHER_LEGGINGS, new ArmorDefinition(EquipmentSlot.LEGS, 1)),
		Map.entry(Items.CHAINMAIL_LEGGINGS, new ArmorDefinition(EquipmentSlot.LEGS, 2)),
		Map.entry(Items.GOLDEN_LEGGINGS, new ArmorDefinition(EquipmentSlot.LEGS, 2)),
		Map.entry(Items.IRON_LEGGINGS, new ArmorDefinition(EquipmentSlot.LEGS, 3)),
		Map.entry(Items.DIAMOND_LEGGINGS, new ArmorDefinition(EquipmentSlot.LEGS, 4)),
		Map.entry(Items.NETHERITE_LEGGINGS, new ArmorDefinition(EquipmentSlot.LEGS, 5)),
		Map.entry(Items.LEATHER_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 1)),
		Map.entry(Items.CHAINMAIL_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 2)),
		Map.entry(Items.GOLDEN_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 2)),
		Map.entry(Items.IRON_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 3)),
		Map.entry(Items.DIAMOND_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 4)),
		Map.entry(Items.NETHERITE_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 5))
	);

	private MeleeAttackGoal meleeGoal;
	private GuardBowAttackGoal rangedGoal;

	private UUID ownerUuid;
	private UUID squadId;
	private boolean staying;
	private boolean retreating;
	private UUID priorityTarget;
	private int combatCooldown;
	private int noSightTicks;
	private int stuckTargetTicks;
	private Vec3d lastProgressPos = Vec3d.ZERO;
	private int rallyTicks;
	private BlockPos rallyPoint;
	private BlockPos home;
	private int patrolRadius = 0;
	private BlockPos lastLandPos;
	private BlockPos lastLandCheckPos;
	private int groupIndex = MIN_GROUP_INDEX;
	private int groupColumn = 1;
	private String groupName = DEFAULT_UNASSIGNED_GROUP_NAME;
	private String skinProfileId = "";
	private boolean playerMainHand;
	private final EnumMap<EquipmentSlot, Boolean> playerArmor = new EnumMap<>(EquipmentSlot.class);
	private String generatedName = "";
	private String displayName = "";
	private SpecialProfile specialProfile = SpecialProfile.NONE;
	private long nextNotchAppleTick = 0L;
	private long lastAlertTick = Long.MIN_VALUE;

	public GuardEntity(EntityType<? extends PathAwareEntity> entityType, net.minecraft.world.World world) {
		super(entityType, world);
		this.getNavigation().setCanOpenDoors(true);
		this.setPathfindingPenalty(net.minecraft.entity.ai.pathing.PathNodeType.WATER, 8.0F);
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			this.playerArmor.put(slot, false);
		}
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return MobEntity.createMobAttributes()
			.add(EntityAttributes.MAX_HEALTH, 20.0D)
			.add(EntityAttributes.MOVEMENT_SPEED, 0.32D)
			.add(EntityAttributes.ATTACK_DAMAGE, 5.0D)
			.add(EntityAttributes.FOLLOW_RANGE, 32.0D);
	}

	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, EntityData entityData) {
		EntityData data = super.initialize(world, difficulty, spawnReason, entityData);
		if ((spawnReason == SpawnReason.SPAWN_ITEM_USE || spawnReason == SpawnReason.DISPENSER || spawnReason == SpawnReason.COMMAND) && this.getMainHandStack().isEmpty()) {
			this.applyNaturalLoadout(world.toServerWorld());
			this.setBehavior(GuardBehavior.random(world.toServerWorld().getRandom()));
		}
		return data;
	}

	@Override
	protected void initGoals() {
		this.meleeGoal = new MeleeAttackGoal(this, 1.2D, true);
		this.rangedGoal = new GuardBowAttackGoal(this, 1.0D, 20, 15.0F);

		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new TacticalRetreatGoal(this, 1.35D));
		this.goalSelector.add(2, new LongDoorInteractGoal(this, true));
		this.goalSelector.add(3, new ReturnToLandGoal(this, 1.2D));
		this.goalSelector.add(4, new RaidTacticsGoal(this, 1.2D));
		this.goalSelector.add(5, new PerimeterPatrolGoal(this, 1.0D));
		this.goalSelector.add(6, new CrowdControlGoal(this, 1.0D));
		this.goalSelector.add(7, new FormationFollowOwnerGoal(this, 1.0D));
		this.goalSelector.add(8, new WanderAroundFarGoal(this, 0.8D));
		this.goalSelector.add(9, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
		this.goalSelector.add(10, new LookAroundGoal(this));

		this.targetSelector.add(1, new RevengeGoal(this, GuardEntity.class));
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, HostileEntity.class, true, false));
		this.targetSelector.add(3, new ElectLeaderGoal(this, 48.0D));
		this.updateCombatGoals();
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(ROLE, GuardRole.SWORDSMAN.getId());
		builder.add(BEHAVIOR, GuardBehavior.DEFENSIVE.getId());
		builder.add(FORMATION, FormationType.FOLLOW.getId());
		builder.add(SQUAD_LEADER, false);
		builder.add(EXPERIENCE, 0);
		builder.add(DEBUG_ACTIVE, false);
		builder.add(SYNCED_HOME, Optional.empty());
		builder.add(SYNCED_PATROL_RADIUS, 0);
	}

	public boolean isDebugActive() {
		return this.dataTracker.get(DEBUG_ACTIVE);
	}

	public void setDebugActive(boolean active) {
		this.dataTracker.set(DEBUG_ACTIVE, active);
	}

	public GuardRole getRole() {
		return GuardRole.fromId(this.dataTracker.get(ROLE));
	}

	public void setRole(GuardRole role) {
		this.dataTracker.set(ROLE, role.getId());
		if (this.getEntityWorld() instanceof ServerWorld) {
			this.updateCombatGoals();
		}
	}

	public GuardBehavior getBehavior() {
		return GuardBehavior.fromId(this.dataTracker.get(BEHAVIOR));
	}

	public void setBehavior(GuardBehavior behavior) {
		this.dataTracker.set(BEHAVIOR, behavior.getId());
	}

	public FormationType getFormationType() {
		return FormationType.fromId(this.dataTracker.get(FORMATION));
	}

	public void setFormationType(FormationType formationType) {
		FormationType resolved = formationType == null ? FormationType.FOLLOW : formationType;
		this.dataTracker.set(FORMATION, resolved.getId());
	}

	public int getExperience() {
		return this.dataTracker.get(EXPERIENCE);
	}

	public void setExperience(int experience) {
		this.dataTracker.set(EXPERIENCE, Math.max(0, experience));
		this.applyLevelModifiers();
	}

	public void addExperience(int amount) {
		if (amount <= 0) {
			return;
		}
		int beforeLevel = this.getLevel();
		this.setExperience(this.getExperience() + amount);
		if (this.getLevel() > beforeLevel) {
			this.heal(2.0F);
		}
	}

	public int getLevel() {
		return Math.min(10, 1 + (this.getExperience() / 120));
	}

	public boolean hasOwner() {
		return this.ownerUuid != null;
	}

	public UUID getOwnerUuid() {
		return this.ownerUuid;
	}

	public boolean isOwnedBy(UUID playerUuid) {
		return this.ownerUuid != null && this.ownerUuid.equals(playerUuid);
	}

	public void setOwnerUuid(UUID ownerUuid) {
		UUID previousOwner = this.ownerUuid;
		this.ownerUuid = ownerUuid;
		if (ownerUuid != null && this.squadId == null) {
			this.squadId = ownerUuid;
		}
		if (ownerUuid == null) {
			this.generatedName = "";
			this.displayName = "";
			this.specialProfile = SpecialProfile.NONE;
			this.nextNotchAppleTick = 0L;
		} else if (!ownerUuid.equals(previousOwner) || this.generatedName.isBlank()) {
			this.ensureIdentityForOwner();
		}
		this.updateGroupNameplate();
		if (this.getEntityWorld() instanceof ServerWorld) {
			GuardOwnershipIndex.track(this);
		} else if (ownerUuid == null) {
			GuardOwnershipIndex.untrack(this);
		}
	}

	public UUID getSquadId() {
		return this.squadId;
	}

	public boolean hasSquad() {
		return this.squadId != null;
	}

	public void setSquadId(UUID squadId) {
		this.squadId = squadId;
	}

	public boolean isSquadLeader() {
		return this.dataTracker.get(SQUAD_LEADER);
	}

	public void setSquadLeader(boolean squadLeader) {
		this.dataTracker.set(SQUAD_LEADER, squadLeader);
	}

	public boolean isSameSquad(GuardEntity other) {
		return this.squadId != null && this.squadId.equals(other.squadId);
	}

	public boolean isStaying() {
		return this.staying;
	}

	public void setStaying(boolean staying) {
		this.staying = staying;
		if (staying) {
			this.clearCombatTarget();
		}
	}

	public boolean isRetreating() {
		return this.retreating;
	}

	public void setRetreating(boolean retreating) {
		this.retreating = retreating;
	}

	public Optional<BlockPos> getHome() {
		return Optional.ofNullable(this.home);
	}

	public int getPatrolRadius() {
		return this.patrolRadius;
	}

	public void setHome(BlockPos home, int patrolRadius) {
		this.home = home.toImmutable();
		this.setPatrolRadius(patrolRadius);
		this.syncHomeData();
	}

	public void setHome(BlockPos home) {
		this.setHome(home, Math.max(24, this.patrolRadius));
	}

	public void clearHome() {
		this.home = null;
		this.patrolRadius = 0;
		this.syncHomeData();
	}

	public void setPatrolRadius(int patrolRadius) {
		this.patrolRadius = MathHelper.clamp(patrolRadius, 0, 128);
		this.syncHomeData();
	}

	private void syncHomeData() {
		this.dataTracker.set(SYNCED_HOME, Optional.ofNullable(this.home));
		this.dataTracker.set(SYNCED_PATROL_RADIUS, this.patrolRadius);
	}

	public Optional<BlockPos> getSyncedHome() {
		return this.dataTracker.get(SYNCED_HOME);
	}

	public int getSyncedPatrolRadius() {
		return this.dataTracker.get(SYNCED_PATROL_RADIUS);
	}

	public BlockPos getLastLandPos() {
		return this.lastLandPos;
	}

	private void updateLastLandPos() {
		if (this.isTouchingWater() || !this.isOnGround()) {
			return;
		}
		BlockPos currentPos = this.getBlockPos();
		if (currentPos.equals(this.lastLandCheckPos)) {
			return;
		}
		this.lastLandCheckPos = currentPos;
		this.lastLandPos = currentPos.toImmutable();
	}

	public int getGroupIndex() {
		return this.groupIndex;
	}

	public void setGroupIndex(int groupIndex) {
		this.groupIndex = MathHelper.clamp(groupIndex, MIN_GROUP_INDEX, MAX_GROUP_INDEX);
	}

	public int getGroupColumn() {
		return this.groupColumn;
	}

	public void setGroupColumn(int groupColumn) {
		this.groupColumn = MathHelper.clamp(groupColumn, 0, 2);
	}

	public String getGroupName() {
		if (this.groupName == null || this.groupName.isBlank()) {
			return DEFAULT_UNASSIGNED_GROUP_NAME;
		}
		return this.groupName;
	}

	public void setGroupName(String name) {
		if (name == null || name.isBlank()) {
			this.groupName = DEFAULT_UNASSIGNED_GROUP_NAME;
			return;
		}
		String trimmed = name.trim();
		this.groupName = trimmed.length() <= 24 ? trimmed : trimmed.substring(0, 24);
	}

	public String getSkinProfileId() {
		return this.skinProfileId;
	}

	public void setSkinProfileId(String skinProfileId) {
		if (skinProfileId == null || skinProfileId.isBlank()) {
			this.skinProfileId = "";
			return;
		}
		String trimmed = skinProfileId.trim();
		this.skinProfileId = trimmed.length() <= MAX_SKIN_PROFILE_LENGTH ? trimmed : trimmed.substring(0, MAX_SKIN_PROFILE_LENGTH);
	}

	public void clearCombatTarget() {
		this.priorityTarget = null;
		this.setTarget(null);
		this.noSightTicks = 0;
		this.stuckTargetTicks = 0;
		this.combatCooldown = 0;
		this.getNavigation().stop();
	}

	public boolean canTargetWithinZone(BlockPos pos) {
		if (this.home == null || this.patrolRadius <= 0) {
			return true;
		}
		return this.home.getSquaredDistance(pos) <= (double) this.patrolRadius * this.patrolRadius;
	}

	public void rallyTo(BlockPos rallyPoint, int ticks) {
		this.rallyPoint = rallyPoint.toImmutable();
		this.rallyTicks = Math.max(0, ticks);
		this.staying = false;
	}

	public boolean canExecuteBehaviorGoals() {
		return !this.staying && !this.retreating && this.rallyTicks <= 0;
	}

	public boolean canFollowOwnerFormation() {
		if (!this.hasOwner() || this.staying || this.retreating || this.getTarget() != null) {
			return false;
		}
		if (!(this.getEntityWorld() instanceof ServerWorld world)) {
			return false;
		}
		return GuardReputationManager.isTrustedByGuards(world, this.ownerUuid, this.getBlockPos());
	}

	public boolean shouldTacticallyRetreat() {
		if (this.getHealth() > this.getMaxHealth() * 0.25F) {
			return false;
		}
		LivingEntity target = this.getTarget();
		return target != null || this.combatCooldown > 0;
	}

	public boolean shouldContinueRetreat() {
		return this.getHealth() < this.getMaxHealth() * 0.40F && (this.getTarget() != null || this.combatCooldown > 0);
	}

	public BlockPos findSafeRetreatPoint(ServerWorld world) {
		VillagerEntity bestCleric = null;
		double bestDistance = Double.MAX_VALUE;
		for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, this.getBoundingBox().expand(48.0D), VillagerEntity::isAlive)) {
			if (!villager.getVillagerData().profession().matchesKey(VillagerProfession.CLERIC)) {
				continue;
			}
			double distanceSq = this.squaredDistanceTo(villager);
			if (distanceSq < bestDistance) {
				bestDistance = distanceSq;
				bestCleric = villager;
			}
		}
		if (bestCleric != null) {
			return bestCleric.getBlockPos();
		}

		Optional<BlockPos> bedPos = world.getPointOfInterestStorage().getNearestPosition(
			entry -> entry.matchesKey(PointOfInterestTypes.HOME),
			this.getBlockPos(),
			48,
			PointOfInterestStorage.OccupationStatus.ANY
		);
		if (bedPos.isPresent()) {
			return bedPos.get();
		}

		if (this.home != null) {
			return this.home;
		}

		LivingEntity target = this.getTarget();
		if (target != null) {
			Vec3d away = this.getEntityPos().subtract(target.getEntityPos()).normalize().multiply(12.0D);
			Vec3d destination = this.getEntityPos().add(away);
			BlockPos blockPos = new BlockPos((int) Math.floor(destination.x), (int) Math.floor(destination.y), (int) Math.floor(destination.z));
			return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, blockPos);
		}

		return this.getBlockPos();
	}

	public ServerPlayerEntity resolveOwner(ServerWorld world) {
		if (!this.hasOwner()) {
			return null;
		}
		ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(this.ownerUuid);
		if (owner == null || owner.getEntityWorld() != world) {
			return null;
		}
		return owner;
	}

	public void assignRandomRole(ServerWorld world) {
		this.setRole(GuardRole.random(world.getRandom()));
	}

	public void applyNaturalLoadout(ServerWorld world) {
		this.assignRandomRole(world);
		this.setBehavior(GuardBehavior.random(world.getRandom()));
		this.setFormationType(FormationType.FOLLOW);
		this.setGroupIndex(MIN_GROUP_INDEX);
		this.setGroupColumn(world.getRandom().nextBetween(0, 2));
		this.setGroupName(DEFAULT_UNASSIGNED_GROUP_NAME);
		this.equipGuardGear(world, 0, new GuardPlayerUpgrades());
	}

	public void applyPurchasedLoadout(ServerWorld world, GuardPlayerUpgrades upgrades) {
		if (!this.hasOwner()) {
			this.assignRandomRole(world);
		}
		this.setBehavior(GuardBehavior.DEFENSIVE);
		this.setFormationType(FormationType.FOLLOW);
		this.setGroupIndex(MIN_GROUP_INDEX);
		this.setGroupColumn(world.getRandom().nextBetween(0, 2));
		this.setGroupName(DEFAULT_UNASSIGNED_GROUP_NAME);
		this.equipGuardGear(world, upgrades.getWeaponLevel(), upgrades);
	}

	private void equipGuardGear(ServerWorld world, int weaponLevel, GuardPlayerUpgrades upgrades) {
		if (this.getRole() == GuardRole.SWORDSMAN) {
			Item sword = switch (weaponLevel) {
				case 1 -> Items.IRON_SWORD;
				case 2, 3, 4, 5 -> Items.DIAMOND_SWORD;
				default -> Items.STONE_SWORD;
			};
			ItemStack swordStack = new ItemStack(sword);
			int sharpnessLevel = switch (weaponLevel) {
				case 1 -> 1;
				case 2 -> 2;
				default -> 3;
			};
			this.applyEnchantment(world, swordStack, Enchantments.SHARPNESS, sharpnessLevel);
			this.equipStack(EquipmentSlot.MAINHAND, swordStack);
		} else {
			ItemStack bowStack = new ItemStack(Items.BOW);
			this.applyEnchantment(world, bowStack, Enchantments.POWER, Math.min(5, Math.max(1, weaponLevel + 1)));
			this.equipStack(EquipmentSlot.MAINHAND, bowStack);
		}

		this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0F);
		this.playerMainHand = false;
		if (upgrades.hasShieldUpgrade()) {
			this.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			this.setEquipmentDropChance(EquipmentSlot.OFFHAND, 0.0F);
		} else if (this.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD)) {
			this.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		}

		this.equipArmorPieces(world, upgrades);
	}

	private void equipArmorPieces(ServerWorld world, GuardPlayerUpgrades upgrades) {
		int armorLevel = upgrades.getArmorLevel();
		int protectionLevel = Math.min(4, Math.max(0, armorLevel / 2));
		GuardPlayerUpgrades.ArmorTier helmetTier = this.rollArmorTierForCurrentProfile(upgrades);
		this.equipArmorPiece(world, EquipmentSlot.HEAD, getArmorItemForSlot(helmetTier, EquipmentSlot.HEAD), protectionLevel);

		for (EquipmentSlot slot : List.of(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			GuardPlayerUpgrades.ArmorTier tier = this.rollConstrainedArmorTier(upgrades, helmetTier);
			this.equipArmorPiece(world, slot, getArmorItemForSlot(tier, slot), protectionLevel);
		}

		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			this.setEquipmentDropChance(slot, 0.0F);
			this.playerArmor.put(slot, false);
		}
	}

	private GuardPlayerUpgrades.ArmorTier rollConstrainedArmorTier(GuardPlayerUpgrades upgrades, GuardPlayerUpgrades.ArmorTier helmetTier) {
		GuardPlayerUpgrades.ArmorTier roll = this.rollArmorTierForCurrentProfile(upgrades);
		int helmetIndex = helmetTier.ordinal();
		int minAllowed = Math.max(0, helmetIndex - 1);
		int clamped = Math.max(minAllowed, Math.min(helmetIndex, roll.ordinal()));
		return GuardPlayerUpgrades.ArmorTier.values()[clamped];
	}

	private GuardPlayerUpgrades.ArmorTier rollArmorTierForCurrentProfile(GuardPlayerUpgrades upgrades) {
		GuardPlayerUpgrades.ArmorTier base = upgrades.rollArmorTier(this.getRandom());
		if (!this.specialProfile.hasDiamondArmorBonus()) {
			return base;
		}
		return this.applyDiamondArmorBonus(upgrades, base);
	}

	private GuardPlayerUpgrades.ArmorTier applyDiamondArmorBonus(GuardPlayerUpgrades upgrades, GuardPlayerUpgrades.ArmorTier baseTier) {
		if (baseTier == GuardPlayerUpgrades.ArmorTier.DIAMOND || baseTier == GuardPlayerUpgrades.ArmorTier.NETHERITE) {
			return baseTier;
		}
		int baseDiamondChance = upgrades.getArmorDistribution().diamond();
		if (baseDiamondChance <= 0) {
			return baseTier;
		}
		int boostedDiamondChance = Math.min(100, baseDiamondChance * 3);
		double bonusChance = (boostedDiamondChance - baseDiamondChance) / (double) (100 - baseDiamondChance);
		if (this.getRandom().nextDouble() > bonusChance) {
			return baseTier;
		}
		if (this.rollNetheriteUpgrade(upgrades.getArmorLevel())) {
			return GuardPlayerUpgrades.ArmorTier.NETHERITE;
		}
		return GuardPlayerUpgrades.ArmorTier.DIAMOND;
	}

	private boolean rollNetheriteUpgrade(int armorLevel) {
		int netheriteChance = switch (armorLevel) {
			case 6 -> 2;
			case 7 -> 5;
			case 8 -> 10;
			default -> 0;
		};
		return netheriteChance > 0 && this.getRandom().nextInt(100) < netheriteChance;
	}

	private Item getArmorItemForSlot(GuardPlayerUpgrades.ArmorTier tier, EquipmentSlot slot) {
		return switch (tier) {
			case CHAINMAIL -> switch (slot) {
				case HEAD -> Items.CHAINMAIL_HELMET;
				case CHEST -> Items.CHAINMAIL_CHESTPLATE;
				case LEGS -> Items.CHAINMAIL_LEGGINGS;
				case FEET -> Items.CHAINMAIL_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
			case IRON -> switch (slot) {
				case HEAD -> Items.IRON_HELMET;
				case CHEST -> Items.IRON_CHESTPLATE;
				case LEGS -> Items.IRON_LEGGINGS;
				case FEET -> Items.IRON_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
			case GOLD -> switch (slot) {
				case HEAD -> Items.GOLDEN_HELMET;
				case CHEST -> Items.GOLDEN_CHESTPLATE;
				case LEGS -> Items.GOLDEN_LEGGINGS;
				case FEET -> Items.GOLDEN_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
			case DIAMOND -> switch (slot) {
				case HEAD -> Items.DIAMOND_HELMET;
				case CHEST -> Items.DIAMOND_CHESTPLATE;
				case LEGS -> Items.DIAMOND_LEGGINGS;
				case FEET -> Items.DIAMOND_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
			case NETHERITE -> switch (slot) {
				case HEAD -> Items.NETHERITE_HELMET;
				case CHEST -> Items.NETHERITE_CHESTPLATE;
				case LEGS -> Items.NETHERITE_LEGGINGS;
				case FEET -> Items.NETHERITE_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
			default -> switch (slot) {
				case HEAD -> Items.LEATHER_HELMET;
				case CHEST -> Items.LEATHER_CHESTPLATE;
				case LEGS -> Items.LEATHER_LEGGINGS;
				case FEET -> Items.LEATHER_BOOTS;
				default -> Items.LEATHER_BOOTS;
			};
		};
	}

	private void equipArmorPiece(ServerWorld world, EquipmentSlot slot, Item item, int protectionLevel) {
		ItemStack stack = new ItemStack(item);
		if (protectionLevel > 0) {
			this.applyEnchantment(world, stack, Enchantments.PROTECTION, protectionLevel);
		}
		this.equipStack(slot, stack);
	}

	private void applyEnchantment(ServerWorld world, ItemStack stack, RegistryKey<Enchantment> enchantment, int level) {
		Registry<Enchantment> registry = world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
		if (!registry.contains(enchantment)) {
			return;
		}
		RegistryEntry<Enchantment> entry = registry.getEntry(registry.getValueOrThrow(enchantment));
		EnchantmentHelper.apply(stack, builder -> builder.set(entry, level));
	}

	@Override
	protected ActionResult interactMob(PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);

		if (this.ownerUuid != null && !this.ownerUuid.equals(player.getUuid())) {
			if (this.getEntityWorld() instanceof ServerWorld) {
				player.sendMessage(Text.literal(this.getCallsign() + " is not your guard."), true);
			}
			return ActionResult.SUCCESS;
		}

		if (this.ownerUuid == null && stack.isOf(Items.EMERALD)) {
			if (this.getEntityWorld() instanceof ServerWorld world) {
				if (!GuardReputationManager.isTrustedByGuards(world, player.getUuid(), this.getBlockPos())) {
					player.sendMessage(Text.literal("The guard distrusts you due to village reputation."), true);
					return ActionResult.SUCCESS;
				}
				int hirePrice = com.guardvillagers.GuardHirePricing.getHirePrice(this.getLevel());
				if (!player.getAbilities().creativeMode && stack.getCount() < hirePrice) {
					player.sendMessage(Text.literal("Need " + hirePrice + " emerald(s) to hire this guard."), true);
					return ActionResult.SUCCESS;
				}

				this.setOwnerUuid(player.getUuid());
				this.setStaying(false);
				this.setBehavior(GuardBehavior.DEFENSIVE);
				this.setFormationType(FormationType.FOLLOW);
				if (!player.getAbilities().creativeMode) {
					stack.decrement(hirePrice);
				}
				player.sendMessage(Text.literal(this.getCallsign() + " is now loyal to you."), true);
			}
			return ActionResult.SUCCESS;
		}

		if (this.ownerUuid != null && this.ownerUuid.equals(player.getUuid()) && stack.isEmpty()) {
			if (player.isSneaking()) {
				this.cycleBehavior();
				player.sendMessage(Text.literal(this.getCallsign() + " behavior set to " + this.getBehavior().name().toLowerCase(Locale.ROOT) + "."), true);
			} else {
				this.setStaying(!this.staying);
				player.sendMessage(Text.literal(this.staying ? this.getCallsign() + " staying." : this.getCallsign() + " following."), true);
			}
			return ActionResult.SUCCESS;
		}

		if (this.ownerUuid != null && this.ownerUuid.equals(player.getUuid()) && !stack.isEmpty()) {
			if (this.tryApplyNameTag(player, hand, stack)) {
				return ActionResult.SUCCESS;
			}
			if (this.tryApplyPlayerUpgrade(player, hand, stack)) {
				return ActionResult.SUCCESS;
			}
			player.sendMessage(Text.literal("That item is not an upgrade for " + this.getCallsign() + "."), true);
			return ActionResult.SUCCESS;
		}

		return super.interactMob(player, hand);
	}

	private void cycleBehavior() {
		GuardBehavior[] behaviors = GuardBehavior.values();
		int next = (this.getBehavior().ordinal() + 1) % behaviors.length;
		this.setBehavior(behaviors[next]);
	}

	private boolean tryApplyNameTag(PlayerEntity player, Hand hand, ItemStack offered) {
		if (!offered.isOf(Items.NAME_TAG) || !offered.contains(DataComponentTypes.CUSTOM_NAME)) {
			return false;
		}
		String requestedName = offered.getName().getString();
		String sanitized = sanitizeName(requestedName);
		if (sanitized.isBlank()) {
			return false;
		}
		this.displayName = sanitized;
		this.updateGroupNameplate();
		if (!player.getAbilities().creativeMode) {
			player.getStackInHand(hand).decrement(1);
		}
		if (this.getEntityWorld() instanceof ServerWorld) {
			player.sendMessage(Text.literal("Renamed guard to " + this.getCallsign() + "."), true);
		}
		return true;
	}

	private boolean tryApplyPlayerUpgrade(PlayerEntity player, Hand hand, ItemStack offered) {
		if (!(this.getEntityWorld() instanceof ServerWorld)) {
			return false;
		}

		if (this.tryApplyArmorUpgrade(player, hand, offered)) {
			player.sendMessage(Text.literal("Guard armor upgraded."), true);
			return true;
		}

		if (this.tryApplyWeaponUpgrade(player, hand, offered)) {
			player.sendMessage(Text.literal("Guard weapon upgraded."), true);
			return true;
		}

		return false;
	}

	private boolean tryApplyArmorUpgrade(PlayerEntity player, Hand hand, ItemStack offered) {
		if (!player.isSneaking() || this.squaredDistanceTo(player) > 25.0D) {
			return false;
		}

		ArmorDefinition definition = ARMOR_DEFINITIONS.get(offered.getItem());
		if (definition == null) {
			return false;
		}

		ItemStack equipped = this.getEquippedStack(definition.slot());
		int currentScore = this.getArmorScore(equipped);
		int offeredProtection = this.getEnchantmentLevel(offered, Enchantments.PROTECTION);
		int currentProtection = this.getEnchantmentLevel(equipped, Enchantments.PROTECTION);
		if (definition.score() < currentScore) {
			return false;
		}
		if (definition.score() == currentScore && offeredProtection <= currentProtection) {
			return false;
		}

		this.equipStack(definition.slot(), offered.copyWithCount(1));
		this.setEquipmentDropChance(definition.slot(), 1.0F);
		this.playerArmor.put(definition.slot(), true);
		if (!player.getAbilities().creativeMode) {
			player.getStackInHand(hand).decrement(1);
		}
		return true;
	}

	public UUID getPriorityTargetUuid() {
		return this.priorityTarget;
	}

	public int getCombatCooldown() {
		return this.combatCooldown;
	}

	public GuardDebugSnapshot getDebugSnapshot(int maxNodes) {
		int nodeCap = Math.max(0, maxNodes);
		Path path = this.getNavigation().getCurrentPath();
		List<BlockPos> nodes = new ArrayList<>();
		int currentNodeIndex = -1;
		if (path != null && !path.isFinished() && nodeCap > 0) {
			int length = Math.min(path.getLength(), nodeCap);
			for (int i = 0; i < length; i++) {
				PathNode node = path.getNode(i);
				nodes.add(new BlockPos(node.x, node.y, node.z));
			}
			currentNodeIndex = Math.min(path.getCurrentNodeIndex(), Math.max(0, length - 1));
		}
		LivingEntity target = this.getTarget();
		int targetEntityId = target == null ? -1 : target.getId();
		return new GuardDebugSnapshot(nodes, currentNodeIndex, targetEntityId);
	}

	public record GuardDebugSnapshot(List<BlockPos> pathNodes, int currentPathIndex, int targetEntityId) {
		public GuardDebugSnapshot {
			pathNodes = List.copyOf(pathNodes);
		}
	}

	private boolean tryApplyWeaponUpgrade(PlayerEntity player, Hand hand, ItemStack offered) {
		if (this.getRole() == GuardRole.SWORDSMAN) {
			int currentScore = this.getSwordScore(this.getMainHandStack());
			int offeredScore = this.getSwordScore(offered);
			if (offeredScore <= currentScore) {
				return false;
			}
		} else {
			if (!offered.isOf(Items.BOW)) {
				return false;
			}
			int offeredPower = this.getEnchantmentLevel(offered, Enchantments.POWER);
			int currentPower = this.getEnchantmentLevel(this.getMainHandStack(), Enchantments.POWER);
			if (offeredPower <= currentPower) {
				return false;
			}
		}

		this.equipStack(EquipmentSlot.MAINHAND, offered.copyWithCount(1));
		this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 1.0F);
		this.playerMainHand = true;
		if (!player.getAbilities().creativeMode) {
			player.getStackInHand(hand).decrement(1);
		}
		return true;
	}

	private int getSwordScore(ItemStack stack) {
		int base = SWORD_SCORE.getOrDefault(stack.getItem(), 0);
		if (base == 0) {
			return 0;
		}
		int sharpness = this.getEnchantmentLevel(stack, Enchantments.SHARPNESS);
		return base * 10 + sharpness;
	}

	private int getEnchantmentLevel(ItemStack stack, RegistryKey<Enchantment> enchantment) {
		if (!(this.getEntityWorld() instanceof ServerWorld world)) {
			return 0;
		}
		Registry<Enchantment> registry = world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
		if (!registry.contains(enchantment)) {
			return 0;
		}
		RegistryEntry<Enchantment> entry = registry.getEntry(registry.getValueOrThrow(enchantment));
		return EnchantmentHelper.getLevel(entry, stack);
	}

	private int getArmorScore(ItemStack stack) {
		ArmorDefinition definition = ARMOR_DEFINITIONS.get(stack.getItem());
		return definition == null ? 0 : definition.score();
	}

	public void setPriorityTarget(LivingEntity target) {
		if (!this.canUseAsCombatTarget(target) || !this.canSee(target)) {
			return;
		}

		boolean acquiredNewTarget = this.priorityTarget == null || !this.priorityTarget.equals(target.getUuid());
		this.priorityTarget = target.getUuid();
		this.setTarget(target);
		this.combatCooldown = 100;
		if (acquiredNewTarget && this.getEntityWorld() instanceof ServerWorld world) {
			this.broadcastAlertTarget(world, target);
			this.shareTargetWithNearbyGolems(world, target);
		}
	}

	@Override
	protected void mobTick(ServerWorld world) {
		super.mobTick(world);
		if (this.age % 40 == 0 || this.ownerUuid == null) {
			GuardOwnershipIndex.track(this);
		}

		if (this.age % 20 == 0) {
			this.updateLastLandPos();
		}

		if (this.combatCooldown > 0) {
			this.combatCooldown--;
		}
		if (this.rallyTicks > 0) {
			this.rallyTicks--;
		}
		if (this.rallyTicks == 0) {
			this.rallyPoint = null;
		}

		if (this.age % 1200 == 0) {
			this.addExperience(1);
		}

		this.syncPriorityTarget(world);
		this.pickFallbackTarget(world);
		this.enforceTargetLineOfSight();
		this.breakOutOfStuckChasing();
		this.applyRallyBehavior();
		this.enforceZoneTethering();
		this.handleOwnerTrust(world);
		this.syncSupportEquipment(world);
		this.updateShieldUsage();
		this.maybeConsumeNotchApple(world);
		this.updateGroupNameplate();

		int healInterval = GuardVillagersMod.getHealingIntervalTicks(world, this.ownerUuid);
		if (healInterval > 0 && this.age % healInterval == 0 && this.combatCooldown <= 0 && this.getHealth() < this.getMaxHealth() * 0.6F) {
			this.heal(GuardVillagersMod.getHealingAmount(world, this.ownerUuid));
		}
	}

	private void syncSupportEquipment(ServerWorld world) {
		if (this.ownerUuid == null) {
			return;
		}
		boolean shouldHaveShield = GuardVillagersMod.hasShieldUpgrade(world, this.ownerUuid);
		boolean hasShield = this.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD);
		if (shouldHaveShield && !hasShield) {
			this.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			this.setEquipmentDropChance(EquipmentSlot.OFFHAND, 0.0F);
		} else if (!shouldHaveShield && hasShield) {
			this.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		}
	}

	private void updateShieldUsage() {
		ItemStack shield = this.getEquippedStack(EquipmentSlot.OFFHAND);
		if (!shield.isOf(Items.SHIELD)) {
			if (this.isUsingItem() && this.getActiveHand() == Hand.OFF_HAND) {
				this.clearActiveItem();
			}
			return;
		}

		LivingEntity target = this.getTarget();
		boolean shouldBlock = false;
		if (target != null && target.isAlive()) {
			double distanceSq = this.squaredDistanceTo(target);
			boolean rangedThreat = target instanceof RangedAttackMob
				|| target.getMainHandStack().isOf(Items.BOW)
				|| target.getMainHandStack().isOf(Items.CROSSBOW)
				|| target.getMainHandStack().isOf(Items.TRIDENT);
			boolean meleeWindow = this.getRole() != GuardRole.BOWMAN && distanceSq < 9.0D && !rangedThreat;
			shouldBlock = !meleeWindow && distanceSq <= (rangedThreat ? 256.0D : 81.0D);
			if (this.getRole() == GuardRole.BOWMAN && !rangedThreat && distanceSq <= 196.0D) {
				shouldBlock = false;
			}
		}

		if (shouldBlock) {
			if (this.isUsingItem() && this.getActiveHand() == Hand.MAIN_HAND) {
				this.clearActiveItem();
			}
			if (!this.isUsingItem() || this.getActiveHand() != Hand.OFF_HAND) {
				this.setCurrentHand(Hand.OFF_HAND);
			}
		} else if (this.isUsingItem() && this.getActiveHand() == Hand.OFF_HAND) {
			this.clearActiveItem();
		}
	}

	private void maybeConsumeNotchApple(ServerWorld world) {
		if (this.specialProfile != SpecialProfile.NOTCH) {
			return;
		}
		if (this.getHealth() > this.getMaxHealth() * 0.30F) {
			return;
		}
		if (world.getTime() < this.nextNotchAppleTick) {
			return;
		}
		this.nextNotchAppleTick = world.getTime() + NOTCH_APPLE_COOLDOWN_TICKS;
		this.heal(8.0F);
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 400, 1));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 2400, 3));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 6000, 0));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 6000, 0));
	}

	public void updateGroupNameplate() {
		Text current = this.getCustomName();
		String currentText = current == null ? "" : current.getString();
		if (currentText.startsWith(DEBUG_NAME_PREFIX)) {
			return;
		}

		if (!this.hasOwner()) {
			if (current != null || this.isCustomNameVisible()) {
				this.setCustomName(null);
				this.setCustomNameVisible(false);
			}
			return;
		}

		String name = this.getCallsign();
		if (!name.equals(currentText)) {
			this.setCustomName(Text.literal(name));
		}
		if (!this.isCustomNameVisible()) {
			this.setCustomNameVisible(true);
		}
	}

	private void applyRallyBehavior() {
		if (this.rallyPoint == null || this.rallyTicks <= 0 || this.getTarget() != null) {
			return;
		}
		this.getNavigation().startMovingTo(this.rallyPoint.getX() + 0.5D, this.rallyPoint.getY(), this.rallyPoint.getZ() + 0.5D, 1.2D);
	}

	private void enforceZoneTethering() {
		if (this.home == null || this.patrolRadius <= 0) {
			return;
		}

		LivingEntity target = this.getTarget();
		if (target != null && !this.canTargetWithinZone(target.getBlockPos())) {
			this.clearCombatTarget();
		}

		if (this.getTarget() == null && this.home.getSquaredDistance(this.getBlockPos()) > (double) (this.patrolRadius + 8) * (this.patrolRadius + 8)) {
			this.getNavigation().startMovingTo(this.home.getX() + 0.5D, this.home.getY(), this.home.getZ() + 0.5D, 1.0D);
		}
	}

	private void syncPriorityTarget(ServerWorld world) {
		if (this.priorityTarget == null) {
			return;
		}

		Entity entity = world.getEntity(this.priorityTarget);
		if (entity instanceof LivingEntity living && this.canUseAsCombatTarget(living) && this.canSee(living)) {
			this.setTarget(living);
			this.combatCooldown = 80;
		} else {
			this.priorityTarget = null;
		}
	}

	private void pickFallbackTarget(ServerWorld world) {
		LivingEntity current = this.getTarget();
		if (current != null && current.isAlive()) {
			if (this.isAlly(current)) {
				this.clearCombatTarget();
			} else {
				this.combatCooldown = 80;
				return;
			}
		}

		LivingEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;

		List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class, this.getBoundingBox().expand(20.0D), this::canTargetHostile);
		for (HostileEntity hostile : hostiles) {
			double distance = Math.sqrt(this.squaredDistanceTo(hostile));
			double distanceWeight = Math.max(0.0D, 24.0D - distance);
			double score = hostile.getHealth() * 0.8D
				+ distanceWeight * 0.6D
				+ this.getRandom().nextDouble() * 3.0D;
			if (score > bestScore) {
				bestScore = score;
				best = hostile;
			}
		}

		List<GuardEntity> enemyGuards = world.getEntitiesByClass(GuardEntity.class, this.getBoundingBox().expand(20.0D), this::canTargetEnemyGuard);
		for (GuardEntity enemyGuard : enemyGuards) {
			double distance = Math.sqrt(this.squaredDistanceTo(enemyGuard));
			double distanceWeight = Math.max(0.0D, 24.0D - distance);
			double score = enemyGuard.getHealth() * 0.8D
				+ distanceWeight * 0.6D
				+ this.getRandom().nextDouble() * 3.0D;
			if (score > bestScore) {
				bestScore = score;
				best = enemyGuard;
			}
		}
		if (best != null) {
			this.setTarget(best);
			this.combatCooldown = 80;
		}
	}

	private void handleOwnerTrust(ServerWorld world) {
		if (this.ownerUuid == null || this.age % 80 != 0) {
			return;
		}

		if (GuardReputationManager.shouldGuardsTurnHostile(world, this.ownerUuid, this.getBlockPos())) {
			ServerPlayerEntity owner = this.resolveOwner(world);
			if (owner != null && !owner.getAbilities().creativeMode && this.squaredDistanceTo(owner) < 256.0D) {
				this.setPriorityTarget(owner);
			}
		}
	}

	private void broadcastAlertTarget(ServerWorld world, LivingEntity target) {
		if (!this.canUseAsCombatTarget(target) || this.ownerUuid == null) {
			return;
		}
		double detectionRange = Math.max(8.0D, this.getAttributeValue(EntityAttributes.FOLLOW_RANGE));
		double detectionRangeSq = detectionRange * detectionRange;
		long alertTick = world.getTime();
		for (GuardEntity guard : world.getEntitiesByClass(
			GuardEntity.class,
			this.getBoundingBox().expand(detectionRange),
			entity -> entity != this
				&& entity.isAlive()
				&& entity.isOwnedBy(this.ownerUuid)
				&& entity.squaredDistanceTo(this) <= detectionRangeSq)
		) {
			guard.receiveAlertTarget(target, alertTick);
		}
	}

	private void receiveAlertTarget(LivingEntity target, long alertTick) {
		if (!this.canUseAsCombatTarget(target) || !this.canSee(target) || this.shouldIgnoreAlertBroadcasts()) {
			return;
		}
		LivingEntity current = this.getTarget();
		if (current != null && current.isAlive()) {
			return;
		}
		if (alertTick < this.lastAlertTick) {
			return;
		}
		this.lastAlertTick = alertTick;
		this.priorityTarget = target.getUuid();
		this.setTarget(target);
		this.combatCooldown = Math.max(this.combatCooldown, 80);
	}

	private boolean shouldIgnoreAlertBroadcasts() {
		return this.staying || this.retreating;
	}

	private void shareTargetWithNearbyGolems(ServerWorld world, LivingEntity target) {
		if (!(target instanceof HostileEntity || target instanceof RaiderEntity)) {
			return;
		}
		for (IronGolemEntity golem : world.getEntitiesByClass(
			IronGolemEntity.class,
			this.getBoundingBox().expand(24.0D),
			LivingEntity::isAlive
		)) {
			golem.setTarget(target);
		}
	}

	private boolean canTargetHostile(HostileEntity hostile) {
		return hostile.isAlive()
			&& !hostile.isRemoved()
			&& !this.isAlly(hostile)
			&& this.canSee(hostile)
			&& this.canTargetWithinZone(hostile.getBlockPos());
	}

	private boolean canTargetEnemyGuard(GuardEntity other) {
		return other != null
			&& other != this
			&& other.isAlive()
			&& this.ownerUuid != null
			&& other.ownerUuid != null
			&& !this.ownerUuid.equals(other.ownerUuid)
			&& this.canSee(other)
			&& this.canTargetWithinZone(other.getBlockPos());
	}

	private boolean canUseAsCombatTarget(LivingEntity target) {
		return target != null
			&& target.isAlive()
			&& !this.isAlly(target)
			&& this.canTargetWithinZone(target.getBlockPos());
	}

	@Override
	public void setTarget(LivingEntity target) {
		if (!this.canUseAsCombatTarget(target)) {
			super.setTarget(null);
			return;
		}
		super.setTarget(target);
	}

	private void enforceTargetLineOfSight() {
		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive()) {
			this.noSightTicks = 0;
			return;
		}

		if (this.canSee(target)) {
			this.noSightTicks = 0;
			return;
		}

		this.noSightTicks++;
		if (this.noSightTicks > 40) {
			this.clearCombatTarget();
		}
	}

	private void breakOutOfStuckChasing() {
		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive()) {
			this.stuckTargetTicks = 0;
			return;
		}

		if (this.squaredDistanceTo(target) < 9.0D) {
			this.lastProgressPos = this.getEntityPos();
			this.stuckTargetTicks = 0;
			return;
		}

		if (this.squaredDistanceTo(this.lastProgressPos) < 0.01D) {
			this.stuckTargetTicks++;
		} else {
			this.lastProgressPos = this.getEntityPos();
			this.stuckTargetTicks = 0;
		}

		if (this.stuckTargetTicks > 60) {
			this.clearCombatTarget();
		}
	}

	private boolean isAlly(Entity entity) {
		if (entity == null || entity == this) {
			return true;
		}
		if (entity instanceof IronGolemEntity) {
			return true;
		}
		if (entity instanceof GuardEntity otherGuard) {
			if (this.ownerUuid != null && otherGuard.ownerUuid != null) {
				return this.ownerUuid.equals(otherGuard.ownerUuid);
			}
			if (this.ownerUuid != null || otherGuard.ownerUuid != null) {
				return false;
			}
			return this.squadId != null && this.squadId.equals(otherGuard.squadId);
		}
		if (entity instanceof VillagerEntity) {
			return true;
		}
		if (entity instanceof PlayerEntity player) {
			if (player.isSpectator() || player.getAbilities().creativeMode) {
				return true;
			}
			if (this.ownerUuid != null && this.ownerUuid.equals(player.getUuid())) {
				return true;
			}
			if (this.ownerUuid != null && this.getEntityWorld() instanceof ServerWorld world) {
				return GuardDiplomacyManager.isWhitelisted(world.getServer(), this.ownerUuid, player.getUuid());
			}
		}
		return false;
	}

	private void keepBowRange() {
		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive()) {
			return;
		}

		double distanceSq = this.squaredDistanceTo(target);
		if (distanceSq < 100.0D) {
			Vec3d retreat = this.getEntityPos().subtract(target.getEntityPos());
			if (retreat.lengthSquared() < 1.0E-4D) {
				retreat = new Vec3d(this.getRandom().nextDouble() - 0.5D, 0.0D, this.getRandom().nextDouble() - 0.5D);
			}
			Vec3d destination = this.getEntityPos().add(retreat.normalize().multiply(6.0D));
			if (this.canTargetWithinZone(new BlockPos((int) destination.x, (int) destination.y, (int) destination.z))) {
				this.getNavigation().startMovingTo(destination.x, destination.y, destination.z, 1.15D);
			}
		} else if (distanceSq > 225.0D) {
			this.getNavigation().startMovingTo(target, 1.0D);
		} else {
			this.getNavigation().stop();
		}
	}

	@Override
	public boolean tryAttack(ServerWorld world, Entity target) {
		boolean attacked = super.tryAttack(world, target);
		if (attacked && !this.getMainHandStack().isOf(Items.BOW)) {
			this.swingHand(Hand.MAIN_HAND);
		}
		return attacked;
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		ItemStack bow = this.getMainHandStack();
		if (!bow.isOf(Items.BOW) || !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}

		ItemStack arrowStack = this.getProjectileType(bow);
		if (arrowStack.isEmpty()) {
			arrowStack = new ItemStack(Items.ARROW);
		}

		PersistentProjectileEntity arrow = new GuardArrowEntity(world, this, arrowStack.copyWithCount(1), bow.copy());
		arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
		int power = this.getEnchantmentLevel(bow, Enchantments.POWER);
		double baseDamage = 2.0D + Math.max(0.0F, pullProgress) * 1.6D + (double) power * 0.6D;
		arrow.setDamage(baseDamage);
		if (this.getEnchantmentLevel(bow, Enchantments.FLAME) > 0) {
			arrow.setOnFireFor(5.0F);
		}
		double dx = target.getX() - this.getX();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double dy = target.getBodyY(0.3333333333333333D) - arrow.getY() + horizontal * 0.2D;
		arrow.setVelocity(dx, dy, dz, 1.6F, (float) (14 - this.getEntityWorld().getDifficulty().getId() * 4));
		this.playSound(SoundEvents.ENTITY_SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
		world.spawnEntity(arrow);
	}

	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		if (source.getAttacker() instanceof GuardEntity attackerGuard
			&& this.ownerUuid != null
			&& attackerGuard.isOwnedBy(this.ownerUuid)) {
			return false;
		}
		boolean activelyBlockingWithShield = this.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD)
			&& this.isUsingItem()
			&& this.getActiveHand() == Hand.OFF_HAND;
		if (this.ownerUuid != null && GuardVillagersMod.hasShieldUpgrade(world, this.ownerUuid) && activelyBlockingWithShield) {
			amount *= 0.55F;
		}
		boolean damaged = super.damage(world, source, amount);
		if (!damaged) {
			return false;
		}

		this.combatCooldown = 160;
		if (source.getAttacker() instanceof LivingEntity attacker) {
			if (attacker instanceof PlayerEntity playerAttacker && this.ownerUuid != null && this.ownerUuid.equals(playerAttacker.getUuid())) {
				this.clearCombatTarget();
				return true;
			}
			if (attacker instanceof PlayerEntity player && this.isCreativeOperator(player)) {
				return true;
			}
			if (!this.isAlly(attacker) && this.canSee(attacker)) {
				this.setPriorityTarget(attacker);
				this.rallyNearbyGuards(world, attacker);
			}
			if (attacker instanceof PlayerEntity player) {
				GuardReputationManager.recordGuardHarm(world, player.getUuid());
			}
		}

		return true;
	}

	private boolean isCreativeOperator(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity serverPlayer)) {
			return false;
		}
		if (!serverPlayer.getAbilities().creativeMode) {
			return false;
		}
		if (!(serverPlayer.getCommandSource().getPermissions() instanceof LeveledPermissionPredicate leveled)) {
			return false;
		}
		return leveled.getLevel().isAtLeast(PermissionLevel.GAMEMASTERS);
	}

	@Override
	public boolean onKilledOther(ServerWorld world, LivingEntity other, DamageSource damageSource) {
		boolean result = super.onKilledOther(world, other, damageSource);
		if (other instanceof HostileEntity) {
			this.addExperience(20);
		} else {
			this.addExperience(8);
		}

		if (this.ownerUuid != null) {
			GuardReputationManager.recordHostileKill(world, this.ownerUuid, other);
			if (other instanceof RaiderEntity) {
				GuardReputationManager.recordRaidDefense(world, this.ownerUuid);
			}
		}
		if (other instanceof PlayerEntity player) {
			GuardReputationManager.resetReputation(world, player.getUuid());
		}
		return result;
	}

	private void rallyNearbyGuards(ServerWorld world, LivingEntity attacker) {
		UUID owner = this.ownerUuid;
		double detectionRange = Math.max(8.0D, this.getAttributeValue(EntityAttributes.FOLLOW_RANGE));
		double detectionRangeSq = detectionRange * detectionRange;
		List<GuardEntity> nearby = world.getEntitiesByClass(GuardEntity.class, this.getBoundingBox().expand(detectionRange), guard -> {
			if (!guard.isAlive()) {
				return false;
			}
			return owner != null
				&& owner.equals(guard.ownerUuid)
				&& guard != this
				&& guard.squaredDistanceTo(this) <= detectionRangeSq;
		});

		for (GuardEntity guard : nearby) {
			guard.receiveAlertTarget(attacker, world.getTime());
		}
	}

	@Override
	protected void dropEquipment(ServerWorld world, DamageSource source, boolean causedByPlayer) {
		this.dropPlayerGear(world, EquipmentSlot.MAINHAND, this.playerMainHand);
		this.dropPlayerGear(world, EquipmentSlot.HEAD, this.playerArmor.getOrDefault(EquipmentSlot.HEAD, false));
		this.dropPlayerGear(world, EquipmentSlot.CHEST, this.playerArmor.getOrDefault(EquipmentSlot.CHEST, false));
		this.dropPlayerGear(world, EquipmentSlot.LEGS, this.playerArmor.getOrDefault(EquipmentSlot.LEGS, false));
		this.dropPlayerGear(world, EquipmentSlot.FEET, this.playerArmor.getOrDefault(EquipmentSlot.FEET, false));
	}

	private void dropPlayerGear(ServerWorld world, EquipmentSlot slot, boolean playerProvided) {
		if (!playerProvided) {
			return;
		}
		ItemStack stack = this.getEquippedStack(slot);
		if (!stack.isEmpty()) {
			this.dropStack(world, stack.copy());
		}
	}

	private void applyLevelModifiers() {
		int level = this.getLevel();
		double healthBonus = (level - 1) * 2.0D;
		double damageBonus = (level - 1) * 0.5D;
		double speedBonus = (level - 1) * 0.01D;

		this.updateAttributeModifier(EntityAttributes.MAX_HEALTH, LEVEL_HEALTH_MODIFIER_ID, healthBonus);
		this.updateAttributeModifier(EntityAttributes.ATTACK_DAMAGE, LEVEL_DAMAGE_MODIFIER_ID, damageBonus);
		this.updateAttributeModifier(EntityAttributes.MOVEMENT_SPEED, LEVEL_SPEED_MODIFIER_ID, speedBonus);
		if (this.getHealth() > this.getMaxHealth()) {
			this.setHealth(this.getMaxHealth());
		}
	}

	private void updateAttributeModifier(RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute, Identifier modifierId, double value) {
		EntityAttributeInstance instance = this.getAttributeInstance(attribute);
		if (instance == null) {
			return;
		}
		instance.removeModifier(modifierId);
		if (value != 0.0D) {
			instance.addPersistentModifier(new EntityAttributeModifier(modifierId, value, EntityAttributeModifier.Operation.ADD_VALUE));
		}
	}

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		view.putInt(ROLE_KEY, this.dataTracker.get(ROLE));
		view.putInt(BEHAVIOR_KEY, this.dataTracker.get(BEHAVIOR));
		view.putInt(FORMATION_KEY, this.dataTracker.get(FORMATION));
		view.putBoolean(STAYING_KEY, this.staying);
		view.putString(OWNER_KEY, this.ownerUuid == null ? "" : this.ownerUuid.toString());
		view.putString(SQUAD_ID_KEY, this.squadId == null ? "" : this.squadId.toString());
		view.putBoolean(SQUAD_LEADER_KEY, this.isSquadLeader());
		view.putInt(EXPERIENCE_KEY, this.getExperience());
		view.putBoolean(PLAYER_MAINHAND_KEY, this.playerMainHand);
		view.putBoolean(PLAYER_HELMET_KEY, this.playerArmor.getOrDefault(EquipmentSlot.HEAD, false));
		view.putBoolean(PLAYER_CHEST_KEY, this.playerArmor.getOrDefault(EquipmentSlot.CHEST, false));
		view.putBoolean(PLAYER_LEGS_KEY, this.playerArmor.getOrDefault(EquipmentSlot.LEGS, false));
		view.putBoolean(PLAYER_FEET_KEY, this.playerArmor.getOrDefault(EquipmentSlot.FEET, false));

		boolean hasHome = this.home != null;
		view.putBoolean(HAS_HOME_KEY, hasHome);
		if (hasHome) {
			view.putInt(HOME_X_KEY, this.home.getX());
			view.putInt(HOME_Y_KEY, this.home.getY());
			view.putInt(HOME_Z_KEY, this.home.getZ());
		}
		view.putInt(PATROL_RADIUS_KEY, this.patrolRadius);
		view.putInt(GROUP_INDEX_KEY, this.groupIndex);
		view.putInt(GROUP_COLUMN_KEY, this.groupColumn);
		view.putString(GROUP_NAME_KEY, this.getGroupName());
		view.putString(SKIN_PROFILE_KEY, this.skinProfileId);
		view.putString(GENERATED_NAME_KEY, this.generatedName);
		view.putString(DISPLAY_NAME_KEY, this.displayName);
		view.putString(SPECIAL_PROFILE_KEY, this.specialProfile.id());
		view.putLong(NEXT_NOTCH_APPLE_TICK_KEY, this.nextNotchAppleTick);
		boolean hasLastLand = this.lastLandPos != null;
		view.putBoolean(HAS_LAST_LAND_KEY, hasLastLand);
		if (hasLastLand) {
			view.putInt(LAST_LAND_X_KEY, this.lastLandPos.getX());
			view.putInt(LAST_LAND_Y_KEY, this.lastLandPos.getY());
			view.putInt(LAST_LAND_Z_KEY, this.lastLandPos.getZ());
		}
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		this.dataTracker.set(ROLE, view.getInt(ROLE_KEY, GuardRole.SWORDSMAN.getId()));
		this.dataTracker.set(BEHAVIOR, view.getInt(BEHAVIOR_KEY, GuardBehavior.DEFENSIVE.getId()));
		this.dataTracker.set(FORMATION, FormationType.FOLLOW.getId());
		this.staying = view.getBoolean(STAYING_KEY, false);
		this.ownerUuid = parseUuid(view.getString(OWNER_KEY, ""));
		this.squadId = parseUuid(view.getString(SQUAD_ID_KEY, ""));
		this.dataTracker.set(SQUAD_LEADER, view.getBoolean(SQUAD_LEADER_KEY, false));
		this.dataTracker.set(EXPERIENCE, Math.max(0, view.getInt(EXPERIENCE_KEY, 0)));
		this.playerMainHand = view.getBoolean(PLAYER_MAINHAND_KEY, false);
		this.playerArmor.put(EquipmentSlot.HEAD, view.getBoolean(PLAYER_HELMET_KEY, false));
		this.playerArmor.put(EquipmentSlot.CHEST, view.getBoolean(PLAYER_CHEST_KEY, false));
		this.playerArmor.put(EquipmentSlot.LEGS, view.getBoolean(PLAYER_LEGS_KEY, false));
		this.playerArmor.put(EquipmentSlot.FEET, view.getBoolean(PLAYER_FEET_KEY, false));

		if (view.getBoolean(HAS_HOME_KEY, false)) {
			this.home = new BlockPos(
				view.getInt(HOME_X_KEY, 0),
				view.getInt(HOME_Y_KEY, 0),
				view.getInt(HOME_Z_KEY, 0)
			);
		} else {
			this.home = null;
		}
		this.patrolRadius = MathHelper.clamp(view.getInt(PATROL_RADIUS_KEY, 0), 0, 128);
		// Read new keys first, fall back to legacy keys for migration
		int readGroupIndex = view.getInt(GROUP_INDEX_KEY, Integer.MIN_VALUE);
		if (readGroupIndex == Integer.MIN_VALUE) {
			readGroupIndex = view.getInt(LEGACY_HIERARCHY_ROW_KEY, 0);
		}
		this.groupIndex = MathHelper.clamp(readGroupIndex, MIN_GROUP_INDEX, MAX_GROUP_INDEX);
		int readGroupColumn = view.getInt(GROUP_COLUMN_KEY, -1);
		if (readGroupColumn < 0) {
			readGroupColumn = view.getInt(LEGACY_HIERARCHY_COLUMN_KEY, 1);
		}
		this.groupColumn = MathHelper.clamp(readGroupColumn, 0, 2);
		String readGroupName = view.getString(GROUP_NAME_KEY, "");
		if (readGroupName.isEmpty()) {
			readGroupName = view.getString(LEGACY_HIERARCHY_ROLE_KEY, "Alpha");
		}
		this.setGroupName(readGroupName);
		this.setSkinProfileId(view.getString(SKIN_PROFILE_KEY, ""));
		this.generatedName = sanitizeName(view.getString(GENERATED_NAME_KEY, ""));
		this.displayName = sanitizeName(view.getString(DISPLAY_NAME_KEY, ""));
		if (this.displayName.isBlank()) {
			this.displayName = this.generatedName;
		}
		this.specialProfile = SpecialProfile.fromId(view.getString(SPECIAL_PROFILE_KEY, ""));
		this.nextNotchAppleTick = Math.max(0L, view.getLong(NEXT_NOTCH_APPLE_TICK_KEY, 0L));
		if (view.getBoolean(HAS_LAST_LAND_KEY, false)) {
			this.lastLandPos = new BlockPos(
				view.getInt(LAST_LAND_X_KEY, 0),
				view.getInt(LAST_LAND_Y_KEY, 0),
				view.getInt(LAST_LAND_Z_KEY, 0)
			);
		}
		this.applyLevelModifiers();
		this.updateCombatGoals();
		if (this.getEntityWorld() instanceof ServerWorld world) {
			this.ensureIdentityForOwner();
			GuardOwnershipIndex.track(this);
		}
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (reason == null || reason.shouldDestroy()) {
			GuardOwnershipIndex.untrack(this);
		}
		super.remove(reason);
	}

	private UUID parseUuid(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private void ensureIdentityForOwner() {
		if (this.ownerUuid == null || !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}
		if (!this.generatedName.isBlank()) {
			if (this.displayName.isBlank()) {
				this.displayName = this.generatedName;
			}
			return;
		}
		Set<String> usedNames = this.collectUsedOwnerNames(world, this.ownerUuid);
		NameAssignment generated = this.rollUniqueName(world.getRandom(), usedNames);
		this.generatedName = generated.name();
		this.displayName = generated.name();
		this.specialProfile = generated.profile();
		this.nextNotchAppleTick = 0L;
	}

	private Set<String> collectUsedOwnerNames(ServerWorld world, UUID ownerId) {
		Set<String> used = new HashSet<>();
		for (GuardEntity guard : GuardOwnershipIndex.getOwnedGuards(world.getServer(), ownerId)) {
			if (guard == this) {
				continue;
			}
			String taken = sanitizeName(guard.displayName.isBlank() ? guard.generatedName : guard.displayName);
			if (!taken.isBlank()) {
				used.add(taken.toLowerCase(Locale.ROOT));
			}
		}
		return used;
	}

	private NameAssignment rollUniqueName(Random random, Set<String> usedNames) {
		for (int attempt = 0; attempt < 2048; attempt++) {
			NameAssignment candidate = this.rollWeightedName(random);
			String normalized = candidate.name().toLowerCase(Locale.ROOT);
			if (usedNames.add(normalized)) {
				return candidate;
			}
		}
		for (String fallback : GuardNamePool.REGULAR_NAMES) {
			String normalized = fallback.toLowerCase(Locale.ROOT);
			if (usedNames.add(normalized)) {
				return new NameAssignment(fallback, SpecialProfile.NONE);
			}
		}
		int index = 2;
		String fallback = "Guard";
		while (usedNames.contains((fallback + index).toLowerCase(Locale.ROOT))) {
			index++;
		}
		return new NameAssignment(fallback + index, SpecialProfile.NONE);
	}

	private NameAssignment rollWeightedName(Random random) {
		if (random.nextInt(NOTCH_ROLL_CHANCE) == 0) {
			return new NameAssignment(SPECIAL_NOTCH_NAME, SpecialProfile.NOTCH);
		}
		boolean jackBlack = random.nextInt(JACK_BLACK_ROLL_CHANCE) == 0;
		boolean jasonMomoa = random.nextInt(JASON_MOMOA_ROLL_CHANCE) == 0;
		if (jackBlack || jasonMomoa) {
			if (jackBlack && jasonMomoa) {
				return random.nextBoolean()
					? new NameAssignment(SPECIAL_JACK_BLACK_NAME, SpecialProfile.JACK_BLACK)
					: new NameAssignment(SPECIAL_JASON_MOMOA_NAME, SpecialProfile.JASON_MOMOA);
			}
			return jackBlack
				? new NameAssignment(SPECIAL_JACK_BLACK_NAME, SpecialProfile.JACK_BLACK)
				: new NameAssignment(SPECIAL_JASON_MOMOA_NAME, SpecialProfile.JASON_MOMOA);
		}
		return new NameAssignment(GuardNamePool.randomRegularName(random), SpecialProfile.NONE);
	}

	private String getCallsign() {
		String resolvedDisplayName = sanitizeName(this.displayName);
		if (!resolvedDisplayName.isBlank()) {
			return resolvedDisplayName;
		}
		String resolvedGeneratedName = sanitizeName(this.generatedName);
		if (!resolvedGeneratedName.isBlank()) {
			return resolvedGeneratedName;
		}
		return "Guard";
	}

	private static String sanitizeName(String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		String trimmed = name.trim();
		if (trimmed.length() <= NAME_MAX_LENGTH) {
			return trimmed;
		}
		return trimmed.substring(0, NAME_MAX_LENGTH);
	}

	private void updateCombatGoals() {
		if (this.meleeGoal == null || this.rangedGoal == null) {
			return;
		}

		this.goalSelector.remove(this.meleeGoal);
		this.goalSelector.remove(this.rangedGoal);
		if (this.getRole() == GuardRole.BOWMAN) {
			this.goalSelector.add(2, this.rangedGoal);
		} else {
			this.goalSelector.add(2, this.meleeGoal);
		}
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.ENTITY_VILLAGER_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_VILLAGER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_VILLAGER_DEATH;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	private record NameAssignment(String name, SpecialProfile profile) {
	}

	private enum SpecialProfile {
		NONE(""),
		NOTCH("notch"),
		JACK_BLACK("jack_black"),
		JASON_MOMOA("jason_momoa");

		private final String id;

		SpecialProfile(String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		public boolean hasDiamondArmorBonus() {
			return this == JACK_BLACK || this == JASON_MOMOA;
		}

		public static SpecialProfile fromId(String id) {
			if (id == null || id.isBlank()) {
				return NONE;
			}
			for (SpecialProfile value : values()) {
				if (value.id.equalsIgnoreCase(id.trim())) {
					return value;
				}
			}
			return NONE;
		}
	}

	private record ArmorDefinition(EquipmentSlot slot, int score) {
	}
}
