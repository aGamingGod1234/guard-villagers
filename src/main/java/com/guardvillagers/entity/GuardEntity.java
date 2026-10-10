package com.guardvillagers.entity;

import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardPlayerUpgrades;
import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.hierarchy.GuardRank;
import com.guardvillagers.entity.ai.GuardAiController;
import com.guardvillagers.entity.ai.GuardAiIntent;
import com.guardvillagers.entity.ai.GuardBehaviorExecutor;
import com.guardvillagers.entity.ai.GuardMovementSlotResolver;
import com.guardvillagers.entity.ai.TargetScoring;
import com.guardvillagers.entity.goal.CrowdControlGoal;
import com.guardvillagers.entity.goal.ElectLeaderGoal;
import com.guardvillagers.entity.goal.FormationFollowOwnerGoal;
import com.guardvillagers.entity.goal.GuardBowAttackGoal;
import com.guardvillagers.entity.goal.GuardHomeAnchorGoal;
import com.guardvillagers.entity.goal.GuardIdleGoal;
import com.guardvillagers.entity.goal.GuardMeleeAttackGoal;
import com.guardvillagers.entity.goal.GuardRallyGoal;
import com.guardvillagers.entity.goal.PerimeterPatrolGoal;
import com.guardvillagers.entity.goal.RaidTacticsGoal;
import com.guardvillagers.entity.goal.ReturnToLandGoal;
import com.guardvillagers.entity.goal.SeekAirGoal;
import com.guardvillagers.entity.goal.TacticalRetreatGoal;
import com.guardvillagers.navigation.GuardGrounding;
import com.guardvillagers.navigation.GuardNavigation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.guardvillagers.entity.projectile.GuardArrowEntity;

public class GuardEntity extends PathfinderMob implements RangedAttackMob {
	private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> BEHAVIOR = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> FORMATION = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> SQUAD_LEADER = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> EXPERIENCE = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> DEBUG_ACTIVE = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Optional<BlockPos>> SYNCED_HOME = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.OPTIONAL_BLOCK_POS);
	private static final EntityDataAccessor<Integer> SYNCED_PATROL_RADIUS = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> SYNCED_OWNER = SynchedEntityData
			.defineId(GuardEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);
	private static final EntityDataAccessor<Integer> SYNCED_GROUP_INDEX = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.INT);
	/** {@link GuardRank} id of owned guards, synced for a nameplate badge. */
	private static final EntityDataAccessor<Byte> RANK = SynchedEntityData.defineId(GuardEntity.class,
			EntityDataSerializers.BYTE);
	/** Only the type (biome robe) is used; profession and level stay at their defaults. */
	private static final EntityDataAccessor<VillagerData> SYNCED_VILLAGER_DATA = SynchedEntityData.defineId(
			GuardEntity.class, EntityDataSerializers.VILLAGER_DATA);

	private static final Identifier LEVEL_HEALTH_MODIFIER_ID = GuardVillagersMod.id("guard_level_health");
	private static final Identifier LEVEL_DAMAGE_MODIFIER_ID = GuardVillagersMod.id("guard_level_damage");
	private static final Identifier LEVEL_SPEED_MODIFIER_ID = GuardVillagersMod.id("guard_level_speed");
	private static final Identifier FOLLOW_CATCH_UP_SPEED_MODIFIER_ID = GuardVillagersMod.id("guard_follow_catch_up_speed");
	private static final double FOLLOW_CATCH_UP_SPEED_BONUS = 0.35D;
	private static final int INITIAL_SPREAD_TICKS = 5;
	private static final double INITIAL_SPREAD_SPEED = 1.0D;
	private static final double INITIAL_SPREAD_COMPLETE_DISTANCE_SQUARED = 0.45D * 0.45D;
	/** Default water pathfinding malus; goals that temporarily allow swimming restore this value. */
	public static final float DEFAULT_WATER_MALUS = 8.0F;
	private static final int HEAL_AFTER_HURT_TICKS = 60;
	private static final int PEEL_REBROADCAST_TICKS = 20;
	private static final int UNSTICK_SAMPLES = 5;

	private static final String OWNER_KEY = "GuardOwner";
	private static final String ROLE_KEY = "GuardRole";
	private static final String BEHAVIOR_KEY = "GuardBehavior";
	private static final String FORMATION_KEY = "GuardFormation";
	private static final String STAYING_KEY = "GuardStaying";
	private static final String FOLLOW_OVERRIDE_KEY = "GuardFollowOverride";
	private static final String SQUAD_ID_KEY = "GuardSquadId";
	private static final String SQUAD_LEADER_KEY = "GuardSquadLeader";
	private static final String EXPERIENCE_KEY = "GuardExperience";
	private static final String PLAYER_MAINHAND_KEY = "PlayerMainHand";
	private static final String PLAYER_HELMET_KEY = "PlayerHelmet";
	private static final String PLAYER_CHEST_KEY = "PlayerChest";
	private static final String PLAYER_LEGS_KEY = "PlayerLegs";
	private static final String PLAYER_FEET_KEY = "PlayerFeet";
	private static final String LOADOUT_ARMOR_LEVEL_KEY = "LoadoutArmorLevel";
	private static final String LOADOUT_WEAPON_LEVEL_KEY = "LoadoutWeaponLevel";
	private static final String LOADOUT_SUPPORT_LEVEL_KEY = "LoadoutSupportLevel";
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
	private static final String SPECIAL_PROFILE_KEY = "SpecialProfile";
	private static final String NOTCH_APPLE_COOLDOWN_UNTIL_KEY = "NotchAppleCooldownUntil";
	// Legacy keys for migration
	private static final String LEGACY_HIERARCHY_ROW_KEY = "HierarchyRow";
	private static final String LEGACY_HIERARCHY_COLUMN_KEY = "HierarchyColumn";
	private static final String LEGACY_HIERARCHY_ROLE_KEY = "HierarchyRole";
	private static final String LAST_LAND_X_KEY = "LastLandX";
	private static final String LAST_LAND_Y_KEY = "LastLandY";
	private static final String LAST_LAND_Z_KEY = "LastLandZ";
	private static final String HAS_STAY_ORIGIN_KEY = "HasStayOrigin";
	private static final String STAY_ORIGIN_X_KEY = "StayOriginX";
	private static final String STAY_ORIGIN_Y_KEY = "StayOriginY";
	private static final String STAY_ORIGIN_Z_KEY = "StayOriginZ";
	public static final int STAY_RADIUS = 3;
	private static final String HAS_LAST_LAND_KEY = "HasLastLand";
	private static final String HOLD_POSITION_KEY = "GuardHoldPosition";
	private static final String HOLD_X_KEY = "HoldX";
	private static final String HOLD_Y_KEY = "HoldY";
	private static final String HOLD_Z_KEY = "HoldZ";
	private static final String VILLAGER_DATA_KEY = "GuardVillagerData";
	private static final String RANK_KEY = "GuardRank";
	private static final String HIRED_AT_KEY = "GuardHiredAt";
	private static final int MIN_GROUP_INDEX = -1;
	private static final int MAX_GROUP_INDEX = Integer.MAX_VALUE / 2;
	private static final int MISSING_LOADOUT_LEVEL = -1;
	private static final String DEFAULT_UNASSIGNED_GROUP_NAME = "Unassigned";
	private static final int MAX_SKIN_PROFILE_LENGTH = 64;
	private static final String DEBUG_NAME_PREFIX = "[DBG] ";
	private static final int SPECIAL_PROFILE_NONE = 0;
	private static final int SPECIAL_PROFILE_NOTCH = 1;
	private static final int SPECIAL_PROFILE_JACK_BLACK = 2;
	private static final int SPECIAL_PROFILE_JASON_MOMOA = 3;
	private static final long NOTCH_APPLE_COOLDOWN_TICKS = 15L * 60L * 20L;
	private static final float NOTCH_APPLE_TRIGGER_HEALTH_RATIO = 0.30F;
	private static final int NOTCH_ROLL = 500;
	private static final int JACK_BLACK_ROLL = 400;
	private static final int JASON_MOMOA_ROLL = 400;

	private static final String[] FIRST_NAME_POOL = {
			"Aaron", "Abel", "Abigail", "Adam", "Adrian", "Aiden", "Alan", "Albert", "Alec", "Alexa",
			"Alexander", "Alice", "Alicia", "Allison", "Amanda", "Amelia", "Amy", "Andrea", "Andrew", "Angela",
			"Anna", "Anthony", "Arthur", "Ashley", "Aubrey", "Audrey", "Austin", "Ava", "Barbara", "Beatrice",
			"Benjamin", "Bethany", "Blake", "Brandon", "Brenda", "Brian", "Brianna", "Brittany", "Brooke", "Caleb",
			"Cameron", "Carla", "Carlos", "Carmen", "Caroline", "Carter", "Catherine", "Charles", "Charlotte", "Chloe",
			"Chris", "Christian", "Christina", "Claire", "Clara", "Cole", "Colin", "Connor", "Courtney", "Crystal",
			"Daisy", "Dakota", "Daniel", "Danielle", "David", "Dawn", "Dean", "Deborah", "Dennis", "Derek",
			"Diana", "Dominic", "Donna", "Dylan", "Eleanor", "Elena", "Eli", "Elijah", "Elizabeth", "Ella",
			"Emily", "Emma", "Eric", "Erica", "Ethan", "Eva", "Evan", "Evelyn", "Faith", "Felix",
			"Fiona", "Frank", "Gabriel", "Gavin", "George", "Georgia", "Grace", "Grant", "Gregory", "Hailey",
			"Hannah", "Harper", "Hazel", "Heather", "Helen", "Henry", "Holly", "Hunter", "Ian", "Irene",
			"Isaac", "Isabel", "Isabella", "Jack", "Jacob", "Jade", "Jamal", "James", "Jamie", "Jared",
			"Jason", "Jasmine", "Jayden", "Jeffrey", "Jenna", "Jennifer", "Jeremy", "Jerry", "Jesse", "Jessica",
			"Joan", "Jordan", "Joseph", "Joshua", "Joyce", "Juan", "Julia", "Julian", "Justin", "Kaitlyn",
			"Karen", "Katherine", "Kathleen", "Kayla", "Keith", "Kelly", "Kevin", "Kimberly", "Kyle", "Lance",
			"Laura", "Lauren", "Leah", "Leo", "Liam", "Lillian", "Lily", "Logan", "Lucas", "Lucy",
			"Luke", "Madeline", "Madison", "Maya", "Megan", "Melanie", "Michael", "Michelle", "Mila", "Molly",
			"Natalie", "Nathan", "Nicholas", "Nicole", "Noah", "Nora", "Olivia", "Owen", "Paige", "Pamela",
			"Patrick", "Paul", "Peter", "Rachel", "Rebecca", "Richard", "Robert", "Ryan", "Samantha", "Sarah",
			"Scott", "Sean", "Sophia", "Stephen", "Taylor", "Thomas", "Tristan", "Tyler", "Victoria", "William",
			"Wyatt", "Zachary"
	};

	private static final String[] LAST_NAME_POOL = {
			"Abbott", "Adams", "Alexander", "Allen", "Anderson", "Armstrong", "Atkins", "Austin", "Bailey", "Baker",
			"Barnes", "Barrett", "Bennett", "Bishop", "Black", "Blair", "Bowman", "Boyd", "Bradley", "Brooks",
			"Brown", "Bryant", "Butler", "Campbell", "Carter", "Castillo", "Chambers", "Chapman", "Clark", "Coleman",
			"Collins", "Cook", "Cooper", "Cox", "Crawford", "Cruz", "Cunningham", "Daniels", "Davis", "Dean",
			"Dixon", "Douglas", "Duncan", "Edwards", "Ellis", "Evans", "Ferguson", "Fisher", "Fleming", "Flores",
			"Ford", "Foster", "Fox", "Freeman", "Garcia", "Gardner", "Garrett", "Gibson", "Gomez", "Gonzalez",
			"Gordon", "Graham", "Grant", "Gray", "Green", "Griffin", "Hall", "Hamilton", "Harris", "Harrison",
			"Hart", "Hayes", "Henderson", "Hernandez", "Hill", "Hoffman", "Howard", "Hudson", "Hughes", "Hunt",
			"Jackson", "James", "Jenkins", "Johnson", "Jordan", "Kelly", "Kennedy", "Kim", "King", "Knight",
			"Lane", "Larson", "Lawrence", "Lee", "Lewis", "Long", "Lopez", "Marshall", "Martin", "Martinez",
			"Mason", "Matthews", "Miller", "Mitchell", "Moore", "Morales", "Morgan", "Morris", "Murphy", "Myers",
			"Nelson", "Nguyen", "Nichols", "Ortiz", "Owens", "Palmer", "Parker", "Patel", "Patterson", "Payne",
			"Perez", "Perry", "Peterson", "Phillips", "Pierce", "Porter", "Powell", "Price", "Ramirez", "Reed",
			"Reyes", "Reynolds", "Rice", "Richardson", "Rivera", "Roberts", "Robinson", "Rodriguez", "Rogers", "Ross",
			"Russell", "Sanchez", "Sanders", "Scott", "Shaw", "Simmons", "Smith", "Snyder", "Spencer", "Stevens",
			"Stewart", "Stone", "Sullivan", "Taylor", "Thomas", "Thompson", "Torres", "Turner", "Vargas", "Vasquez",
			"Walker", "Wallace", "Ward", "Washington", "Watkins", "Watson", "Weaver", "Webb", "Wells", "West",
			"Wheeler", "White", "Williams", "Wilson", "Wood", "Woods", "Wright", "Young"
	};

	/**
	 * Guards in different dimensions (Overworld / Nether / End) tick on separate
	 * threads, and hire-identity registration hooks are called from each thread's
	 * entity tick and from NBT load. A plain HashMap would be corrupted by
	 * concurrent put/remove. Both the outer map and the inner Set must be
	 * thread-safe.
	 */
	private static final Map<UUID, Set<String>> OWNER_USED_NAMES = new ConcurrentHashMap<>();

	private static final Map<Item, Integer> SWORD_SCORE = Map.ofEntries(
			Map.entry(Items.WOODEN_SWORD, 1),
			Map.entry(Items.STONE_SWORD, 2),
			Map.entry(Items.GOLDEN_SWORD, 2),
			Map.entry(Items.IRON_SWORD, 3),
			Map.entry(Items.DIAMOND_SWORD, 4),
			Map.entry(Items.NETHERITE_SWORD, 5));

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
			Map.entry(Items.NETHERITE_BOOTS, new ArmorDefinition(EquipmentSlot.FEET, 5)));

	private final GuardAiController aiController;

	private UUID ownerUuid;
	private UUID squadId;
	private boolean staying;
	private BlockPos stayOrigin;
	private boolean followOverride;
	private BlockPos home;
	private int patrolRadius = 0;
	private BlockPos lastLandPos;
	private BlockPos lastLandCheckPos;
	private int groupIndex = MIN_GROUP_INDEX;
	private int groupColumn = 1;
	private String groupName = DEFAULT_UNASSIGNED_GROUP_NAME;
	private String skinProfileId = "";
	private String generatedName = "";
	private int specialProfile = SPECIAL_PROFILE_NONE;
	private long notchAppleCooldownUntil;
	private UUID lastRegisteredOwner;
	private String lastRegisteredOwnerName = "";
	private boolean playerMainHand;
	private boolean catchUpSpeedActive;
	private boolean initialSpreadResolved;
	private BlockPos pendingInitialSpreadTarget;
	private int loadoutArmorLevel;
	private int loadoutWeaponLevel;
	private int loadoutSupportLevel;
	private final EnumMap<EquipmentSlot, Boolean> playerArmor = new EnumMap<>(EquipmentSlot.class);
	private final GuardMovementSlotResolver.SlotCache slotCache = new GuardMovementSlotResolver.SlotCache();
	private boolean shieldRequested;
	private BlockPos unstickSamplePos;
	private boolean gearChecked;
	private BlockPos holdOrigin;
	private boolean villagerTypeChosen;
	private int unstickSamples;
	private UUID lastRallyAttackerUuid;
	private long lastRallyTick = Long.MIN_VALUE;
	/** Game time this guard was hired; 0 for village guards. Seniority tie-breaker (longer service ranks higher). */
	private long hiredAtTick;

	public GuardEntity(EntityType<? extends PathfinderMob> entityType, net.minecraft.world.level.Level world) {
		super(entityType, world);
		this.aiController = new GuardAiController(this);
		this.getNavigation().setCanOpenDoors(true);
		this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, DEFAULT_WATER_MALUS);
		// Hazards: never path through fire/magma/campfires (lava, cactus, berry bushes and powder
		// snow are already blocked by default), and give their neighbours a heavy cost.
		this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.FIRE, -1.0F);
		this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.FIRE_IN_NEIGHBOR, 16.0F);
		this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGING_IN_NEIGHBOR, 16.0F);
		this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGE_CAUTIOUS, 8.0F);
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
				EquipmentSlot.FEET)) {
			this.playerArmor.put(slot, false);
		}
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0D)
				.add(Attributes.MOVEMENT_SPEED, 0.32D)
				.add(Attributes.ATTACK_DAMAGE, 5.0D)
				.add(Attributes.FOLLOW_RANGE, 32.0D);
	}

	@Override
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor world, DifficultyInstance difficulty, EntitySpawnReason spawnReason,
			SpawnGroupData entityData) {
		SpawnGroupData data = super.finalizeSpawn(world, difficulty, spawnReason, entityData);
		this.chooseVillagerTypeFromBiome();
		if ((spawnReason == EntitySpawnReason.SPAWN_ITEM_USE || spawnReason == EntitySpawnReason.DISPENSER
				|| spawnReason == EntitySpawnReason.COMMAND) && this.getMainHandItem().isEmpty()) {
			this.applyNaturalLoadout(world.getLevel());
			this.setBehavior(GuardBehavior.random(world.getLevel().getRandom()));
		}
		return data;
	}

	@Override
	protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(net.minecraft.world.level.Level world) {
		return new com.guardvillagers.navigation.GuardNavigation(this, world);
	}

	@Override
	protected void registerGoals() {
		// Both combat goals are always registered; each checks the held weapon, so a bowman whose
		// bow was replaced still fights instead of standing still with a target.
		GuardMeleeAttackGoal meleeGoal = new GuardMeleeAttackGoal(this, 1.2D);
		GuardBowAttackGoal rangedGoal = new GuardBowAttackGoal(this, 1.0D, 15.0F);

		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new SeekAirGoal(this, 1.3D));
		this.goalSelector.addGoal(2, new ReturnToLandGoal(this, 1.2D));
		this.goalSelector.addGoal(3, new TacticalRetreatGoal(this, 1.35D));
		this.goalSelector.addGoal(4, new OpenDoorGoal(this, true));
		this.goalSelector.addGoal(5, meleeGoal);
		this.goalSelector.addGoal(5, rangedGoal);
		this.goalSelector.addGoal(6, new GuardRallyGoal(this, 1.2D));
		this.goalSelector.addGoal(7, new FormationFollowOwnerGoal(this, 1.0D));
		this.goalSelector.addGoal(8, new GuardHomeAnchorGoal(this, 1.0D));
		this.goalSelector.addGoal(9, new RaidTacticsGoal(this, 1.2D));
		this.goalSelector.addGoal(10, new PerimeterPatrolGoal(this, 1.0D));
		this.goalSelector.addGoal(11, new CrowdControlGoal(this, 1.0D));
		this.goalSelector.addGoal(12, new GuardIdleGoal(this, 0.8D));
		this.goalSelector.addGoal(13, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(14, new RandomLookAroundGoal(this));

		this.targetSelector.addGoal(1, new ElectLeaderGoal(this, 48.0D));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ROLE, GuardRole.SWORDSMAN.getId());
		builder.define(BEHAVIOR, GuardBehavior.DEFENSIVE.getId());
		builder.define(FORMATION, FormationType.FOLLOW.getId());
		builder.define(SQUAD_LEADER, false);
		builder.define(EXPERIENCE, 0);
		builder.define(DEBUG_ACTIVE, false);
		builder.define(SYNCED_HOME, Optional.empty());
		builder.define(SYNCED_PATROL_RADIUS, 0);
		builder.define(SYNCED_OWNER, Optional.empty());
		builder.define(SYNCED_GROUP_INDEX, MIN_GROUP_INDEX);
		builder.define(RANK, (byte) GuardRank.SOLDIER.id());
		builder.define(SYNCED_VILLAGER_DATA, Villager.createDefaultVillagerData());
	}

	public boolean isDebugActive() {
		return this.entityData.get(DEBUG_ACTIVE);
	}

	public void setDebugActive(boolean active) {
		this.entityData.set(DEBUG_ACTIVE, active);
	}

	public GuardRole getRole() {
		return GuardRole.fromId(this.entityData.get(ROLE));
	}

	public void setRole(GuardRole role) {
		this.entityData.set(ROLE, role.getId());
	}

	public GuardBehavior getBehavior() {
		return GuardBehavior.fromId(this.entityData.get(BEHAVIOR));
	}

	public void setBehavior(GuardBehavior behavior) {
		this.entityData.set(BEHAVIOR, behavior.getId());
	}

	public FormationType getFormationType() {
		return FormationType.fromId(this.entityData.get(FORMATION));
	}

	public void setFormationType(FormationType formationType) {
		FormationType resolved = formationType == null ? FormationType.FOLLOW : formationType;
		this.entityData.set(FORMATION, resolved.getId());
	}

	public int getExperience() {
		return this.entityData.get(EXPERIENCE);
	}

	public void setExperience(int experience) {
		this.entityData.set(EXPERIENCE, Math.max(0, experience));
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
		this.syncOwnerData();
		if (ownerUuid != null && !ownerUuid.equals(previousOwner)) {
			this.squadId = ownerUuid;
			if (this.level() instanceof ServerLevel serverLevel) {
				this.hiredAtTick = serverLevel.getGameTime();
			}
		}
		if (!java.util.Objects.equals(previousOwner, ownerUuid)) {
			this.setRank(GuardRank.SOLDIER);
			if (this.level() instanceof ServerLevel serverLevel) {
				GuardHierarchy.onGuardLeftOwner(serverLevel.getServer(), previousOwner, this.getUUID());
				GuardHierarchy.requestElection(previousOwner);
				GuardHierarchy.requestElection(ownerUuid);
			}
		}
		if (previousOwner != null && !previousOwner.equals(ownerUuid) && !this.lastRegisteredOwnerName.isBlank()) {
			unregisterOwnerName(this.lastRegisteredOwner != null ? this.lastRegisteredOwner : previousOwner,
					this.lastRegisteredOwnerName);
			this.lastRegisteredOwner = null;
			this.lastRegisteredOwnerName = "";
		}
		if (ownerUuid == null) {
			this.setCustomNameVisible(false);
		}
		if (this.level() instanceof ServerLevel) {
			ServerLevel world = (ServerLevel) this.level();
			if (ownerUuid != null) {
				this.ensureHireIdentity(world);
				this.syncRegisteredOwnerName();
			}
			GuardOwnershipIndex.track(this);
			this.aiController.invalidateStatus();
		}
	}

	public boolean hasGeneratedIdentity() {
		return !this.generatedName.isBlank();
	}

	public String getGeneratedName() {
		return this.generatedName;
	}

	private void ensureHireIdentity(ServerLevel world) {
		if (this.ownerUuid == null || !this.generatedName.isBlank()) {
			if (this.ownerUuid != null && this.getCustomName() == null && !this.generatedName.isBlank()) {
				this.setCustomName(Component.literal(this.generatedName));
			}
			if (this.ownerUuid != null) {
				this.setCustomNameVisible(true);
			}
			return;
		}

		Component customName = this.getCustomName();
		if (customName != null) {
			String existing = customName.getString().trim();
			if (!existing.isBlank() && !existing.startsWith(DEBUG_NAME_PREFIX)) {
				this.generatedName = existing;
				this.specialProfile = this.inferSpecialProfileFromName(existing);
				this.setCustomNameVisible(true);
				return;
			}
		}

		GuardNameRoll roll = this.rollUniqueGuardName(world, this.ownerUuid);
		this.generatedName = roll.name();
		this.specialProfile = roll.specialProfile();
		this.notchAppleCooldownUntil = 0L;
		this.setCustomName(Component.literal(this.generatedName));
		this.setCustomNameVisible(true);
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
		return this.entityData.get(SQUAD_LEADER);
	}

	public void setSquadLeader(boolean squadLeader) {
		this.entityData.set(SQUAD_LEADER, squadLeader);
	}

	public GuardRank getRank() {
		return GuardRank.fromId(this.entityData.get(RANK));
	}

	/**
	 * Set by {@link GuardHierarchy} only. Captains and commanders also carry the squad-leader flag, so the whistle's
	 * leader rally reaches them without knowing about ranks.
	 */
	public void setRank(GuardRank rank) {
		GuardRank resolved = rank == null ? GuardRank.SOLDIER : rank;
		this.entityData.set(RANK, (byte) resolved.id());
		this.setSquadLeader(resolved.atLeast(GuardRank.CAPTAIN));
	}

	public long getHiredAtTick() {
		return this.hiredAtTick;
	}

	public boolean isSameSquad(GuardEntity other) {
		return this.squadId != null && this.squadId.equals(other.squadId);
	}

	public boolean isStaying() {
		return this.staying;
	}

	public void setStaying(boolean staying) {
		this.holdOrigin = null;
		this.staying = staying;
		if (staying) {
			this.stayOrigin = this.blockPosition();
			this.setFollowOverride(false);
			this.clearCombatTarget();
		} else {
			this.stayOrigin = null;
		}
		this.aiController.invalidateStatus();
	}

	public BlockPos getStayOrigin() {
		return this.stayOrigin;
	}

	public boolean hasFollowOverride() {
		return this.followOverride;
	}

	public void setFollowOverride(boolean followOverride) {
		boolean changed = this.followOverride != followOverride;
		this.followOverride = followOverride;
		if (!followOverride) {
			this.setCatchUpSpeedActive(false);
		}
		if (followOverride) {
			this.holdOrigin = null;
		}
		if (changed && followOverride) {
			// A follow order overrides whatever the guard was fighting or walking to.
			this.clearCombatTarget();
		}
		this.aiController.invalidateStatus();
	}

	public boolean isRetreating() {
		return this.aiController.isRetreating();
	}

	public void setRetreating(boolean retreating) {
		this.aiController.setRetreating(retreating);
	}

	public Optional<BlockPos> getHome() {
		return Optional.ofNullable(this.home);
	}

	public int getPatrolRadius() {
		return this.patrolRadius;
	}

	public void setHome(BlockPos home, int patrolRadius) {
		this.setFollowOverride(false);
		this.holdOrigin = null;
		this.home = home.immutable();
		this.setPatrolRadius(patrolRadius);
		this.syncHomeData();
		this.aiController.invalidateStatus();
	}

	public void setHome(BlockPos home) {
		this.setHome(home, Math.max(24, this.patrolRadius));
	}

	public void clearHome() {
		this.setFollowOverride(false);
		this.home = null;
		this.patrolRadius = 0;
		this.syncHomeData();
		this.aiController.invalidateStatus();
	}

	public void setPatrolRadius(int patrolRadius) {
		this.patrolRadius = Mth.clamp(patrolRadius, 0, 128);
		this.syncHomeData();
	}

	private void syncOwnerData() {
		this.entityData.set(SYNCED_OWNER, this.ownerUuid == null
				? Optional.empty()
				: Optional.of(EntityReference.of(this.ownerUuid)));
	}

	private void syncHomeData() {
		this.entityData.set(SYNCED_HOME, Optional.ofNullable(this.home));
		this.entityData.set(SYNCED_PATROL_RADIUS, this.patrolRadius);
	}

	public Optional<BlockPos> getSyncedHome() {
		return this.entityData.get(SYNCED_HOME);
	}

	public int getSyncedPatrolRadius() {
		return this.entityData.get(SYNCED_PATROL_RADIUS);
	}

	/** Owner UUID as seen by clients (null when unowned). */
	public UUID getSyncedOwnerUuid() {
		return this.entityData.get(SYNCED_OWNER).map(EntityReference::getUUID).orElse(null);
	}

	/** Group index as seen by clients (-1 = unassigned). */
	public int getSyncedGroupIndex() {
		return this.entityData.get(SYNCED_GROUP_INDEX);
	}

	/** Biome villager type for robe rendering, chosen from the spawn biome and persisted. */
	public Holder<VillagerType> getVillagerTypeHolder() {
		return this.entityData.get(SYNCED_VILLAGER_DATA).type();
	}

	private void chooseVillagerTypeFromBiome() {
		ResourceKey<VillagerType> typeKey = VillagerType.byBiome(this.level().getBiome(this.blockPosition()));
		this.entityData.set(SYNCED_VILLAGER_DATA, this.entityData.get(SYNCED_VILLAGER_DATA)
				.withType(BuiltInRegistries.VILLAGER_TYPE.getOrThrow(typeKey)));
		this.villagerTypeChosen = true;
	}

	/**
	 * Hold mode: the guard stands on its current block, fights only what it can reach from there
	 * (bowmen shoot from position) and never paths away. Follow, stay and home orders clear it.
	 */
	public void setHoldPosition(boolean hold) {
		if (hold) {
			this.staying = false;
			this.stayOrigin = null;
			this.followOverride = false;
			this.setCatchUpSpeedActive(false);
			this.holdOrigin = this.blockPosition().immutable();
			this.clearCombatTarget();
		} else {
			this.holdOrigin = null;
		}
		this.aiController.invalidateStatus();
	}

	public boolean isHoldingPosition() {
		return this.holdOrigin != null;
	}

	public BlockPos getHoldOrigin() {
		return this.holdOrigin;
	}

	public BlockPos getLastLandPos() {
		return this.lastLandPos;
	}

	private void updateLastLandPos() {
		if (this.isInWater() || !this.onGround()) {
			return;
		}
		BlockPos currentPos = this.blockPosition();
		if (currentPos.equals(this.lastLandCheckPos)) {
			return;
		}
		this.lastLandCheckPos = currentPos;
		this.lastLandPos = currentPos.immutable();
	}

	public int getGroupIndex() {
		return this.groupIndex;
	}

	public void setGroupIndex(int groupIndex) {
		int previous = this.groupIndex;
		this.groupIndex = Mth.clamp(groupIndex, MIN_GROUP_INDEX, MAX_GROUP_INDEX);
		this.entityData.set(SYNCED_GROUP_INDEX, this.groupIndex);
		if (previous != this.groupIndex && this.ownerUuid != null && this.level() instanceof ServerLevel) {
			GuardHierarchy.requestElection(this.ownerUuid);
		}
	}

	public int getGroupColumn() {
		return this.groupColumn;
	}

	public void setGroupColumn(int groupColumn) {
		this.groupColumn = Mth.clamp(groupColumn, 0, 2);
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
		this.skinProfileId = trimmed.length() <= MAX_SKIN_PROFILE_LENGTH ? trimmed
				: trimmed.substring(0, MAX_SKIN_PROFILE_LENGTH);
	}

	public void clearCombatTarget() {
		this.aiController.clearCombatTarget();
	}

	public boolean canTargetWithinZone(BlockPos pos) {
		if (this.followOverride) {
			return true;
		}
		if (this.home == null || this.patrolRadius <= 0) {
			return true;
		}
		return this.home.distSqr(pos) <= (double) this.patrolRadius * this.patrolRadius;
	}

	public void rallyTo(BlockPos rallyPoint, int ticks) {
		// Temporary: stay/follow/home state is untouched, so the guard resumes it once the rally ends.
		this.aiController.rallyTo(rallyPoint, ticks, false);
	}

	/**
	 * Like {@link #rallyTo(BlockPos, int)}, but with {@code exact} the guard stands on the rally block itself instead of
	 * a ring slot around it (formation posts, the commander reporting to its owner). Guards sharing one exact point
	 * still spread into slots.
	 */
	public void rallyTo(BlockPos rallyPoint, int ticks, boolean exact) {
		this.aiController.rallyTo(rallyPoint, ticks, exact);
	}

	public boolean isRallyExact() {
		return this.aiController.isRallyExact();
	}

	public boolean canExecuteBehaviorGoals() {
		return this.aiController.canExecuteBehaviorGoals();
	}

	public boolean canFollowOwnerFormation() {
		return this.aiController.canFollowOwnerFormation();
	}

	public GuardNavigation getGuardNavigation() {
		return (GuardNavigation) this.getNavigation();
	}

	/**
	 * Grounded, non-overlapping standing slot for this guard around a shared anchor (home, rally
	 * point, crowd meeting point, raid centre, retreat point...).
	 */
	public BlockPos resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind kind, BlockPos anchor, boolean allowCenter) {
		GuardMovementSlotResolver.AnchorKey key = GuardMovementSlotResolver.AnchorKey.at(kind, this.level().dimension(), anchor);
		return GuardMovementSlotResolver.resolve(this, this.slotCache, key, anchor, allowCenter, this.level().getGameTime());
	}

	/** Exact standing position of the slot most recently returned by {@link #resolveAnchorSlot}. */
	public Vec3 getResolvedSlotPosition() {
		return this.slotCache.exactPosition();
	}

	/** Formation slot around the owner, grounded relative to {@code ownerGround}. */
	public BlockPos resolveFollowSlot(ServerPlayer owner, BlockPos ownerGround) {
		GuardMovementSlotResolver.AnchorKey key = GuardMovementSlotResolver.AnchorKey.follow(owner.getUUID());
		return GuardMovementSlotResolver.resolve(this, this.slotCache, key, ownerGround, false, this.level().getGameTime());
	}

	/** Combat goals call this every tick they want the shield up; it is applied in the AI step. */
	public void requestShield(boolean raised) {
		this.shieldRequested = raised;
	}

	public int getEngagementRank() {
		return this.aiController.getEngagementRank();
	}

	public void setCatchUpSpeedActive(boolean catchUpSpeedActive) {
		if (this.catchUpSpeedActive == catchUpSpeedActive) {
			return;
		}

		this.catchUpSpeedActive = catchUpSpeedActive;
		AttributeInstance speedInstance = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speedInstance == null) {
			return;
		}

		speedInstance.removeModifier(FOLLOW_CATCH_UP_SPEED_MODIFIER_ID);
		if (catchUpSpeedActive) {
			speedInstance.addTransientModifier(new AttributeModifier(
					FOLLOW_CATCH_UP_SPEED_MODIFIER_ID,
					FOLLOW_CATCH_UP_SPEED_BONUS,
					AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		}
	}

	public boolean shouldTacticallyRetreat() {
		return this.aiController.shouldTacticallyRetreat();
	}

	public boolean shouldContinueRetreat() {
		return this.aiController.shouldContinueRetreat();
	}

	public BlockPos findSafeRetreatPoint(ServerLevel world) {
		Villager bestCleric = null;
		double bestDistance = Double.MAX_VALUE;
		for (Villager villager : world.getEntitiesOfClass(Villager.class,
				this.getBoundingBox().inflate(32.0D), Villager::isAlive)) {
			if (!villager.getVillagerData().profession().is(VillagerProfession.CLERIC)) {
				continue;
			}
			double distanceSq = this.distanceToSqr(villager);
			if (distanceSq < bestDistance) {
				bestDistance = distanceSq;
				bestCleric = villager;
			}
		}
		if (bestCleric != null) {
			return this.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.RETREAT, bestCleric.blockPosition(), false);
		}

		Optional<BlockPos> bedPos = world.getPoiManager().findClosest(
				entry -> entry.is(PoiTypes.HOME),
				this.blockPosition(),
				48,
				PoiManager.Occupancy.ANY);
		if (bedPos.isPresent()) {
			return this.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.RETREAT, bedPos.get(), false);
		}

		if (this.home != null) {
			return this.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.RETREAT, this.home, false);
		}

		LivingEntity target = this.aiController.getTrackedCombatTarget(world);
		if (target != null) {
			Vec3 away = this.position().subtract(target.position());
			away = new Vec3(away.x, 0.0D, away.z);
			if (away.lengthSqr() < 1.0E-4D) {
				away = new Vec3(1.0D, 0.0D, 0.0D);
			}
			Vec3 destination = this.position().add(away.normalize().scale(12.0D));
			// Ground near the guard's own height (path-safe), not the heightmap top.
			BlockPos grounded = GuardGrounding.findStandableNear(this, Mth.floor(destination.x), Mth.floor(destination.z),
					this.getBlockY(), 4, 4, 2);
			if (grounded != null) {
				return grounded;
			}
		}

		return this.blockPosition();
	}

	public ServerPlayer resolveOwner(ServerLevel world) {
		if (!this.hasOwner()) {
			return null;
		}
		ServerPlayer owner = this.resolveOnlineOwner(world);
		if (owner == null || owner.level() != world) {
			return null;
		}
		return owner;
	}

	public ServerPlayer resolveOnlineOwner(ServerLevel world) {
		if (!this.hasOwner()) {
			return null;
		}
		return world.getServer().getPlayerList().getPlayer(this.ownerUuid);
	}

	public boolean tryTeleportToOwnerWorld(ServerLevel currentWorld) {
		ServerPlayer owner = this.resolveOnlineOwner(currentWorld);
		if (owner == null || owner.isSpectator() || !(owner.level() instanceof ServerLevel ownerWorld)) {
			return false;
		}
		if (ownerWorld == currentWorld || this.isLeashed() || this.isPassenger() || this.isVehicle()) {
			return false;
		}

		BlockPos destination = GuardVillagersMod.findNearbyGuardSpawnPos(ownerWorld, owner.blockPosition(), 8);
		if (destination == null) {
			return false;
		}

		this.getNavigation().stop();
		Entity teleported = this.teleport(new TeleportTransition(
				ownerWorld,
				Vec3.atBottomCenterOf(destination),
				Vec3.ZERO,
				owner.getYRot(),
				owner.getXRot(),
				TeleportTransition.DO_NOTHING));
		return teleported != null;
	}

	public void assignRandomRole(ServerLevel world) {
		this.setRole(GuardRole.random(world.getRandom()));
	}

	public void applyNaturalLoadout(ServerLevel world) {
		this.assignRandomRole(world);
		this.setBehavior(GuardBehavior.random(world.getRandom()));
		this.setFormationType(FormationType.FOLLOW);
		this.setGroupIndex(MIN_GROUP_INDEX);
		this.setGroupColumn(world.getRandom().nextIntBetweenInclusive(0, 2));
		this.setGroupName(DEFAULT_UNASSIGNED_GROUP_NAME);
		this.storeLoadoutUpgrades(new GuardPlayerUpgrades());
		this.equipGuardGear(world, 0, new GuardPlayerUpgrades());
	}

	public void applyPurchasedLoadout(ServerLevel world, GuardPlayerUpgrades upgrades) {
		GuardPlayerUpgrades storedUpgrades = upgrades == null ? new GuardPlayerUpgrades() : upgrades.copy();
		if (!this.hasOwner()) {
			this.assignRandomRole(world);
		}
		this.setBehavior(GuardBehavior.DEFENSIVE);
		this.setFormationType(FormationType.FOLLOW);
		this.setGroupIndex(MIN_GROUP_INDEX);
		this.setGroupColumn(world.getRandom().nextIntBetweenInclusive(0, 2));
		this.setGroupName(DEFAULT_UNASSIGNED_GROUP_NAME);
		this.storeLoadoutUpgrades(storedUpgrades);
		this.equipGuardGear(world, storedUpgrades.getWeaponLevel(), storedUpgrades);
	}

	private void equipGuardGear(ServerLevel world, int weaponLevel, GuardPlayerUpgrades upgrades) {
		if (this.getRole() == GuardRole.SWORDSMAN) {
			Item sword = switch (weaponLevel) {
				case 1 -> Items.IRON_SWORD;
				case 2, 3, 4, 5 -> Items.DIAMOND_SWORD;
				default -> Items.STONE_SWORD;
			};
			ItemStack swordStack = new ItemStack(sword);
			int sharpnessLevel = switch (weaponLevel) {
				case 3 -> 3;
				case 4 -> 4;
				case 5 -> 5;
				default -> 0;
			};
			if (sharpnessLevel > 0) {
				this.applyEnchantment(world, swordStack, Enchantments.SHARPNESS, sharpnessLevel);
			}
			this.setItemSlot(EquipmentSlot.MAINHAND, swordStack);
		} else {
			ItemStack bowStack = new ItemStack(Items.BOW);
			this.applyEnchantment(world, bowStack, Enchantments.POWER, Math.min(5, Math.max(1, weaponLevel + 1)));
			this.setItemSlot(EquipmentSlot.MAINHAND, bowStack);
		}

		this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		this.playerMainHand = false;
		if (upgrades.hasShieldUpgrade()) {
			this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			this.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
		} else if (this.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
			this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		}

		this.equipArmorPieces(world, upgrades);
	}

	private void equipArmorPieces(ServerLevel world, GuardPlayerUpgrades upgrades) {
		int armorLevel = upgrades.getArmorLevel();
		int protectionLevel = Math.min(4, Math.max(0, armorLevel / 2));
		GuardPlayerUpgrades.ArmorTier helmetTier = this.rollArmorTierForLoadout(upgrades);
		this.equipArmorPiece(world, EquipmentSlot.HEAD, getArmorItemForSlot(helmetTier, EquipmentSlot.HEAD),
				protectionLevel);

		for (EquipmentSlot slot : List.of(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			GuardPlayerUpgrades.ArmorTier tier = this.rollConstrainedArmorTier(upgrades, helmetTier);
			this.equipArmorPiece(world, slot, getArmorItemForSlot(tier, slot), protectionLevel);
		}

		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
				EquipmentSlot.FEET)) {
			this.setDropChance(slot, 0.0F);
			this.playerArmor.put(slot, false);
		}
	}

	/**
	 * Guards created without finalizeSpawn (structure placement, EntityType.create, game tests) had
	 * no weapon or armor and fought bare-handed. Equip the role's default gear once if the main
	 * hand is empty; never touches player-provided gear or the role/behavior.
	 */
	private void ensureWeapon(ServerLevel world) {
		if (!this.getMainHandItem().isEmpty() || this.playerMainHand) {
			return;
		}
		GuardPlayerUpgrades upgrades = this.ownerUuid != null
				? GuardVillagersMod.getUpgradesView(world, this.ownerUuid)
				: new GuardPlayerUpgrades();
		if (upgrades == null) {
			upgrades = new GuardPlayerUpgrades();
		}
		this.equipGuardGear(world, upgrades.getWeaponLevel(), upgrades);
	}

	private void storeLoadoutUpgrades(GuardPlayerUpgrades upgrades) {
		if (upgrades == null) {
			this.setStoredLoadoutLevels(0, 0, 0);
			return;
		}
		this.setStoredLoadoutLevels(
				upgrades.getArmorLevel(),
				upgrades.getWeaponLevel(),
				upgrades.getSupportLevel());
	}

	private void setStoredLoadoutLevels(int armorLevel, int weaponLevel, int supportLevel) {
		this.loadoutArmorLevel = Mth.clamp(armorLevel, 0, GuardPlayerUpgrades.MAX_ARMOR_LEVEL);
		this.loadoutWeaponLevel = Mth.clamp(weaponLevel, 0, GuardPlayerUpgrades.MAX_WEAPON_LEVEL);
		this.loadoutSupportLevel = Mth.clamp(supportLevel, 0, GuardPlayerUpgrades.MAX_SUPPORT_LEVEL);
	}

	private int getStoredHealingIntervalTicks() {
		return switch (this.loadoutSupportLevel) {
			case 1, 2 -> 50;
			case 3 -> 20;
			default -> 100;
		};
	}

	private float getStoredHealingAmount() {
		return this.loadoutSupportLevel >= 1 ? 2.0F : 1.0F;
	}

	private boolean hasStoredShieldUpgrade() {
		return this.loadoutSupportLevel >= 2;
	}

	private GuardPlayerUpgrades.ArmorTier rollConstrainedArmorTier(GuardPlayerUpgrades upgrades,
			GuardPlayerUpgrades.ArmorTier helmetTier) {
		GuardPlayerUpgrades.ArmorTier roll = this.rollArmorTierForLoadout(upgrades);
		int helmetIndex = helmetTier.ordinal();
		int minAllowed = Math.max(0, helmetIndex - 1);
		int clamped = Math.max(minAllowed, Math.min(helmetIndex, roll.ordinal()));
		return GuardPlayerUpgrades.ArmorTier.values()[clamped];
	}

	private GuardPlayerUpgrades.ArmorTier rollArmorTierForLoadout(GuardPlayerUpgrades upgrades) {
		GuardPlayerUpgrades.ArmorTier baseTier = upgrades.rollArmorTier(this.getRandom());
		GuardPlayerUpgrades.ArmorTier rolledTier = baseTier;
		if (!this.hasSpecialArmorBoost()) {
			return rolledTier;
		}

		GuardPlayerUpgrades.ArmorDistribution distribution = upgrades.getArmorDistribution();
		int boostedDiamondChance = Math.min(100, distribution.diamond() * 3);
		int roll = this.getRandom().nextInt(100);
		if (roll < boostedDiamondChance) {
			rolledTier = GuardPlayerUpgrades.ArmorTier.DIAMOND;
		}
		if (baseTier == GuardPlayerUpgrades.ArmorTier.DIAMOND
				&& ("Notch".equals(this.generatedName) || "Jack Black".equals(this.generatedName)
						|| "Jason Momoa".equals(this.generatedName))
				&& this.getRandom().nextInt(10) == 0) {
			rolledTier = GuardPlayerUpgrades.ArmorTier.NETHERITE;
		}
		return rolledTier;
	}

	private boolean hasSpecialArmorBoost() {
		return this.specialProfile == SPECIAL_PROFILE_NOTCH
				|| this.specialProfile == SPECIAL_PROFILE_JACK_BLACK
				|| this.specialProfile == SPECIAL_PROFILE_JASON_MOMOA;
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

	private void equipArmorPiece(ServerLevel world, EquipmentSlot slot, Item item, int protectionLevel) {
		ItemStack stack = new ItemStack(item);
		if (protectionLevel > 0) {
			this.applyEnchantment(world, stack, Enchantments.PROTECTION, protectionLevel);
		}
		this.setItemSlot(slot, stack);
	}

	private void applyEnchantment(ServerLevel world, ItemStack stack, ResourceKey<Enchantment> enchantment, int level) {
		Registry<Enchantment> registry = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		if (!registry.containsKey(enchantment)) {
			return;
		}
		Holder<Enchantment> entry = registry.wrapAsHolder(registry.getValueOrThrow(enchantment));
		EnchantmentHelper.updateEnchantments(stack, builder -> builder.set(entry, level));
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (this.ownerUuid != null && !this.ownerUuid.equals(player.getUUID())) {
			if (this.level() instanceof ServerLevel) {
				player.sendOverlayMessage(Component.literal("I'm not your guard!"));
			}
			return InteractionResult.SUCCESS;
		}

		if (this.ownerUuid == null && stack.is(Items.EMERALD)) {
			if (this.level() instanceof ServerLevel world) {
				if (!GuardReputationManager.isTrustedByGuards(world, player.getUUID(), this.blockPosition())) {
					player.sendOverlayMessage(Component.literal("The guard distrusts you due to village reputation."));
					return InteractionResult.SUCCESS;
				}
				int ownedGuards = GuardOwnershipIndex.countOwnedGuards(world.getServer(), player.getUUID());
				if (com.guardvillagers.GuardSecurityLimits.remainingPersonalGuardSlots(ownedGuards) <= 0) {
					player.sendOverlayMessage(Component.literal("You already command as many guards as you can."));
					return InteractionResult.SUCCESS;
				}
				int hirePrice = com.guardvillagers.GuardHirePricing.getHirePrice(this.getLevel());
				if (!player.getAbilities().instabuild && stack.getCount() < hirePrice) {
					player.sendOverlayMessage(Component.literal("Need " + hirePrice + " emerald(s) to hire this guard."));
					return InteractionResult.SUCCESS;
				}

				this.setOwnerUuid(player.getUUID());
				this.setStaying(false);
				this.setBehavior(GuardBehavior.DEFENSIVE);
				this.setFormationType(FormationType.FOLLOW);
				if (!player.getAbilities().instabuild) {
					stack.shrink(hirePrice);
				}
				player.sendOverlayMessage(Component.literal(this.getName().getString() + " is now loyal to you."));
			}
			return InteractionResult.SUCCESS;
		}

		if (this.ownerUuid != null && this.ownerUuid.equals(player.getUUID()) && stack.isEmpty()) {
			if (player.isShiftKeyDown()) {
				this.cycleBehavior();
				player.sendOverlayMessage(Component.literal("Behavior set to " + this.getBehavior().name().toLowerCase()));
			} else {
				this.setStaying(!this.staying);
				player.sendOverlayMessage(Component.literal(this.staying ? "Guard staying." : "Guard following."));
			}
			return InteractionResult.SUCCESS;
		}

		if (this.ownerUuid != null && this.ownerUuid.equals(player.getUUID()) && !stack.isEmpty()) {
			if (this.tryApplyPlayerUpgrade(player, hand, stack)) {
				return InteractionResult.SUCCESS;
			}
			player.sendOverlayMessage(Component.literal("That item is not an upgrade for this guard."));
			return InteractionResult.SUCCESS;
		}

		return super.mobInteract(player, hand);
	}

	private void cycleBehavior() {
		GuardBehavior[] behaviors = GuardBehavior.values();
		int next = (this.getBehavior().ordinal() + 1) % behaviors.length;
		this.setBehavior(behaviors[next]);
	}

	private boolean tryApplyPlayerUpgrade(Player player, InteractionHand hand, ItemStack offered) {
		if (!(this.level() instanceof ServerLevel)) {
			return false;
		}

		if (this.tryApplyArmorUpgrade(player, hand, offered)) {
			player.sendOverlayMessage(Component.literal("Guard armor upgraded."));
			return true;
		}

		if (this.tryApplyWeaponUpgrade(player, hand, offered)) {
			player.sendOverlayMessage(Component.literal("Guard weapon upgraded."));
			return true;
		}

		return false;
	}

	private boolean tryApplyArmorUpgrade(Player player, InteractionHand hand, ItemStack offered) {
		if (!player.isShiftKeyDown() || this.distanceToSqr(player) > 25.0D) {
			return false;
		}

		ArmorDefinition definition = ARMOR_DEFINITIONS.get(offered.getItem());
		if (definition == null) {
			return false;
		}

		ItemStack equipped = this.getItemBySlot(definition.slot());
		int currentScore = this.getArmorScore(equipped);
		int offeredProtection = this.getEnchantmentLevel(offered, Enchantments.PROTECTION);
		int currentProtection = this.getEnchantmentLevel(equipped, Enchantments.PROTECTION);
		if (definition.score() < currentScore) {
			return false;
		}
		if (definition.score() == currentScore && offeredProtection <= currentProtection) {
			return false;
		}

		this.setItemSlot(definition.slot(), offered.copyWithCount(1));
		this.setDropChance(definition.slot(), 1.0F);
		this.playerArmor.put(definition.slot(), true);
		if (!player.getAbilities().instabuild) {
			player.getItemInHand(hand).shrink(1);
		}
		return true;
	}

	public UUID getMainTargetUuid() {
		return this.aiController.getMainTargetUuid();
	}

	public UUID getUrgentTargetUuid() {
		return this.aiController.getUrgentTargetUuid();
	}

	public boolean isCombatSuspended() {
		return this.aiController.isCombatSuspended();
	}

	public void suspendCombat() {
		this.aiController.suspendCombat();
	}

	public void resumeCombat() {
		this.aiController.resumeCombat();
	}

	public int getCombatCooldown() {
		return this.aiController.getCombatCooldown();
	}

	public boolean isAiIntent(GuardAiIntent intent) {
		return this.aiController.isIntent(intent);
	}

	public boolean isBehaviorExecutor(GuardBehaviorExecutor executor) {
		return this.aiController.isBehaviorExecutor(executor);
	}

	public Optional<BlockPos> getRallyPoint() {
		return this.aiController.getRallyPoint();
	}

	public GuardDebugSnapshot getDebugSnapshot(int maxNodes) {
		int nodeCap = Math.max(0, maxNodes);
		Path path = this.getNavigation().getPath();
		List<BlockPos> nodes = new ArrayList<>();
		int currentNodeIndex = -1;
		if (path != null && !path.isDone() && nodeCap > 0) {
			int length = Math.min(path.getNodeCount(), nodeCap);
			for (int i = 0; i < length; i++) {
				Node node = path.getNode(i);
				nodes.add(new BlockPos(node.x, node.y, node.z));
			}
			currentNodeIndex = Math.min(path.getNextNodeIndex(), Math.max(0, length - 1));
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

	private boolean tryApplyWeaponUpgrade(Player player, InteractionHand hand, ItemStack offered) {
		if (this.getRole() == GuardRole.SWORDSMAN) {
			int currentScore = this.getSwordScore(this.getMainHandItem());
			int offeredScore = this.getSwordScore(offered);
			if (offeredScore <= currentScore) {
				return false;
			}
		} else {
			if (!offered.is(Items.BOW)) {
				return false;
			}
			int offeredPower = this.getEnchantmentLevel(offered, Enchantments.POWER);
			int currentPower = this.getEnchantmentLevel(this.getMainHandItem(), Enchantments.POWER);
			if (offeredPower <= currentPower) {
				return false;
			}
		}

		this.setItemSlot(EquipmentSlot.MAINHAND, offered.copyWithCount(1));
		this.setDropChance(EquipmentSlot.MAINHAND, 1.0F);
		this.playerMainHand = true;
		if (!player.getAbilities().instabuild) {
			player.getItemInHand(hand).shrink(1);
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

	private int getEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		if (!(this.level() instanceof ServerLevel world)) {
			return 0;
		}
		Registry<Enchantment> registry = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		if (!registry.containsKey(enchantment)) {
			return 0;
		}
		Holder<Enchantment> entry = registry.wrapAsHolder(registry.getValueOrThrow(enchantment));
		return EnchantmentHelper.getItemEnchantmentLevel(entry, stack);
	}

	private int getArmorScore(ItemStack stack) {
		ArmorDefinition definition = ARMOR_DEFINITIONS.get(stack.getItem());
		return definition == null ? 0 : definition.score();
	}

	public void setMainTarget(LivingEntity target) {
		this.aiController.setMainTarget(target);
	}

	public void setPriorityTarget(LivingEntity target) {
		this.aiController.setPriorityTarget(target);
	}

	public void receiveOwnerAlert(LivingEntity target, long alertTick) {
		this.receiveOwnerAttackAlert(target, alertTick);
	}

	public void receiveOwnerAttackAlert(LivingEntity target, long alertTick) {
		this.aiController.receiveOwnerAttackAlert(target, alertTick);
	}

	public void receiveOwnerDamagedAlert(LivingEntity target, long alertTick) {
		this.aiController.receiveOwnerDamagedAlert(target, alertTick);
	}

	public void receiveAlliedAlert(LivingEntity target, long alertTick) {
		this.aiController.receiveAlliedAlert(target, alertTick);
	}

	public void receiveUrgentPeel(LivingEntity urgentThreat, ServerLevel world) {
		this.aiController.receiveUrgentPeel(urgentThreat, world);
	}

	public int scoreTarget(LivingEntity target) {
		if (target == null || !target.isAlive()) {
			return 0;
		}
		if (this.ownerUuid != null && this.level() instanceof ServerLevel world) {
			ServerPlayer owner = this.resolveOwner(world);
			if (owner != null && owner.getLastHurtMob() == target) {
				return 100;
			}
		}
		if (target instanceof Player) {
			return 80;
		}
		if (target instanceof Monster) {
			return 40;
		}
		return 20;
	}

	public boolean isSameGroup(GuardEntity other) {
		if (other == null || other == this) {
			return true;
		}
		if (this.ownerUuid != null && this.ownerUuid.equals(other.ownerUuid)) {
			return true;
		}
		if (this.ownerUuid == null && other.ownerUuid == null) {
			if (this.squadId != null && this.squadId.equals(other.squadId)) {
				return true;
			}
			if (this.home != null && other.home != null
					&& this.home.distSqr(other.home) <= 64.0D * 64.0D) {
				return true;
			}
			if (this.home == null && other.home == null
					&& this.distanceToSqr(other) <= 48.0D * 48.0D) {
				return true;
			}
		}
		return false;
	}


	public boolean hasActiveCombatTarget() {
		return this.aiController.hasActiveCombatTarget();
	}

	@Override
	public void aiStep() {
		// PathfinderMob does not advance the swing animation (only Monster/Player do), so without
		// this guards never visibly swung their weapon.
		this.updateSwingTime();
		super.aiStep();
	}

	@Override
	protected void customServerAiStep(ServerLevel world) {
		super.customServerAiStep(world);
		if (this.isRemoved()) {
			// A goal just teleported this guard to another dimension. The new copy (same UUID) owns
			// the index entry and the owner's name now; this one must not touch them.
			return;
		}
		if (this.tickCount % 20 == 0) {
			this.syncRegisteredOwnerName();
		}
		// setOwnerUuid() already (un)tracks immediately; this is only a periodic self-heal.
		if (this.tickCount % 40 == 0) {
			GuardOwnershipIndex.track(this);
		}

		if (this.tickCount % 20 == 0) {
			this.updateLastLandPos();
		}

		if (this.tickCount % 1200 == 0) {
			this.addExperience(1);
		}

		if (!this.gearChecked) {
			this.gearChecked = true;
			this.ensureWeapon(world);
			if (!this.villagerTypeChosen) {
				this.chooseVillagerTypeFromBiome();
			}
		}

		if (!this.initialSpreadResolved && this.tickCount <= INITIAL_SPREAD_TICKS) {
			this.resolveInitialSpawnSpacing(world);
		}

		this.aiController.tick(world);
		this.tickInitialSpawnDispersal(world);
		this.tickUnstickWatchdog();
		this.syncSupportEquipment();
		this.updateShieldUsage();
		this.updateGroupNameplate();
		this.tryTriggerNotchGoldenApple(world);

		// Heal whenever the guard is not actively fighting and has not been hit recently. The old
		// gate (combat cooldown <= 0) was refreshed by every allied alert, so a retreating guard
		// never healed while its group was still fighting and could never leave retreat.
		int healInterval = this.getStoredHealingIntervalTicks();
		if (healInterval > 0 && this.tickCount % healInterval == 0
				&& !this.aiController.isIntent(GuardAiIntent.ENGAGE_TARGET)
				&& this.tickCount - this.getLastHurtByMobTimestamp() > HEAL_AFTER_HURT_TICKS
				&& this.getHealth() < this.getMaxHealth() * 0.6F) {
			this.heal(this.getStoredHealingAmount());
		}
	}

	private void resolveInitialSpawnSpacing(ServerLevel world) {
		this.initialSpreadResolved = true;
		List<GuardEntity> crowded = world.getEntitiesOfClass(
				GuardEntity.class,
				this.getBoundingBox().inflate(1.1D),
				entity -> entity != this && entity.isAlive());
		if (crowded.isEmpty()) {
			return;
		}

		BlockPos spreadTarget = this.resolveAnchorSlot(GuardMovementSlotResolver.AnchorKind.SPAWN_SPREAD,
				this.blockPosition(), true);
		if (spreadTarget.distSqr(this.blockPosition()) < 1.0D || !GuardGrounding.isStandable(this, spreadTarget)) {
			return;
		}

		this.pendingInitialSpreadTarget = spreadTarget.immutable();
	}

	private void tickInitialSpawnDispersal(ServerLevel world) {
		BlockPos spreadTarget = this.pendingInitialSpreadTarget;
		if (spreadTarget == null) {
			return;
		}

		Vec3 targetPos = Vec3.atBottomCenterOf(spreadTarget);
		if (this.distanceToSqr(targetPos.x, targetPos.y, targetPos.z) <= INITIAL_SPREAD_COMPLETE_DISTANCE_SQUARED) {
			this.pendingInitialSpreadTarget = null;
			return;
		}

		if (this.tickCount <= INITIAL_SPREAD_TICKS) {
			this.getGuardNavigation().startMovingToStatic(spreadTarget, INITIAL_SPREAD_SPEED);
			return;
		}

		if (this.getNavigation().isDone()) {
			this.pendingInitialSpreadTarget = null;
		}
	}

	/**
	 * Guards with nothing to do that end up boxed in (inside a house, wedged in a doorway or a
	 * 1-wide gap) for 5 seconds walk out to the nearest standing spot under open sky.
	 */
	private void tickUnstickWatchdog() {
		if (this.tickCount % 20 != 0) {
			return;
		}
		boolean idle = this.aiController.isIntent(GuardAiIntent.IDLE)
				|| this.aiController.isIntent(GuardAiIntent.BEHAVIOR_PATROL_OR_ANCHOR);
		BlockPos pos = this.blockPosition();
		if (!idle || this.staying || this.getTarget() != null || this.isInWater()
				|| this.level().canSeeSky(pos.above())) {
			this.unstickSamples = 0;
			this.unstickSamplePos = pos;
			return;
		}
		if (this.unstickSamplePos == null || this.unstickSamplePos.distSqr(pos) > 2.0D) {
			this.unstickSamples = 0;
			this.unstickSamplePos = pos;
			return;
		}
		if (++this.unstickSamples < UNSTICK_SAMPLES) {
			return;
		}
		this.unstickSamples = 0;
		BlockPos exit = this.getGuardNavigation().findOpenSkyExit();
		if (exit != null) {
			this.getGuardNavigation().beginRecoveryMove(exit, 1.0D);
		}
	}

	private void syncSupportEquipment() {
		if (this.ownerUuid == null) {
			return;
		}
		boolean shouldHaveShield = this.hasStoredShieldUpgrade();
		boolean hasShield = this.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD);
		if (shouldHaveShield && !hasShield) {
			this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			this.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
		} else if (!shouldHaveShield && hasShield) {
			this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		}
	}

	/**
	 * Applies the shield request made by the combat goals this tick. Combat goals decide when to
	 * block (closing on archers, between sword swings, between bow shots) because only they know
	 * whether the guard is about to swing or is drawing the bow. A retreating guard keeps its shield
	 * up while it is being hit.
	 */
	private void updateShieldUsage() {
		ItemStack shield = this.getItemBySlot(EquipmentSlot.OFFHAND);
		boolean requested = this.shieldRequested;
		this.shieldRequested = false;
		if (!shield.is(Items.SHIELD)) {
			if (this.isUsingItem() && this.getUsedItemHand() == InteractionHand.OFF_HAND) {
				this.stopUsingItem();
			}
			return;
		}

		boolean shouldBlock = requested
				|| (this.aiController.isIntent(GuardAiIntent.RETREAT)
						&& this.tickCount - this.getLastHurtByMobTimestamp() < 40);
		boolean drawingBow = this.isUsingItem() && this.getUsedItemHand() == InteractionHand.MAIN_HAND;
		if (shouldBlock && !drawingBow) {
			if (!this.isUsingItem()) {
				this.startUsingItem(InteractionHand.OFF_HAND);
			}
		} else if (this.isUsingItem() && this.getUsedItemHand() == InteractionHand.OFF_HAND) {
			this.stopUsingItem();
		}
	}

	public void updateGroupNameplate() {
		if (this.tickCount % 10 != 0) {
			return;
		}
		Component current = this.getCustomName();
		if (current != null && current.getString().startsWith(DEBUG_NAME_PREFIX)) {
			return;
		}

		if (!this.hasOwner()) {
			if (this.isCustomNameVisible()) {
				this.setCustomNameVisible(false);
			}
			return;
		}

		if (current == null && !this.generatedName.isBlank()) {
			this.setCustomName(Component.literal(this.generatedName));
		}
		if (!this.isCustomNameVisible()) {
			this.setCustomNameVisible(true);
		}
	}

	private void syncRegisteredOwnerName() {
		if (this.ownerUuid == null) {
			if (!this.lastRegisteredOwnerName.isBlank()) {
				unregisterOwnerName(this.lastRegisteredOwner, this.lastRegisteredOwnerName);
				this.lastRegisteredOwner = null;
				this.lastRegisteredOwnerName = "";
			}
			return;
		}
		String currentDisplayName = this.resolveCurrentDisplayName();
		if (currentDisplayName.isBlank()) {
			return;
		}
		if (currentDisplayName.equals(this.lastRegisteredOwnerName)) {
			return;
		}
		if (!this.lastRegisteredOwnerName.isBlank()) {
			unregisterOwnerName(this.lastRegisteredOwner != null ? this.lastRegisteredOwner : this.ownerUuid,
					this.lastRegisteredOwnerName);
		}
		registerOwnerName(this.ownerUuid, currentDisplayName);
		this.lastRegisteredOwner = this.ownerUuid;
		this.lastRegisteredOwnerName = currentDisplayName;
	}

	private String resolveCurrentDisplayName() {
		Component customName = this.getCustomName();
		if (customName != null) {
			String text = customName.getString().trim();
			if (!text.isBlank() && !text.startsWith(DEBUG_NAME_PREFIX)) {
				return text;
			}
		}
		return this.generatedName == null ? "" : this.generatedName.trim();
	}

	private void tryTriggerNotchGoldenApple(ServerLevel world) {
		if (this.specialProfile != SPECIAL_PROFILE_NOTCH) {
			return;
		}
		if (this.getHealth() > this.getMaxHealth() * NOTCH_APPLE_TRIGGER_HEALTH_RATIO) {
			return;
		}
		if (world.getGameTime() < this.notchAppleCooldownUntil) {
			return;
		}
		this.notchAppleCooldownUntil = world.getGameTime() + NOTCH_APPLE_COOLDOWN_TICKS;
		this.playSound(SoundEvents.GENERIC_EAT.value(), 1.0F, 1.0F);
		this.heal(8.0F);
		this.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 1));
		this.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 3));
		this.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 6000, 0));
		this.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
	}

	private GuardNameRoll rollUniqueGuardName(ServerLevel world, UUID ownerUuid) {
		Set<String> usedNames = OWNER_USED_NAMES.computeIfAbsent(ownerUuid, ignored -> ConcurrentHashMap.newKeySet());
		for (int i = 0; i < 4096; i++) {
			GuardNameRoll roll = this.rollNameCandidate(world);
			if (!usedNames.contains(roll.name())) {
				registerOwnerName(ownerUuid, roll.name());
				return roll;
			}
		}
		String fallback = this.composeRegularName(world) + " " + Integer.toString(Math.max(1, usedNames.size() + 1));
		registerOwnerName(ownerUuid, fallback);
		return new GuardNameRoll(fallback, SPECIAL_PROFILE_NONE);
	}

	private GuardNameRoll rollNameCandidate(ServerLevel world) {
		int notchRoll = world.getRandom().nextInt(NOTCH_ROLL);
		if (notchRoll == 0) {
			return new GuardNameRoll("Notch", SPECIAL_PROFILE_NOTCH);
		}
		int jackRoll = world.getRandom().nextInt(JACK_BLACK_ROLL);
		if (jackRoll == 0) {
			return new GuardNameRoll("Jack Black", SPECIAL_PROFILE_JACK_BLACK);
		}
		int jasonRoll = world.getRandom().nextInt(JASON_MOMOA_ROLL);
		if (jasonRoll == 0) {
			return new GuardNameRoll("Jason Momoa", SPECIAL_PROFILE_JASON_MOMOA);
		}
		return new GuardNameRoll(this.composeRegularName(world), SPECIAL_PROFILE_NONE);
	}

	private String composeRegularName(ServerLevel world) {
		String firstName = FIRST_NAME_POOL[world.getRandom().nextInt(FIRST_NAME_POOL.length)];
		String lastName = LAST_NAME_POOL[world.getRandom().nextInt(LAST_NAME_POOL.length)];
		return firstName + " " + lastName;
	}

	private int inferSpecialProfileFromName(String name) {
		if (name == null || name.isBlank()) {
			return SPECIAL_PROFILE_NONE;
		}
		String normalized = name.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("notch")) {
			return SPECIAL_PROFILE_NOTCH;
		}
		if (normalized.equals("jack black")) {
			return SPECIAL_PROFILE_JACK_BLACK;
		}
		if (normalized.equals("jason momoa")) {
			return SPECIAL_PROFILE_JASON_MOMOA;
		}
		return SPECIAL_PROFILE_NONE;
	}

	private static void registerOwnerName(UUID ownerUuid, String name) {
		if (ownerUuid == null || name == null || name.isBlank()) {
			return;
		}
		OWNER_USED_NAMES.computeIfAbsent(ownerUuid, ignored -> ConcurrentHashMap.newKeySet()).add(name);
	}

	private static void unregisterOwnerName(UUID ownerUuid, String name) {
		if (ownerUuid == null || name == null || name.isBlank()) {
			return;
		}
		Set<String> usedNames = OWNER_USED_NAMES.get(ownerUuid);
		if (usedNames == null) {
			return;
		}
		usedNames.remove(name);
		if (usedNames.isEmpty()) {
			OWNER_USED_NAMES.remove(ownerUuid);
		}
	}

	public boolean isAlly(Entity entity) {
		if (entity == null || entity == this) {
			return true;
		}
		if (entity instanceof IronGolem) {
			return true;
		}
		if (entity instanceof GuardEntity otherGuard) {
			if (this.ownerUuid != null && this.ownerUuid.equals(otherGuard.ownerUuid)) {
				return true;
			}
			if (this.ownerUuid == null && otherGuard.ownerUuid == null) {
				return true;
			}
			return false;
		}
		if (entity instanceof Villager) {
			return true;
		}
		if (entity instanceof Player player) {
			if (player.isSpectator() || player.getAbilities().instabuild) {
				return true;
			}
			if (this.ownerUuid != null && this.ownerUuid.equals(player.getUUID())) {
				return true;
			}
			if (this.ownerUuid != null && this.level() instanceof ServerLevel world) {
				if (GuardDiplomacyManager.isBlacklisted(world.getServer(), this.ownerUuid, player.getUUID())) {
					return false;
				}
				return GuardDiplomacyManager.isWhitelisted(world.getServer(), this.ownerUuid, player.getUUID());
			}
		}
		return false;
	}

	@Override
	public void setTarget(LivingEntity target) {
		if (target != null && this.isAlly(target)) {
			target = null;
		}
		super.setTarget(target);
	}



	@Override
	public boolean doHurtTarget(ServerLevel world, Entity target) {
		boolean attacked = super.doHurtTarget(world, target);
		if (attacked && !this.getMainHandItem().is(Items.BOW)) {
			this.swing(InteractionHand.MAIN_HAND);
		}
		return attacked;
	}

	@Override
	public void die(DamageSource damageSource) {
		super.die(damageSource);
		if (this.ownerUuid != null && this.level() instanceof ServerLevel) {
			GuardHierarchy.onGuardLost(this, true);
		}
		if (this.ownerUuid != null && this.level() instanceof ServerLevel world) {
			ServerPlayer owner = this.resolveOwner(world);
			boolean showDeathMessages = Boolean.TRUE.equals(world.getGameRules().get(GameRules.SHOW_DEATH_MESSAGES));
			if (owner != null && showDeathMessages) {
				owner.sendSystemMessage(damageSource.getLocalizedDeathMessage(this));
			}
		}
	}

	@Override
	public void performRangedAttack(LivingEntity target, float pullProgress) {
		ItemStack bow = this.getMainHandItem();
		if (!bow.is(Items.BOW) || !(this.level() instanceof ServerLevel world)) {
			return;
		}

		ItemStack arrowStack = this.getProjectile(bow);
		if (arrowStack.isEmpty()) {
			arrowStack = new ItemStack(Items.ARROW);
		}

		AbstractArrow arrow = new GuardArrowEntity(world, this, arrowStack.copyWithCount(1), bow.copy());
		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		int power = this.getEnchantmentLevel(bow, Enchantments.POWER);
		double baseDamage = 2.0D + Math.max(0.0F, pullProgress) * 1.6D + (double) power * 0.6D;
		arrow.setBaseDamage(baseDamage);
		if (this.getEnchantmentLevel(bow, Enchantments.FLAME) > 0) {
			arrow.igniteForSeconds(5.0F);
		}
		double dx = target.getX() - this.getX();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double dy = target.getY(0.3333333333333333D) - arrow.getY() + horizontal * 0.2D;
		arrow.shoot(dx, dy, dz, 1.6F, (float) (14 - this.level().getDifficulty().getId() * 4));
		this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
		world.addFreshEntity(arrow);
	}

	@Override
	public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
		boolean activelyBlockingWithShield = this.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)
				&& this.isUsingItem()
				&& this.getUsedItemHand() == InteractionHand.OFF_HAND;
		if (this.hasStoredShieldUpgrade() && activelyBlockingWithShield) {
			amount *= 0.55F;
		}
		boolean damaged = super.hurtServer(world, source, amount);
		if (!damaged) {
			return false;
		}

		if (source.getEntity() instanceof LivingEntity attacker) {
			if (attacker instanceof Player playerAttacker && this.ownerUuid != null
					&& this.ownerUuid.equals(playerAttacker.getUUID())) {
				this.clearCombatTarget();
				return true;
			}
			if (attacker instanceof GuardEntity attackerGuard && this.ownerUuid != null
					&& this.ownerUuid.equals(attackerGuard.ownerUuid)) {
				// Friendly fire from a squadmate: don't retaliate, but don't abandon the current fight.
				return true;
			}
			if (attacker instanceof Player player && this.isCreativeOperator(player)) {
				return true;
			}
			// A forgiven accidental hit from a player triggers neither retaliation nor a group rally.
			boolean retaliate = !(attacker instanceof Player player)
					|| GuardReputationManager.onGuardHitByPlayer(world, player.getUUID(), amount);
			if (retaliate && !this.isAlly(attacker)) {
				this.aiController.receiveDamageAlert(attacker, world.getGameTime());
				this.rallyNearbyGuards(world, attacker);
			}
		}

		return true;
	}

	private boolean isCreativeOperator(Player player) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return false;
		}
		if (!serverPlayer.getAbilities().instabuild) {
			return false;
		}
		return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(serverPlayer.createCommandSourceStack());
	}

	@Override
	public boolean killedEntity(ServerLevel world, LivingEntity other, DamageSource damageSource) {
		boolean result = super.killedEntity(world, other, damageSource);
		if (other instanceof Monster) {
			this.addExperience(20);
		} else {
			this.addExperience(8);
		}

		if (this.ownerUuid != null) {
			GuardReputationManager.recordHostileKill(world, this.ownerUuid, other);
			if (other instanceof Raider) {
				GuardReputationManager.recordRaidDefense(world, this.ownerUuid);
			}
		}
		if (other instanceof Player player) {
			GuardReputationManager.resetReputation(world, player.getUUID());
		}
		return result;
	}

	/**
	 * Alerts the group about an attacker and assigns peelers. The hit guard coordinates the peel:
	 * it counts its group once, then sends the guards nearest to the attacker (those busy with
	 * another main target and not already peeling) until the cap
	 * max(1, min(10, floor(groupSize * 0.30))) is reached. Previously every recipient ran its own
	 * group scan to check the cap, which was O(n^2) entity queries per hit.
	 */
	private void rallyNearbyGuards(ServerLevel world, LivingEntity attacker) {
		long now = world.getGameTime();
		if (attacker.getUUID().equals(this.lastRallyAttackerUuid) && now - this.lastRallyTick < PEEL_REBROADCAST_TICKS) {
			return;
		}
		this.lastRallyAttackerUuid = attacker.getUUID();
		this.lastRallyTick = now;

		double range = Math.max(8.0D, this.getAttributeValue(Attributes.FOLLOW_RANGE));
		List<GuardEntity> nearby = world.getEntitiesOfClass(GuardEntity.class, this.getBoundingBox().inflate(range),
				guard -> guard != this && guard.isAlive() && this.isSameGroup(guard));

		int groupSize = nearby.size() + 1;
		int peeling = this.getUrgentTargetUuid() != null ? 1 : 0;
		List<GuardEntity> peelCandidates = new ArrayList<>();
		for (GuardEntity guard : nearby) {
			guard.receiveAlliedAlert(attacker, now);
			if (guard.getUrgentTargetUuid() != null) {
				peeling++;
			} else if (guard.getMainTargetUuid() != null && !attacker.getUUID().equals(guard.getMainTargetUuid())) {
				peelCandidates.add(guard);
			}
		}

		int toAssign = TargetScoring.peelCap(groupSize) - peeling;
		if (toAssign <= 0 || peelCandidates.isEmpty()) {
			return;
		}
		peelCandidates.sort((a, b) -> Double.compare(a.distanceToSqr(attacker), b.distanceToSqr(attacker)));
		for (int i = 0; i < peelCandidates.size() && i < toAssign; i++) {
			peelCandidates.get(i).receiveUrgentPeel(attacker, world);
		}
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel world, DamageSource source, boolean causedByPlayer) {
		this.dropPlayerGear(world, EquipmentSlot.MAINHAND, this.playerMainHand);
		this.dropPlayerGear(world, EquipmentSlot.HEAD, this.playerArmor.getOrDefault(EquipmentSlot.HEAD, false));
		this.dropPlayerGear(world, EquipmentSlot.CHEST, this.playerArmor.getOrDefault(EquipmentSlot.CHEST, false));
		this.dropPlayerGear(world, EquipmentSlot.LEGS, this.playerArmor.getOrDefault(EquipmentSlot.LEGS, false));
		this.dropPlayerGear(world, EquipmentSlot.FEET, this.playerArmor.getOrDefault(EquipmentSlot.FEET, false));
	}

	private void dropPlayerGear(ServerLevel world, EquipmentSlot slot, boolean playerProvided) {
		if (!playerProvided) {
			return;
		}
		ItemStack stack = this.getItemBySlot(slot);
		if (!stack.isEmpty()) {
			this.spawnAtLocation(world, stack.copy());
		}
	}

	private void applyLevelModifiers() {
		int level = this.getLevel();
		double healthBonus = (level - 1) * 2.0D;
		double damageBonus = (level - 1) * 0.5D;
		double speedBonus = (level - 1) * 0.01D;

		this.updateAttributeModifier(Attributes.MAX_HEALTH, LEVEL_HEALTH_MODIFIER_ID, healthBonus);
		this.updateAttributeModifier(Attributes.ATTACK_DAMAGE, LEVEL_DAMAGE_MODIFIER_ID, damageBonus);
		this.updateAttributeModifier(Attributes.MOVEMENT_SPEED, LEVEL_SPEED_MODIFIER_ID, speedBonus);
		if (this.getHealth() > this.getMaxHealth()) {
			this.setHealth(this.getMaxHealth());
		}
	}

	private void updateAttributeModifier(Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
			Identifier modifierId, double value) {
		AttributeInstance instance = this.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		instance.removeModifier(modifierId);
		if (value != 0.0D) {
			instance.addPermanentModifier(
					new AttributeModifier(modifierId, value, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput view) {
		super.addAdditionalSaveData(view);
		view.putInt(ROLE_KEY, this.entityData.get(ROLE));
		view.putInt(BEHAVIOR_KEY, this.entityData.get(BEHAVIOR));
		view.putInt(FORMATION_KEY, this.entityData.get(FORMATION));
		view.putBoolean(STAYING_KEY, this.staying);
		boolean hasStayOrigin = this.stayOrigin != null;
		view.putBoolean(HAS_STAY_ORIGIN_KEY, hasStayOrigin);
		if (hasStayOrigin) {
			view.putInt(STAY_ORIGIN_X_KEY, this.stayOrigin.getX());
			view.putInt(STAY_ORIGIN_Y_KEY, this.stayOrigin.getY());
			view.putInt(STAY_ORIGIN_Z_KEY, this.stayOrigin.getZ());
		}
		view.putBoolean(FOLLOW_OVERRIDE_KEY, this.followOverride);
		view.putString(OWNER_KEY, this.ownerUuid == null ? "" : this.ownerUuid.toString());
		view.putString(SQUAD_ID_KEY, this.squadId == null ? "" : this.squadId.toString());
		view.putBoolean(SQUAD_LEADER_KEY, this.isSquadLeader());
		view.putByte(RANK_KEY, this.entityData.get(RANK));
		view.putLong(HIRED_AT_KEY, this.hiredAtTick);
		view.putInt(EXPERIENCE_KEY, this.getExperience());
		view.putBoolean(PLAYER_MAINHAND_KEY, this.playerMainHand);
		view.putBoolean(PLAYER_HELMET_KEY, this.playerArmor.getOrDefault(EquipmentSlot.HEAD, false));
		view.putBoolean(PLAYER_CHEST_KEY, this.playerArmor.getOrDefault(EquipmentSlot.CHEST, false));
		view.putBoolean(PLAYER_LEGS_KEY, this.playerArmor.getOrDefault(EquipmentSlot.LEGS, false));
		view.putBoolean(PLAYER_FEET_KEY, this.playerArmor.getOrDefault(EquipmentSlot.FEET, false));
		view.putInt(LOADOUT_ARMOR_LEVEL_KEY, this.loadoutArmorLevel);
		view.putInt(LOADOUT_WEAPON_LEVEL_KEY, this.loadoutWeaponLevel);
		view.putInt(LOADOUT_SUPPORT_LEVEL_KEY, this.loadoutSupportLevel);

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
		view.putString(GENERATED_NAME_KEY, this.generatedName == null ? "" : this.generatedName);
		view.putInt(SPECIAL_PROFILE_KEY, this.specialProfile);
		view.putLong(NOTCH_APPLE_COOLDOWN_UNTIL_KEY, this.notchAppleCooldownUntil);
		view.putBoolean(HOLD_POSITION_KEY, this.holdOrigin != null);
		if (this.holdOrigin != null) {
			view.putInt(HOLD_X_KEY, this.holdOrigin.getX());
			view.putInt(HOLD_Y_KEY, this.holdOrigin.getY());
			view.putInt(HOLD_Z_KEY, this.holdOrigin.getZ());
		}
		if (this.villagerTypeChosen) {
			view.store(VILLAGER_DATA_KEY, VillagerData.CODEC, this.entityData.get(SYNCED_VILLAGER_DATA));
		}
		boolean hasLastLand = this.lastLandPos != null;
		view.putBoolean(HAS_LAST_LAND_KEY, hasLastLand);
		if (hasLastLand) {
			view.putInt(LAST_LAND_X_KEY, this.lastLandPos.getX());
			view.putInt(LAST_LAND_Y_KEY, this.lastLandPos.getY());
			view.putInt(LAST_LAND_Z_KEY, this.lastLandPos.getZ());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput view) {
		super.readAdditionalSaveData(view);
		this.entityData.set(ROLE, view.getIntOr(ROLE_KEY, GuardRole.SWORDSMAN.getId()));
		this.entityData.set(BEHAVIOR, view.getIntOr(BEHAVIOR_KEY, GuardBehavior.DEFENSIVE.getId()));
		// Previously this unconditionally reset to FOLLOW, silently dropping any
		// formation the player had configured on every server restart. Read the
		// stored value and fall back to FOLLOW only when absent.
		this.entityData.set(FORMATION, view.getIntOr(FORMATION_KEY, FormationType.FOLLOW.getId()));
		this.staying = view.getBooleanOr(STAYING_KEY, false);
		if (view.getBooleanOr(HAS_STAY_ORIGIN_KEY, false)) {
			this.stayOrigin = new BlockPos(
					view.getIntOr(STAY_ORIGIN_X_KEY, 0),
					view.getIntOr(STAY_ORIGIN_Y_KEY, 0),
					view.getIntOr(STAY_ORIGIN_Z_KEY, 0));
		} else {
			this.stayOrigin = null;
		}
		this.followOverride = view.getBooleanOr(FOLLOW_OVERRIDE_KEY, false);
		this.catchUpSpeedActive = false;
		this.ownerUuid = parseUuid(view.getStringOr(OWNER_KEY, ""));
		this.squadId = parseUuid(view.getStringOr(SQUAD_ID_KEY, ""));
		this.entityData.set(SQUAD_LEADER, view.getBooleanOr(SQUAD_LEADER_KEY, false));
		this.entityData.set(RANK, (byte) GuardRank.fromId(view.getByteOr(RANK_KEY, (byte) 0)).id());
		this.hiredAtTick = Math.max(0L, view.getLongOr(HIRED_AT_KEY, 0L));
		this.entityData.set(EXPERIENCE, Math.max(0, view.getIntOr(EXPERIENCE_KEY, 0)));
		this.playerMainHand = view.getBooleanOr(PLAYER_MAINHAND_KEY, false);
		this.playerArmor.put(EquipmentSlot.HEAD, view.getBooleanOr(PLAYER_HELMET_KEY, false));
		this.playerArmor.put(EquipmentSlot.CHEST, view.getBooleanOr(PLAYER_CHEST_KEY, false));
		this.playerArmor.put(EquipmentSlot.LEGS, view.getBooleanOr(PLAYER_LEGS_KEY, false));
		this.playerArmor.put(EquipmentSlot.FEET, view.getBooleanOr(PLAYER_FEET_KEY, false));
		int storedArmorLevel = view.getIntOr(LOADOUT_ARMOR_LEVEL_KEY, MISSING_LOADOUT_LEVEL);
		int storedWeaponLevel = view.getIntOr(LOADOUT_WEAPON_LEVEL_KEY, MISSING_LOADOUT_LEVEL);
		int storedSupportLevel = view.getIntOr(LOADOUT_SUPPORT_LEVEL_KEY, MISSING_LOADOUT_LEVEL);
		if (storedArmorLevel == MISSING_LOADOUT_LEVEL
				|| storedWeaponLevel == MISSING_LOADOUT_LEVEL
				|| storedSupportLevel == MISSING_LOADOUT_LEVEL) {
			if (this.ownerUuid != null && this.level() instanceof ServerLevel world) {
				this.storeLoadoutUpgrades(GuardVillagersMod.getUpgradesView(world, this.ownerUuid));
			} else {
				this.setStoredLoadoutLevels(0, 0, 0);
			}
		} else {
			this.setStoredLoadoutLevels(storedArmorLevel, storedWeaponLevel, storedSupportLevel);
		}

		if (view.getBooleanOr(HAS_HOME_KEY, false)) {
			this.home = new BlockPos(
					view.getIntOr(HOME_X_KEY, 0),
					view.getIntOr(HOME_Y_KEY, 0),
					view.getIntOr(HOME_Z_KEY, 0));
		} else {
			this.home = null;
		}
		this.patrolRadius = Mth.clamp(view.getIntOr(PATROL_RADIUS_KEY, 0), 0, 128);
		this.syncHomeData();
		// Read new keys first, fall back to legacy keys for migration
		int readGroupIndex = view.getIntOr(GROUP_INDEX_KEY, Integer.MIN_VALUE);
		if (readGroupIndex == Integer.MIN_VALUE) {
			readGroupIndex = view.getIntOr(LEGACY_HIERARCHY_ROW_KEY, 0);
		}
		this.groupIndex = Mth.clamp(readGroupIndex, MIN_GROUP_INDEX, MAX_GROUP_INDEX);
		int readGroupColumn = view.getIntOr(GROUP_COLUMN_KEY, -1);
		if (readGroupColumn < 0) {
			readGroupColumn = view.getIntOr(LEGACY_HIERARCHY_COLUMN_KEY, 1);
		}
		this.groupColumn = Mth.clamp(readGroupColumn, 0, 2);
		String readGroupName = view.getStringOr(GROUP_NAME_KEY, "");
		if (readGroupName.isEmpty()) {
			readGroupName = view.getStringOr(LEGACY_HIERARCHY_ROLE_KEY, "Alpha");
		}
		this.setGroupName(readGroupName);
		this.setSkinProfileId(view.getStringOr(SKIN_PROFILE_KEY, ""));
		this.generatedName = view.getStringOr(GENERATED_NAME_KEY, "");
		this.specialProfile = view.getIntOr(SPECIAL_PROFILE_KEY, this.inferSpecialProfileFromName(this.generatedName));
		this.notchAppleCooldownUntil = Math.max(0L, view.getLongOr(NOTCH_APPLE_COOLDOWN_UNTIL_KEY, 0L));
		if (view.getBooleanOr(HAS_LAST_LAND_KEY, false)) {
			this.lastLandPos = new BlockPos(
					view.getIntOr(LAST_LAND_X_KEY, 0),
					view.getIntOr(LAST_LAND_Y_KEY, 0),
					view.getIntOr(LAST_LAND_Z_KEY, 0));
		}
		if (view.getBooleanOr(HOLD_POSITION_KEY, false)) {
			this.holdOrigin = new BlockPos(
					view.getIntOr(HOLD_X_KEY, 0),
					view.getIntOr(HOLD_Y_KEY, 0),
					view.getIntOr(HOLD_Z_KEY, 0));
		} else {
			this.holdOrigin = null;
		}
		Optional<VillagerData> villagerData = view.read(VILLAGER_DATA_KEY, VillagerData.CODEC);
		villagerData.ifPresent(data -> this.entityData.set(SYNCED_VILLAGER_DATA, data));
		this.villagerTypeChosen = villagerData.isPresent();
		this.syncOwnerData();
		this.entityData.set(SYNCED_GROUP_INDEX, this.groupIndex);
		this.setCatchUpSpeedActive(false);
		this.applyLevelModifiers();
		if (this.level() instanceof ServerLevel) {
			if (this.ownerUuid != null) {
				this.ensureHireIdentity((ServerLevel) this.level());
			}
			GuardOwnershipIndex.track(this);
		}
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (!this.lastRegisteredOwnerName.isBlank()) {
			unregisterOwnerName(this.lastRegisteredOwner != null ? this.lastRegisteredOwner : this.ownerUuid,
					this.lastRegisteredOwnerName);
			this.lastRegisteredOwnerName = "";
			this.lastRegisteredOwner = null;
		}
		super.remove(reason);
	}

	/**
	 * Runs for every removal: remove() (kill, discard) and the paths that only call setRemoved()
	 * (chunk unload, dimension change). Deaths are reported in die(); a guard that leaves while
	 * alive counts as lost (not dead) so the owner's hierarchy re-elects among loaded guards. The
	 * removal reason is already set here, so isAlive() is false; check health instead.
	 */
	@Override
	public void onRemoval(Entity.RemovalReason reason) {
		super.onRemoval(reason);
		if (!(this.level() instanceof ServerLevel)) {
			return;
		}
		if (reason.shouldDestroy()) {
			GuardOwnershipIndex.untrack(this);
		}
		if (reason == Entity.RemovalReason.DISCARDED && this.level() instanceof ServerLevel serverLevel) {
			GuardHierarchy.onGuardLeftOwner(serverLevel.getServer(), this.ownerUuid, this.getUUID());
		}
		if (this.ownerUuid != null && this.getHealth() > 0.0F) {
			GuardHierarchy.onGuardLost(this, false);
		}
	}

	/** Drops the per-owner name registry when the server stops so the next world starts clean. */
	public static void clearSessionState() {
		OWNER_USED_NAMES.clear();
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


	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.VILLAGER_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.VILLAGER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.VILLAGER_DEATH;
	}

	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return false;
	}

	private record GuardNameRoll(String name, int specialProfile) {
	}

	private record ArmorDefinition(EquipmentSlot slot, int score) {
	}
}
