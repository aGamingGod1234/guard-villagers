package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PcmResamplerTest {
	@Test
	void outputLengthIsFloorOfScaledInput() {
		assertEquals(48_000, PcmResampler.resample(new short[24_000], 24_000, 48_000, 20).length);
		assertEquals(2, PcmResampler.resample(new short[1], 24_000, 48_000, 20).length);
		assertEquals(1, PcmResampler.resample(new short[1], 48_000, 24_000, 20).length, "never empty for non-empty input");
		assertEquals(0, PcmResampler.resample(new short[0], 24_000, 48_000, 20).length);
		assertEquals(160, PcmResampler.resample(new short[147], 44_100, 48_000, 20).length);
	}

	@Test
	void inputIsCappedAtMaxSeconds() {
		short[] out = PcmResampler.resample(new short[24_000 * 30], 24_000, 48_000, 20);
		assertEquals(48_000 * 20, out.length);
	}

	@Test
	void upsamplingInterpolatesAndHoldsTheLastSample() {
		assertArrayEquals(new short[] {0, 50, 100, 150, 200, 200},
				PcmResampler.resample(new short[] {0, 100, 200}, 24_000, 48_000, 20));
	}

	@Test
	void extremesStayInRange() {
		short[] out = PcmResampler.resample(new short[] {Short.MAX_VALUE, Short.MIN_VALUE}, 24_000, 48_000, 20);
		assertArrayEquals(new short[] {Short.MAX_VALUE, 0, Short.MIN_VALUE, Short.MIN_VALUE}, out);
	}

	@Test
	void sameRateCopies() {
		short[] in = {1, 2, 3};
		short[] out = PcmResampler.resample(in, 48_000, 48_000, 20);
		assertArrayEquals(in, out);
		out[0] = 9;
		assertEquals(1, in[0]);
	}

	@Test
	void littleEndianDecoding() {
		assertArrayEquals(new short[] {1, -1, Short.MIN_VALUE},
				PcmResampler.fromLittleEndian(new byte[] {1, 0, (byte) 0xFF, (byte) 0xFF, 0, (byte) 0x80}));
		assertThrows(IllegalArgumentException.class, () -> PcmResampler.fromLittleEndian(new byte[3]));
	}
}
