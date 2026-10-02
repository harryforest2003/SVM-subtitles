package io.github.harryforest2003.svmsubtitles.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal 16-bit PCM WAV reading and writing. */
public final class Wav {
	private Wav() {
	}

	/** Encodes mono float samples as a 16-bit PCM WAV file. */
	public static byte[] encode(float[] samples, int sampleRate) {
		int dataBytes = samples.length * 2;
		ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataBytes).put(new byte[]{'W', 'A', 'V', 'E'});
		buffer.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
		buffer.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataBytes);
		for (float sample : samples) {
			float clamped = Math.max(-1f, Math.min(1f, sample));
			buffer.putShort((short) Math.round(clamped * 32767f));
		}
		return buffer.array();
	}

	/** Decodes a 16-bit PCM WAV file into mono float samples (channels are averaged). */
	public static Decoded decode(byte[] wav) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		if (wav.length < 12 || buffer.getInt(0) != 0x46464952 || buffer.getInt(8) != 0x45564157) {
			throw new IOException("Not a WAV file");
		}
		int channels = 0;
		int sampleRate = 0;
		int bits = 0;
		int position = 12;
		while (position + 8 <= wav.length) {
			int id = buffer.getInt(position);
			int size = buffer.getInt(position + 4);
			int body = position + 8;
			if (id == 0x20746d66) { // "fmt "
				int format = buffer.getShort(body) & 0xFFFF;
				channels = buffer.getShort(body + 2);
				sampleRate = buffer.getInt(body + 4);
				bits = buffer.getShort(body + 14);
				if (format != 1 && format != 0xFFFE) {
					throw new IOException("Only PCM WAV files are supported");
				}
			} else if (id == 0x61746164) { // "data"
				if (bits != 16 || channels < 1) {
					throw new IOException("Only 16-bit PCM WAV files are supported");
				}
				int frames = Math.min(size, wav.length - body) / (2 * channels);
				float[] samples = new float[frames];
				for (int i = 0; i < frames; i++) {
					float sum = 0;
					for (int c = 0; c < channels; c++) {
						sum += buffer.getShort(body + (i * channels + c) * 2) / 32768f;
					}
					samples[i] = sum / channels;
				}
				return new Decoded(samples, sampleRate);
			}
			position = body + size + (size & 1);
		}
		throw new IOException("WAV file has no audio data");
	}

	public record Decoded(float[] samples, int sampleRate) {
	}
}
