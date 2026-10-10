package com.guardvillagers.voice;

/** Linear-interpolation resampler for mono signed 16-bit PCM (port of agent-arena {@code pcm-audio.mjs}). */
public final class PcmResampler {
	private PcmResampler() {
	}

	/**
	 * Resamples {@code input} from {@code sourceRate} to {@code targetRate}, reading at most
	 * {@code maxSeconds} of input. Output length is {@code floor(n * target / source)} (at least 1 for
	 * non-empty input). The first output sample equals the first input sample; positions past the last input
	 * sample hold the last value.
	 */
	public static short[] resample(short[] input, int sourceRate, int targetRate, double maxSeconds) {
		if (input == null) throw new IllegalArgumentException("input must not be null");
		if (sourceRate <= 0 || targetRate <= 0) throw new IllegalArgumentException("sample rates must be positive");
		if (!(maxSeconds > 0) || !Double.isFinite(maxSeconds)) throw new IllegalArgumentException("maxSeconds must be positive");
		int sourceSamples = (int) Math.min(input.length, Math.floor(sourceRate * maxSeconds));
		if (sourceSamples == 0) return new short[0];
		if (sourceRate == targetRate) return java.util.Arrays.copyOf(input, sourceSamples);
		long targetSamples = Math.min((long) Math.floor(targetRate * maxSeconds),
				Math.max(1L, (long) sourceSamples * targetRate / sourceRate));
		short[] output = new short[(int) targetSamples];
		for (int index = 0; index < output.length; index++) {
			double position = (double) index * sourceRate / targetRate;
			int left = Math.min(sourceSamples - 1, (int) Math.floor(position));
			int right = Math.min(sourceSamples - 1, left + 1);
			double fraction = position - left;
			double value = input[left] + (input[right] - input[left]) * fraction;
			output[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
		}
		return output;
	}

	/** Decodes little-endian signed 16-bit bytes; a trailing odd byte is rejected. */
	public static short[] fromLittleEndian(byte[] bytes) {
		if (bytes.length % 2 != 0) throw new IllegalArgumentException("PCM must contain whole 16-bit samples");
		short[] samples = new short[bytes.length / 2];
		for (int i = 0; i < samples.length; i++) {
			samples[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
		}
		return samples;
	}

	/** A 44-byte RIFF/WAVE header plus little-endian data: mono, 16-bit, {@code sampleRate} Hz. */
	public static byte[] toWav(short[] samples, int sampleRate) {
		int dataBytes = samples.length * 2;
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(44 + dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		buffer.put(new byte[] {'R', 'I', 'F', 'F'}).putInt(36 + dataBytes)
				.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
				.putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
				.put(new byte[] {'d', 'a', 't', 'a'}).putInt(dataBytes);
		for (short sample : samples) buffer.putShort(sample);
		return buffer.array();
	}
}
