package com.guardvillagers.voice;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.Entity;

/**
 * What core voice code needs from a voice-chat mod. The Simple Voice Chat plugin installs an implementation
 * when its server starts and removes it when it stops; without SVC {@link #current()} stays empty and callers
 * fall back to chat. Core code must only reach SVC through this interface.
 */
public interface VoiceBridge {
	/** Human-readable backend name for status output, e.g. "Simple Voice Chat". */
	String name();

	/**
	 * Plays 48 kHz mono PCM positionally from {@code speaker}, audible within {@code distance} blocks, in the
	 * "Guard Voices" volume category. A new line from the same speaker stops the previous one. Playback ends on
	 * its own if the speaker is removed. Call on the server thread.
	 */
	Playback play(Entity speaker, short[] pcm48k, float distance);

	/** Stops whatever {@code speakerId} is saying. */
	void stop(UUID speakerId);

	/** Drops any utterance being captured or transcribed for this player (consent revoked, logout). */
	void cancelCapture(UUID playerId);

	interface Playback {
		/** Completes when the line finishes or is stopped. */
		CompletableFuture<Void> finished();

		void stop();
	}

	static Optional<VoiceBridge> current() {
		return Optional.ofNullable(Holder.CURRENT.get());
	}

	static void install(VoiceBridge bridge) {
		Holder.CURRENT.set(Objects.requireNonNull(bridge, "bridge"));
	}

	/** Removes {@code bridge} if it is still the installed one. */
	static void uninstall(VoiceBridge bridge) {
		Holder.CURRENT.compareAndSet(bridge, null);
	}

	final class Holder {
		private static final AtomicReference<VoiceBridge> CURRENT = new AtomicReference<>();

		private Holder() {
		}
	}
}
