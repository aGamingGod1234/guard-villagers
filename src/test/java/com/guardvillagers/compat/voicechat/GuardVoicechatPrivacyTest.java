package com.guardvillagers.compat.voicechat;

import static org.junit.jupiter.api.Assertions.*;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GuardVoicechatPrivacyTest {
	private static final UUID PLAYER = new UUID(1, 2);

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
				(object, method, arguments) -> values.apply(method.getName()));
	}

	@Test
	void stoppedPlaybackSuppliesNoMoreAudioFrames() {
		SvcVoiceBridge.Frames frames = new SvcVoiceBridge.Frames(new short[960 * 20]);
		assertEquals(960, frames.get().length);
		frames.stop();
		assertNull(frames.get(), "server death/unload stop signal ends the next audio frame");
	}

	@Test
	void everyGroupAndWhisperAreRejectedBeforeAudioIsRead() throws Exception {
		AtomicReference<Group> group = new AtomicReference<>();
		AtomicBoolean whisper = new AtomicBoolean();
		AtomicInteger reads = new AtomicInteger();
		ServerPlayer senderPlayer = proxy(ServerPlayer.class, name -> name.equals("getUuid") ? PLAYER : null);
		VoicechatConnection sender = proxy(VoicechatConnection.class, name -> switch (name) {
			case "getPlayer" -> senderPlayer;
			case "getGroup" -> group.get();
			case "isInGroup" -> group.get() != null;
			default -> false;
		});
		VoicechatServerApi api = proxy(VoicechatServerApi.class, name -> name.equals("getConnectionOf") ? sender : null);
		MicrophonePacket packet = proxy(MicrophonePacket.class, name -> switch (name) {
			case "isWhispering" -> whisper.get();
			case "getOpusEncodedData" -> { reads.incrementAndGet(); yield new byte[] {1}; }
			default -> null;
		});
		MicrophonePacketEvent event = proxy(MicrophonePacketEvent.class, name -> switch (name) {
			case "getPacket" -> packet;
			case "getSenderConnection" -> sender;
			case "getVoicechat" -> api;
			default -> null;
		});
		try (SpeechCaptureEngine engine = new SpeechCaptureEngine((id, samples, endpoint) -> CompletableFuture.completedFuture(null),
				Executors.newSingleThreadScheduledExecutor(), 10_000, 10_000, 960, 96_000, 20_000, System::nanoTime)) {
			SvcVoiceBridge bridge = new SvcVoiceBridge(api, engine);
			GuardVoicechatPlugin plugin = new GuardVoicechatPlugin();
			var bridgeField = GuardVoicechatPlugin.class.getDeclaredField("bridge");
			bridgeField.setAccessible(true);
			bridgeField.set(plugin, bridge);
			Map<Class<?>, Consumer<?>> handlers = new HashMap<>();
			plugin.registerEvents(new EventRegistration() {
				@Override public <T extends Event> void registerEvent(Class<T> type, Consumer<T> handler, int priority) {
					handlers.put(type, handler);
				}
			});
			@SuppressWarnings("unchecked") Consumer<MicrophonePacketEvent> microphone = (Consumer<MicrophonePacketEvent>) handlers.get(MicrophonePacketEvent.class);
			for (Group.Type type : List.of(Group.Type.NORMAL, Group.Type.OPEN, Group.Type.ISOLATED)) {
				group.set(proxy(Group.class, name -> name.equals("getType") ? type : null));
				microphone.accept(event);
				assertFalse(bridge.isPublicChannel(PLAYER), type.toString());
			}
			group.set(null);
			whisper.set(true);
			microphone.accept(event);
			assertFalse(bridge.isPublicChannel(PLAYER));
			assertEquals(0, reads.get(), "excluded speech must not even read encoded audio");
		}
	}

	@Test
	void enteringAGroupCancelsInFlightPendingAndBufferedSpeech() throws Exception {
		CompletableFuture<Void> request = new CompletableFuture<>();
		AtomicInteger calls = new AtomicInteger();
		VoicechatServerApi api = proxy(VoicechatServerApi.class, name -> null);
		try (SpeechCaptureEngine engine = new SpeechCaptureEngine((id, samples, endpoint) -> {
			calls.incrementAndGet();
			return request;
		}, Executors.newSingleThreadScheduledExecutor(), 10_000, 10_000, 960, 1920, 20_000, System::nanoTime)) {
			SvcVoiceBridge bridge = new SvcVoiceBridge(api, engine);
			SpeechCaptureEngine.DecoderFactory decoder = () -> new SpeechCaptureEngine.Decoder() {
				public short[] decode(byte[] opus) { short[] pcm = new short[960]; Arrays.fill(pcm, (short) 2000); return pcm; }
				public void close() { }
			};
			for (int i = 0; i < 5; i++) bridge.accept(PLAYER, new byte[] {1}, decoder);
			assertEquals(1, calls.get());
			assertTrue(engine.capturing(PLAYER));
			GuardVoicechatPlugin plugin = new GuardVoicechatPlugin();
			var field = GuardVoicechatPlugin.class.getDeclaredField("bridge");
			field.setAccessible(true);
			field.set(plugin, bridge);
			Map<Class<?>, Consumer<?>> handlers = new HashMap<>();
			plugin.registerEvents(new EventRegistration() {
				@Override public <T extends Event> void registerEvent(Class<T> type, Consumer<T> handler, int priority) { handlers.put(type, handler); }
			});
			ServerPlayer player = proxy(ServerPlayer.class, name -> name.equals("getUuid") ? PLAYER : null);
			VoicechatConnection connection = proxy(VoicechatConnection.class, name -> name.equals("getPlayer") ? player : null);
			JoinGroupEvent joining = proxy(JoinGroupEvent.class, name -> name.equals("getConnection") ? connection : null);
			@SuppressWarnings("unchecked") Consumer<JoinGroupEvent> joined = (Consumer<JoinGroupEvent>) handlers.get(JoinGroupEvent.class);
			joined.accept(joining);
			assertTrue(request.isCancelled());
			assertFalse(engine.capturing(PLAYER));
			assertEquals(1, calls.get(), "queued speech was discarded rather than started after cancellation");
		}
	}
}
