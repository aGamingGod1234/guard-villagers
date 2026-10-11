package com.guardvillagers.gametest;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Shared helpers for the guard game tests.
 *
 * <p>Every scenario test runs in the {@link #ARENA} structure (an empty 48x32x48 box) so neighbouring
 * tests sit at least 53 blocks apart. Guards scan for hostiles within 20 blocks and broadcast within
 * their 32-block follow range, so the default 8x8 test structure would let one test's guards hunt
 * another test's mobs. The {@link #ENV} environment pins noon, clear weather, no natural mob spawning
 * and no mob griefing so creepers cannot delete arena floors.
 */
final class GuardTestSupport {
	static final String ARENA = "guardvillagers-gametest:arena";
	static final String ENV = "guardvillagers-gametest:arena";
	static final int SIZE = 48;
	static final int CENTER = SIZE / 2;

	private static final AtomicInteger PLAYER_COUNTER = new AtomicInteger();

	private GuardTestSupport() {
	}

	static void floor(GameTestHelper helper) {
		floor(helper, Blocks.STONE);
	}

	static void floor(GameTestHelper helper, Block block) {
		fill(helper, 0, 0, 0, SIZE - 1, 0, SIZE - 1, block);
	}

	static void fill(GameTestHelper helper, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					helper.setBlock(new BlockPos(x, y, z), block);
				}
			}
		}
	}

	/**
	 * Creates a real {@link ServerPlayer} registered in the server player list, so code that looks the
	 * owner up through {@code PlayerList#getPlayer(UUID)} finds it. Unlike
	 * {@code GameTestHelper#makeMockServerPlayerInLevel} this one does not hard-wire creative mode, which
	 * matters because guards treat creative players as allies and mobs ignore them.
	 */
	static ServerPlayer player(GameTestHelper helper, GameType mode, double x, double y, double z) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "gvtest" + PLAYER_COUNTER.incrementAndGet());
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		// Until the client reports it finished loading, ServerPlayer#isInvulnerableTo returns true for everything.
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		player.setGameMode(mode);
		moveTo(helper, player, x, y, z);
		onFinish(helper, () -> {
			if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
				server.getPlayerList().remove(player);
			}
		});
		return player;
	}

	static ServerPlayer survivalPlayer(GameTestHelper helper, double x, double y, double z) {
		return player(helper, GameType.SURVIVAL, x, y, z);
	}

	static void moveTo(GameTestHelper helper, ServerPlayer player, double x, double y, double z) {
		Vec3 absolute = helper.absoluteVec(new Vec3(x, y, z));
		player.teleportTo(absolute.x, absolute.y, absolute.z);
	}

	static int runCommand(ServerPlayer player, String command) {
		return runCommand(player, command, false);
	}

	static int runCommand(ServerPlayer player, String command, boolean operator) {
		var source = player.createCommandSourceStack().withSuppressedOutput();
		if (operator) {
			source = source.withPermission(LevelBasedPermissionSet.OWNER);
		}
		MinecraftServer server = player.level().getServer();
		var parse = server.getCommands().getDispatcher().parse(command, source);
		try {
			return server.getCommands().getDispatcher().execute(parse);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
			GuardVillagersMod.LOGGER.info("[gametest] command '{}' rejected: {}", command, exception.getMessage());
			return -1;
		}
	}

	static GuardEntity guard(GameTestHelper helper, double x, double y, double z, GuardRole role) {
		GuardEntity guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new Vec3(x, y, z));
		guard.setRole(role);
		guard.setBehavior(GuardBehavior.DEFENSIVE);
		guard.setItemSlot(EquipmentSlot.MAINHAND,
				new ItemStack(role == GuardRole.BOWMAN ? Items.BOW : Items.STONE_SWORD));
		return guard;
	}

	static GuardEntity swordsman(GameTestHelper helper, double x, double y, double z) {
		return guard(helper, x, y, z, GuardRole.SWORDSMAN);
	}

	static GuardEntity ownedGuard(GameTestHelper helper, double x, double y, double z, ServerPlayer owner) {
		GuardEntity guard = swordsman(helper, x, y, z);
		guard.setOwnerUuid(owner.getUUID());
		guard.setStaying(false);
		return guard;
	}

	/** Carved pumpkin keeps undead from burning in daylight without wearing out. */
	static <T extends Mob> T noSunBurn(T mob) {
		mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
		mob.setDropChance(EquipmentSlot.HEAD, 0.0F);
		return mob;
	}

	static double horizontalDistance(net.minecraft.world.entity.Entity a, net.minecraft.world.entity.Entity b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	static Vec3 relative(GameTestHelper helper, net.minecraft.world.entity.Entity entity) {
		return helper.relativeVec(entity.position());
	}

	/**
	 * {@link GameTestHelper#onEachTick} that stops once the test has finished. Vanilla stores per-tick
	 * actions and {@code runAfterDelay} callbacks in one hash map keyed by lambda identity, so on the
	 * tick a delayed {@code succeed()} fires the per-tick check may run after it, see the entities that
	 * {@code succeed()} just discarded as dead, and turn a pass into a failure.
	 */
	static void onEachTick(GameTestHelper helper, Runnable action) {
		GameTestInfo info = testInfo(helper);
		helper.onEachTick(() -> {
			if (!info.isDone()) {
				action.run();
			}
		});
	}

	/** Runs cleanup whether the test passes or fails, so mock players never leak into later tests. */
	static void onFinish(GameTestHelper helper, Runnable cleanup) {
		testInfo(helper).addListener(new GameTestListener() {
			@Override
			public void testStructureLoaded(GameTestInfo testInfo) {
			}

			@Override
			public void testPassed(GameTestInfo testInfo, GameTestRunner runner) {
				cleanup.run();
			}

			@Override
			public void testFailed(GameTestInfo testInfo, GameTestRunner runner) {
				cleanup.run();
			}

			@Override
			public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {
			}
		});
	}

	private static GameTestInfo testInfo(GameTestHelper helper) {
		try {
			Field field = GameTestHelper.class.getDeclaredField("testInfo");
			field.setAccessible(true);
			return (GameTestInfo) field.get(helper);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("GameTestHelper.testInfo not accessible", exception);
		}
	}
}
