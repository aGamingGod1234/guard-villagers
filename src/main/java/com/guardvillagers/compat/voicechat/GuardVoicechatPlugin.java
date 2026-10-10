package com.guardvillagers.compat.voicechat;

import com.guardvillagers.voice.VoiceBridge;
import com.guardvillagers.voice.VoiceConfig;
import com.guardvillagers.voice.VoicePipeline;
import com.guardvillagers.voice.VoiceRuntime;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple Voice Chat entrypoint ({@code "voicechat"} in fabric.mod.json). Fabric only instantiates it when SVC
 * is installed, so this package is the only code that ever loads {@code de.maxhenkel} classes.
 *
 * <p>Capture: SVC sends microphone packets only while a player's push-to-talk (or voice activation) is on, so
 * each packet belongs to exactly one speaking player. The packet is copied on the SVC thread, then on the
 * server thread it is dropped unless {@link VoiceRuntime#shouldCapture} passes (consented, near their own
 * guards, within rate limits); only then is it decoded.
 */
public final class GuardVoicechatPlugin implements VoicechatPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger("guardvillagers/voicechat");
	private static final int MAX_OPUS_PACKET_BYTES = 8_192;
	private static final long ADAPTIVE_SILENCE_MILLIS = 160L;
	private static final int SAMPLE_RATE = 48_000;
	private volatile SvcVoiceBridge bridge;

	@Override
	public String getPluginId() {
		return "guardvillagers";
	}

	@Override
	public void initialize(VoicechatApi api) {
		// Makes sure the voice config and pipeline follow the server lifecycle even if nothing else wired it.
		VoiceRuntime.init();
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> started(event.getVoicechat()));
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> stopped());
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
		registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
			SvcVoiceBridge current = bridge;
			if (current != null && event.getPlayerUuid() != null) current.cancelCapture(event.getPlayerUuid());
		});
	}

	private void started(VoicechatServerApi api) {
		try {
			VolumeCategory category = api.volumeCategoryBuilder()
					.setId(SvcVoiceBridge.CATEGORY_ID)
					.setName("Guard Voices")
					.setDescription("Your guards' spoken callouts and replies")
					.build();
			api.registerVolumeCategory(category);
			VoiceConfig config = VoiceRuntime.pipeline().map(VoicePipeline::config).orElseGet(VoiceConfig::defaults);
			SvcVoiceBridge created = new SvcVoiceBridge(api, newEngine(config));
			SvcVoiceBridge previous = bridge;
			bridge = created;
			VoiceBridge.install(created);
			if (previous != null) {
				VoiceBridge.uninstall(previous);
				previous.close();
			}
			LOGGER.info("Guard Villagers voice plugin registered with Simple Voice Chat (volume category '{}')",
					SvcVoiceBridge.CATEGORY_ID);
		} catch (RuntimeException exception) {
			LOGGER.error("Guard Villagers could not start its Simple Voice Chat integration", exception);
		}
	}

	private static SpeechCaptureEngine newEngine(VoiceConfig config) {
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
				runnable -> Thread.ofPlatform().daemon().name("guardvillagers-voice-endpoint").unstarted(runnable));
		long silence = config.voiceInput().endpointSilenceMs();
		return new SpeechCaptureEngine(
				(player, samples, endpointMillis) -> VoiceRuntime.pipeline()
						.<CompletableFuture<?>>map(pipeline -> pipeline.transcribe(player, samples, endpointMillis))
						.orElseGet(() -> CompletableFuture.completedFuture(null)),
				scheduler,
				Math.min(ADAPTIVE_SILENCE_MILLIS, silence),
				silence,
				SAMPLE_RATE / 2,
				(int) ((long) SAMPLE_RATE * config.voiceInput().maxUtteranceMs() / 1000L),
				config.openai().sttTimeoutMs() + 5_000L,
				System::nanoTime);
	}

	private void stopped() {
		SvcVoiceBridge current = bridge;
		bridge = null;
		if (current != null) {
			VoiceBridge.uninstall(current);
			current.close();
		}
	}

	private void onMicrophonePacket(MicrophonePacketEvent event) {
		try {
			SvcVoiceBridge current = bridge;
			if (current == null || event.getPacket() == null) return;
			VoicechatConnection sender = event.getSenderConnection();
			if (sender == null || sender.getPlayer() == null) return;
			byte[] opus = event.getPacket().getOpusEncodedData();
			if (opus == null || opus.length == 0 || opus.length > MAX_OPUS_PACKET_BYTES) return;
			UUID playerId = sender.getPlayer().getUuid();
			byte[] copy = opus.clone();
			VoicechatServerApi api = event.getVoicechat();
			MinecraftServer server = VoiceRuntime.server().orElse(null);
			if (server == null) return;
			server.execute(() -> acceptOnServer(current, api, playerId, copy));
		} catch (RuntimeException exception) {
			LOGGER.error("Guard voice capture failed", exception);
		}
	}

	private void acceptOnServer(SvcVoiceBridge current, VoicechatServerApi api, UUID playerId, byte[] opus) {
		try {
			if (bridge != current || !VoiceRuntime.shouldCapture(playerId)) return;
			current.accept(playerId, opus, () -> decoder(api.createDecoder()));
		} catch (RuntimeException exception) {
			LOGGER.error("Guard voice capture failed", exception);
		}
	}

	private static SpeechCaptureEngine.Decoder decoder(OpusDecoder decoder) {
		if (decoder == null) throw new IllegalStateException("Simple Voice Chat did not create an Opus decoder");
		return new SpeechCaptureEngine.Decoder() {
			@Override
			public short[] decode(byte[] opus) {
				return decoder.decode(opus);
			}

			@Override
			public void close() {
				decoder.close();
			}
		};
	}
}
