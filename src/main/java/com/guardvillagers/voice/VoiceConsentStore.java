package com.guardvillagers.voice;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player voice consent. An empty value means the player never chose, so the config's
 * {@code voiceInput.consentDefault} applies. The default store is in memory only; the lead backs it with
 * saved data ({@code GuardCommandState}) through {@link VoiceRuntime#setConsentStore}.
 */
public interface VoiceConsentStore {
	Optional<Boolean> get(UUID player);

	void set(UUID player, boolean granted);

	static VoiceConsentStore inMemory() {
		Map<UUID, Boolean> values = new ConcurrentHashMap<>();
		return new VoiceConsentStore() {
			@Override
			public Optional<Boolean> get(UUID player) {
				return Optional.ofNullable(values.get(player));
			}

			@Override
			public void set(UUID player, boolean granted) {
				values.put(player, granted);
			}
		};
	}
}
