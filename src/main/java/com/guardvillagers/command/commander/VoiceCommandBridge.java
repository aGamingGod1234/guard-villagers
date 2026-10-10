package com.guardvillagers.command.commander;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.command.language.GrammarInterpreter;
import com.guardvillagers.command.language.OrderInterpreter;
import com.guardvillagers.command.language.llm.LlmConfig;
import com.guardvillagers.command.language.llm.LlmInterpreter;
import com.guardvillagers.command.order.OrderValidator;
import com.guardvillagers.command.order.OwnerRoster;
import com.guardvillagers.data.GuardCommandState;
import com.guardvillagers.voice.VoiceBridge;
import com.guardvillagers.voice.VoiceConfig;
import com.guardvillagers.voice.VoiceConsentStore;
import com.guardvillagers.voice.VoicePipeline;
import com.guardvillagers.voice.VoiceRuntime;
import com.guardvillagers.voice.VoiceTranscriptListener;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Connects the speech package to the commander: transcripts go through the same interpreter as {@code /guards say},
 * callouts are spoken by the commander, consent is saved with the chain of command, and the LLM interpreter is built
 * from the voice config. Also handles the chat prefix ({@code !g follow me}). Everything degrades to text when Simple
 * Voice Chat or the API key is missing.
 */
public final class VoiceCommandBridge {
	private static final Map<UUID, String> PROMPT_HINTS = new ConcurrentHashMap<>();
	/**
	 * Saved consent per player, copied from {@link GuardCommandState} on the server thread. The voice pipeline asks
	 * from STT, HTTP and voice-chat threads, which must never touch saved data (plain maps the autosave walks).
	 */
	private static final Map<UUID, Optional<Boolean>> CONSENT = new ConcurrentHashMap<>();
	/** Players already offered voice this session (cleared when they leave). */
	private static final java.util.Set<UUID> PROMPTED = ConcurrentHashMap.newKeySet();
	private static final int MAX_HINT_LENGTH = 400;

	private VoiceCommandBridge() {
	}

	public static void init() {
		VoiceRuntime.init();
		VoiceRuntime.setTranscriptListener(new VoiceTranscriptListener() {
			@Override
			public void onTranscript(VoiceTranscript transcript) {
				VoiceRuntime.server().ifPresent(server -> {
					ServerPlayer player = server.getPlayerList().getPlayer(transcript.player());
					if (player != null) {
						VoicePipeline pipeline = VoiceRuntime.pipeline().orElse(null);
						if (pipeline != null && pipeline.config().chat().showTranscripts()) {
							player.sendSystemMessage(Component.literal("Heard: " + transcript.text()).withStyle(ChatFormatting.GRAY));
						}
						CommanderService.hear(player, transcript.text(), false);
					}
				});
			}

			@Override
			public void onRecognitionFailed(UUID player) {
				VoiceRuntime.server().ifPresent(server -> CommanderCallouts.say(server, player,
						CommanderCallouts.text("guardvillagers.order.llm_unavailable", "Commander couldn't hear you, try again."),
						CommanderCallouts.Kind.ACK));
			}
		});
		VoiceRuntime.setConsentStore(new VoiceConsentStore() {
			@Override
			public Optional<Boolean> get(UUID player) {
				return consent(player);
			}

			@Override
			public void set(UUID player, boolean granted) {
				CONSENT.put(player, Optional.of(granted));
				VoiceRuntime.server().ifPresent(server -> {
					Runnable save = () -> {
						GuardCommandState state = GuardHierarchy.state(server);
						state.getOrCreate(player).setVoiceConsent(granted);
						state.setDirty();
					};
					if (server.isSameThread()) {
						save.run();
					} else {
						server.execute(save);
					}
				});
			}
		});
		VoiceRuntime.setPromptHints(player -> PROMPT_HINTS.get(player));
		CommanderCallouts.setSpeaker(VoiceCommandBridge::speak);

		// After VoiceRuntime has loaded the config on SERVER_STARTING.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> rebuildInterpreter());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PROMPT_HINTS.clear();
			CONSENT.clear();
			PROMPTED.clear();
			// The LLM interpreter owns an HTTP client and executor; do not leave them running after the server.
			OrderInterpreter previous = CommanderService.interpreter();
			CommanderService.setInterpreter(OrderInterpreter.grammarOnly());
			previous.close();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			CONSENT.put(handler.getPlayer().getUUID(), storedConsent(server, handler.getPlayer().getUUID()));
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> !interceptChat(sender, message.signedContent()));
	}

	/** Re-reads the voice config into the order interpreter (LLM on when enabled and keyed, grammar only otherwise). */
	public static void rebuildInterpreter() {
		Optional<VoiceConfig> config = VoiceRuntime.pipeline().map(VoicePipeline::config);
		OrderInterpreter previous = CommanderService.interpreter();
		if (config.isEmpty()) {
			CommanderService.setInterpreter(OrderInterpreter.grammarOnly());
		} else {
			VoiceConfig voice = config.get();
			LlmConfig llm = new LlmConfig(voice.llm().enabled(), voice.llm().model(), voice.openai().apiKeyEnv(),
					voice.openai().baseUrl(), Duration.ofMillis(voice.openai().llmTimeoutMs()), voice.llm().maxPerPlayerPerMinute(),
					LlmConfig.DEFAULT_REASONING_EFFORT, LlmConfig.DEFAULT_MAX_OUTPUT_TOKENS);
			Optional<LlmInterpreter> interpreter = LlmInterpreter.create(llm, System::getenv);
			CommanderService.setInterpreter(new OrderInterpreter(new GrammarInterpreter(), new OrderValidator(), interpreter));
		}
		if (previous != CommanderService.interpreter()) {
			previous.close();
		}
	}

	/**
	 * Reserves one LLM call within the voice pipeline's per-player and server-wide budgets. Returns the release to
	 * run when the call ends, or null when over budget. With no pipeline the interpreter's own per-player limit
	 * applies and the release does nothing.
	 */
	static Runnable acquireLlm(UUID player) {
		VoicePipeline pipeline = VoiceRuntime.pipeline().orElse(null);
		if (pipeline == null) {
			return () -> {
			};
		}
		com.guardvillagers.voice.VoiceRateLimiter limiter = pipeline.rateLimiter();
		if (!limiter.acquireLlm(player).allowed()) {
			return null;
		}
		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		return () -> {
			if (released.compareAndSet(false, true)) {
				limiter.releaseLlm();
			}
		};
	}

	/** Keeps the STT spelling hint (group and guard names) fresh; called from the commander tick, server thread. */
	static void refreshPromptHint(ServerPlayer player) {
		OwnerRoster roster = CommanderService.roster(player);
		StringBuilder hint = new StringBuilder("Guard orders. Groups: ");
		hint.append(String.join(", ", roster.groupNames())).append(". Guards: ").append(String.join(", ", roster.guardNames()));
		String text = hint.length() > MAX_HINT_LENGTH ? hint.substring(0, MAX_HINT_LENGTH) : hint.toString();
		PROMPT_HINTS.put(player.getUUID(), text);
	}

	static void forget(UUID player) {
		PROMPT_HINTS.remove(player);
		CONSENT.remove(player);
		PROMPTED.remove(player);
	}

	/**
	 * The player's saved choice, from any thread. A miss on the server thread reads saved data and caches it; a miss
	 * on any other thread answers "declined" rather than touching saved data, so an unknown player is never captured
	 * on the strength of {@code consentDefault} alone. The server thread re-checks before a transcript is used.
	 */
	static Optional<Boolean> consent(UUID player) {
		Optional<Boolean> cached = CONSENT.get(player);
		if (cached != null) {
			return cached;
		}
		MinecraftServer server = VoiceRuntime.server().orElse(null);
		if (server == null || !server.isSameThread()) {
			return Optional.of(false);
		}
		Optional<Boolean> stored = storedConsent(server, player);
		CONSENT.put(player, stored);
		return stored;
	}

	private static Optional<Boolean> storedConsent(MinecraftServer server, UUID player) {
		GuardCommandState.OwnerCommand command = GuardHierarchy.state(server).peek(player);
		return command == null ? Optional.empty() : command.voiceConsent();
	}

	private static void speak(ServerPlayer owner, com.guardvillagers.entity.GuardEntity commander, String line,
			CommanderCallouts.Kind kind) {
		if (commander == null || VoiceRuntime.pipeline().isEmpty()) {
			return;
		}
		VoicePipeline pipeline = VoiceRuntime.pipeline().get();
		if (!pipeline.outputAvailable() || !pipeline.rateLimiter().tryCallout(owner.getUUID()).allowed()) {
			return;
		}
		VoiceRuntime.speak(commander, line).exceptionally(error -> {
			GuardVillagersMod.LOGGER.debug("Commander speech failed: {}", error.toString());
			return VoicePipeline.SpeakResult.FAILED;
		});
	}

	private static boolean interceptChat(ServerPlayer sender, String text) {
		VoicePipeline pipeline = VoiceRuntime.pipeline().orElse(null);
		String prefix = pipeline == null ? "!g " : pipeline.config().chat().prefix();
		boolean enabled = pipeline == null || pipeline.config().chat().prefixEnabled();
		if (!enabled || text == null || !text.startsWith(prefix)) {
			return false;
		}
		String order = text.substring(prefix.length()).strip();
		if (!order.isEmpty()) {
			MinecraftServer server = sender.level().getServer();
			server.execute(() -> CommanderService.hear(sender, order, true));
		}
		return true;
	}

	/**
	 * Offers voice commands once per session, the first time the player is near their own loaded guards and has
	 * never chosen. (At join the player's guards are usually not loaded yet, so asking then rarely worked.) Called
	 * from the commander tick, server thread.
	 */
	static void maybeOfferConsent(MinecraftServer server, ServerPlayer player) {
		UUID id = player.getUUID();
		if (PROMPTED.contains(id) || VoiceBridge.current().isEmpty()
				|| !VoiceRuntime.pipeline().map(VoicePipeline::inputAvailable).orElse(false)) {
			return;
		}
		if (consent(id).isPresent()) {
			PROMPTED.add(id);
			return;
		}
		if (!VoiceRuntime.nearOwnGuards(server, id)) {
			return;
		}
		PROMPTED.add(id);
		player.sendSystemMessage(Component.translatable("commands.guardvillagers.voice.prompt")
				.append(" ")
				.append(Component.translatable("commands.guardvillagers.voice.prompt.enable")
						.withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
								.withClickEvent(new ClickEvent.RunCommand("/guards voice on")))));
	}
}
