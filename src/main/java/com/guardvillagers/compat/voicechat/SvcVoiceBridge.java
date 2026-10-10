package com.guardvillagers.compat.voicechat;

import com.guardvillagers.voice.VoiceBridge;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;

/**
 * {@link VoiceBridge} over Simple Voice Chat: guard speech plays from the guard through an entity audio
 * channel in the "Guard Voices" volume category, so it is positional and players get their own slider.
 * One line per speaker at a time; SVC's audio player paces 20 ms frames and closes the encoder itself.
 */
final class SvcVoiceBridge implements VoiceBridge {
	/** SVC category ids are short lowercase identifiers. */
	static final String CATEGORY_ID = "guard_voices";
	static final int FRAME_SAMPLES = 960;
	private final VoicechatServerApi api;
	private volatile SpeechCaptureEngine engine;
	private final Map<UUID, ActivePlayback> active = new ConcurrentHashMap<>();

	SvcVoiceBridge(VoicechatServerApi api, SpeechCaptureEngine engine) {
		this.api = Objects.requireNonNull(api, "api");
		this.engine = Objects.requireNonNull(engine, "engine");
	}

	@Override
	public String name() {
		return "Simple Voice Chat";
	}

	@Override
	public Playback play(Entity speaker, short[] pcm48k, float distance) {
		UUID speakerId = speaker.getUUID();
		stop(speakerId);
		EntityAudioChannel channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(speaker));
		if (channel == null) throw new IllegalStateException("Simple Voice Chat refused an entity audio channel");
		channel.setDistance(distance);
		channel.setCategory(CATEGORY_ID);
		OpusEncoder encoder = api.createEncoder();
		if (encoder == null) throw new IllegalStateException("Simple Voice Chat did not create an Opus encoder");
		AudioPlayer player = api.createAudioPlayer(channel, encoder, new Frames(pcm48k, speaker));
		ActivePlayback playback = new ActivePlayback(speakerId, player);
		player.setOnStopped(playback::onStopped);
		active.put(speakerId, playback);
		player.startPlaying();
		return playback;
	}

	@Override
	public void stop(UUID speakerId) {
		ActivePlayback previous = active.remove(speakerId);
		if (previous != null) previous.stop();
	}

	void accept(UUID playerId, byte[] opus, SpeechCaptureEngine.DecoderFactory decoders) {
		engine.accept(playerId, opus, decoders);
	}

	@Override
	public void cancelCapture(UUID playerId) {
		engine.cancel(playerId);
	}

	/** Swaps in an engine built from a reloaded config; whatever the old one was capturing is dropped. */
	void replaceEngine(SpeechCaptureEngine replacement) {
		SpeechCaptureEngine previous = engine;
		engine = Objects.requireNonNull(replacement, "replacement");
		previous.close();
	}

	void close() {
		for (UUID speaker : active.keySet().toArray(UUID[]::new)) stop(speaker);
		engine.close();
	}

	/** Exact 960-sample frames (SVC rejects any other size); ends early if the speaker is removed. */
	static final class Frames implements Supplier<short[]> {
		private final short[] samples;
		private final Entity speaker;
		private int position;

		Frames(short[] samples, Entity speaker) {
			this.samples = samples;
			this.speaker = speaker;
		}

		@Override
		public short[] get() {
			if (position >= samples.length || speaker != null && speaker.isRemoved()) return null;
			short[] frame = new short[FRAME_SAMPLES];
			int count = Math.min(FRAME_SAMPLES, samples.length - position);
			System.arraycopy(samples, position, frame, 0, count);
			position += count;
			return frame;
		}
	}

	private final class ActivePlayback implements Playback {
		private final UUID speakerId;
		private final AudioPlayer player;
		private final CompletableFuture<Void> finished = new CompletableFuture<>();

		private ActivePlayback(UUID speakerId, AudioPlayer player) {
			this.speakerId = speakerId;
			this.player = player;
		}

		private void onStopped() {
			active.remove(speakerId, this);
			finished.complete(null);
		}

		@Override
		public CompletableFuture<Void> finished() {
			return finished;
		}

		@Override
		public void stop() {
			active.remove(speakerId, this);
			try {
				if (player.isStarted() && !player.isStopped()) player.stopPlaying();
			} finally {
				if (!player.isStarted()) finished.complete(null);
			}
		}
	}
}
