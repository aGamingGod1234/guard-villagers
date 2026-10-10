package com.guardvillagers.command.commander;

import com.guardvillagers.entity.GuardEntity;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Voice output for commander callouts. The voice package registers an implementation with
 * {@link CommanderCallouts#setSpeaker}; without one, callouts are chat and action bar only.
 *
 * <p>Called on the server thread right after the chat line was sent. Implementations must return quickly (hand the
 * synthesis to another thread) and must never throw; a failure only loses the audio, never the order.
 */
@FunctionalInterface
public interface CalloutSpeaker {
	CalloutSpeaker NONE = (owner, commander, line, kind) -> {
	};

	/**
	 * @param owner     the player the line is for
	 * @param commander the guard that should speak it (positional audio), or {@code null} when no commander is loaded
	 * @param line      plain text, at most {@link CommanderCallouts#MAX_LINE_LENGTH} characters
	 * @param kind      why the line was produced; e.g. skip TTS for {@link CommanderCallouts.Kind#STATUS} if it is long
	 */
	void speak(ServerPlayer owner, @Nullable GuardEntity commander, String line, CommanderCallouts.Kind kind);
}
