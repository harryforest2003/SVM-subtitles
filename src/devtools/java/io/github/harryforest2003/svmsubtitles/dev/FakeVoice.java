package io.github.harryforest2003.svmsubtitles.dev;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import io.github.harryforest2003.svmsubtitles.audio.Wav;
import io.github.harryforest2003.svmsubtitles.client.ClientSubtitles;
import io.github.harryforest2003.svmsubtitles.server.ServerSubtitles;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/** Plays the bundled sample sentence as if a player said it, at real-time pace. */
final class FakeVoice {
	private static final int FRAME = 960;

	private FakeVoice() {
	}

	/** Server mode: Opus-encode with Simple Voice Chat's encoder and deliver it as microphone packets. */
	static void speakToServer(VoicechatServerApi api, ServerSubtitles subtitles, ServerPlayer player, boolean whispering) throws IOException {
		VoicechatConnection connection = proxy(VoicechatConnection.class, name -> switch (name) {
			case "getPlayer" -> api.fromServerPlayer(player);
			case "isInGroup", "isDisabled" -> false;
			case "isConnected", "isInstalled" -> true;
			default -> null;
		});
		OpusEncoder encoder = api.createEncoder();
		try {
			forEachFrame(frame -> {
				subtitles.onMicrophonePacket(packet(api, connection, encoder.encode(frame), whispering));
			});
		} finally {
			encoder.close();
		}
		subtitles.onMicrophonePacket(packet(api, connection, new byte[0], false));
	}

	/** Client mode: hand the audio to the client as this player's own microphone. */
	static void speakToClient() throws IOException {
		forEachFrame(frame -> ClientSubtitles.INSTANCE.onOwnVoice(frame, false));
	}

	private static void forEachFrame(java.util.function.Consumer<short[]> sink) throws IOException {
		short[] audio = sampleAt48k();
		long next = System.nanoTime();
		for (int offset = 0; offset + FRAME <= audio.length; offset += FRAME) {
			sink.accept(Arrays.copyOfRange(audio, offset, offset + FRAME));
			next += 20_000_000L;
			LockSupport.parkNanos(next - System.nanoTime());
		}
	}

	private static MicrophonePacketEvent packet(VoicechatServerApi api, VoicechatConnection sender, byte[] opus, boolean whispering) {
		MicrophonePacket packet = proxy(MicrophonePacket.class, name -> switch (name) {
			case "getOpusEncodedData" -> opus;
			case "isWhispering" -> whispering;
			default -> null;
		});
		return proxy(MicrophonePacketEvent.class, name -> switch (name) {
			case "getPacket" -> packet;
			case "getSenderConnection" -> sender;
			case "getVoicechat" -> api;
			case "isCancellable" -> true;
			case "cancel", "isCancelled" -> false;
			default -> null;
		});
	}

	/** The 16 kHz sample, upsampled to Simple Voice Chat's 48 kHz, with half a second of silence first. */
	private static short[] sampleAt48k() throws IOException {
		float[] audio;
		try (InputStream in = FakeVoice.class.getResourceAsStream("/svm_subtitles/benchmark.wav")) {
			audio = Wav.decode(in.readAllBytes()).samples();
		}
		int lead = 24_000;
		short[] out = new short[lead + audio.length * 3];
		for (int i = 0; i < audio.length; i++) {
			float a = audio[i];
			float b = i + 1 < audio.length ? audio[i + 1] : a;
			for (int k = 0; k < 3; k++) {
				out[lead + i * 3 + k] = (short) Math.round((a + (b - a) * k / 3f) * 32767f);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Function<String, Object> answers) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> switch (method.getName()) {
			case "hashCode" -> System.identityHashCode(self);
			case "equals" -> self == args[0];
			case "toString" -> "Fake" + type.getSimpleName();
			default -> answers.apply(method.getName());
		});
	}
}
