package com.guardvillagers.voice;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.entity.GuardEntity;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server lifecycle for voice: loads {@code config/guardvillagers-voice.json} on server start, builds the
 * {@link VoicePipeline}, and closes it on stop. Hooks set here (transcript listener, consent store, prompt
 * hints, capture filter) survive reloads. Safe to call {@link #init()} more than once; the Simple Voice Chat
 * plugin calls it too, so voice works even before the command module is wired.
 */
public final class VoiceRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("guardvillagers/voice");
	private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
	private static volatile MinecraftServer server;
	private static volatile VoicePipeline pipeline;
	private static volatile VoiceTranscriptListener listener;
	private static volatile VoiceConsentStore consent = VoiceConsentStore.inMemory();
	private static volatile Function<UUID, String> promptHints = player -> null;
	private static volatile BiPredicate<MinecraftServer, UUID> captureFilter = VoiceRuntime::nearOwnGuards;

	private VoiceRuntime() {
	}

	/** Registers the server start/stop hooks once. */
	public static void init() {
		if (!INITIALIZED.compareAndSet(false, true)) return;
		ServerLifecycleEvents.SERVER_STARTING.register(VoiceRuntime::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(ignored -> stop());
	}

	public static Optional<VoicePipeline> pipeline() {
		return Optional.ofNullable(pipeline);
	}

	public static Optional<MinecraftServer> server() {
		return Optional.ofNullable(server);
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(VoiceConfig.FILE_NAME);
	}

	static synchronized void start(MinecraftServer started) {
		server = started;
		replacePipeline(started);
	}

	/** Re-reads the config and API key and rebuilds the pipeline ({@code /guards voice reload}). */
	public static synchronized Optional<VoiceConfig> reload() {
		MinecraftServer current = server;
		if (current == null) return Optional.empty();
		return Optional.of(replacePipeline(current).config());
	}

	private static VoicePipeline replacePipeline(MinecraftServer target) {
		VoiceConfig config = VoiceConfig.load(configFile());
		config.warnings().forEach(warning -> LOGGER.warn("Guard voice config: {}", warning));
		Optional<String> key = config.apiKey();
		if (key.isEmpty() && config.voiceInput().enabled()) {
			LOGGER.warn("Guard voice: environment variable {} is not set, so voice commands and spoken callouts "
					+ "are off. Text commands still work.", config.openai().apiKeyEnv());
		}
		VoicePipeline created = VoicePipeline.create(config, key, target::execute);
		VoiceTranscriptListener currentListener = listener;
		if (currentListener != null) created.setTranscriptListener(currentListener);
		created.setConsentStore(consent);
		created.setPromptHints(promptHints);
		VoicePipeline previous = pipeline;
		pipeline = created;
		if (previous != null) previous.close();
		return created;
	}

	static synchronized void stop() {
		VoicePipeline previous = pipeline;
		pipeline = null;
		server = null;
		if (previous != null) previous.close();
	}

	// ---- hooks for the command layer ----

	/** Where recognised speech goes (the order interpreter). Runs on the server thread. */
	public static void setTranscriptListener(VoiceTranscriptListener transcriptListener) {
		listener = Objects.requireNonNull(transcriptListener, "listener");
		VoicePipeline current = pipeline;
		if (current != null) current.setTranscriptListener(transcriptListener);
	}

	/** Backs consent with saved data; the default store forgets on restart. */
	public static void setConsentStore(VoiceConsentStore store) {
		consent = Objects.requireNonNull(store, "store");
		VoicePipeline current = pipeline;
		if (current != null) current.setConsentStore(store);
	}

	/** Group and guard names for the player, sent to STT as a spelling hint. May return null. */
	public static void setPromptHints(Function<UUID, String> hints) {
		promptHints = Objects.requireNonNull(hints, "hints");
		VoicePipeline current = pipeline;
		if (current != null) current.setPromptHints(hints);
	}

	/** Replaces the default "owns a loaded guard within captureRangeToOwnGuards blocks" check. Server thread. */
	public static void setCaptureFilter(BiPredicate<MinecraftServer, UUID> filter) {
		captureFilter = Objects.requireNonNull(filter, "filter");
	}

	// ---- entry points ----

	/**
	 * Server-thread gate before any audio is decoded: voice input ready, the player consented, STT limits
	 * allow a request, and the capture filter passes. Everything else is dropped unheard.
	 */
	public static boolean shouldCapture(UUID player) {
		VoicePipeline current = pipeline;
		MinecraftServer currentServer = server;
		if (current == null || currentServer == null || !current.shouldCapture(player)) return false;
		try {
			return captureFilter.test(currentServer, player);
		} catch (RuntimeException exception) {
			LOGGER.warn("Guard voice capture filter failed", exception);
			return false;
		}
	}

	/** Speaks a line from a guard (see {@link VoicePipeline#speak}). Always show the line in chat as well. */
	public static CompletableFuture<VoicePipeline.SpeakResult> speak(Entity guard, String text) {
		VoicePipeline current = pipeline;
		if (current == null) return CompletableFuture.completedFuture(VoicePipeline.SpeakResult.CLOSED);
		return current.speak(guard, text);
	}

	/** For {@code /guards voice status}. */
	public static VoicePipeline.Status status(UUID player) {
		VoicePipeline current = pipeline;
		if (VoiceBridge.current().isEmpty()) return VoicePipeline.Status.SVC_NOT_INSTALLED;
		if (current == null) return VoicePipeline.Status.DISABLED;
		return current.status(player);
	}

	/** Records the player's choice; revoking drops any utterance in flight. */
	public static void setConsent(UUID player, boolean granted) {
		VoicePipeline current = pipeline;
		if (current != null) current.setConsent(player, granted);
		else consent.set(player, granted);
	}

	static boolean nearOwnGuards(MinecraftServer target, UUID playerId) {
		ServerPlayer player = target.getPlayerList().getPlayer(playerId);
		VoicePipeline current = pipeline;
		if (player == null || current == null) return false;
		double range = current.config().voiceInput().captureRangeToOwnGuards();
		double rangeSq = range * range;
		for (GuardEntity guard : GuardOwnershipIndex.getOwnedGuards(target, playerId)) {
			if (guard.isAlive() && guard.level() == player.level() && guard.distanceToSqr(player) <= rangeSq) return true;
		}
		return false;
	}
}
