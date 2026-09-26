package starking.eccles.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class ADPCMTest {

    @Test
    public void decode_ofNullOrTooShort_returnsEmptyArray() {
        assertEquals(0, ADPCM.decode(null).length);
        assertEquals(0, ADPCM.decode(new byte[]{1, 2}).length);
    }

    @Test
    public void encodeThenDecode_silenceRoundTripsExactly() {
        // 16-bit PCM silence (all zero samples) has zero delta at every step, so ADPCM should
        // reproduce it exactly with no quantization error.
        byte[] silence = new byte[200]; // 100 samples of 0x0000
        byte[] encoded = ADPCM.encode(silence, silence.length);
        byte[] decoded = ADPCM.decode(encoded);

        assertEquals(silence.length, decoded.length);
        for (int i = 0; i < silence.length; i++) {
            assertEquals("sample byte " + i, 0, decoded[i]);
        }
    }

    @Test
    public void encodeThenDecode_sineWave_staysWithinAdpcmQuantizationTolerance() {
        int sampleCount = 4000;
        short[] original = new short[sampleCount];
        byte[] pcm = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * i / 50.0) * 12000);
            original[i] = sample;
            pcm[i * 2] = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        byte[] encoded = ADPCM.encode(pcm, pcm.length);
        byte[] decoded = ADPCM.decode(encoded);

        assertEquals(pcm.length, decoded.length);

        // IMA-ADPCM is lossy by design (4 bits/sample vs. 16-bit PCM); verify the decoded
        // signal tracks the original within the codec's expected step-size error rather than
        // requiring bit-exact output.
        long totalAbsError = 0;
        for (int i = 0; i < sampleCount; i++) {
            short decodedSample = (short) ((decoded[i * 2] & 0xFF) | (decoded[i * 2 + 1] << 8));
            totalAbsError += Math.abs(decodedSample - original[i]);
        }
        double meanAbsError = totalAbsError / (double) sampleCount;
        assertTrue("mean absolute reconstruction error was unexpectedly large: " + meanAbsError,
                meanAbsError < 2000);
    }

    @Test
    public void encode_outputSizeMatchesExpectedPackedNibbleFormula() {
        // 3-byte header (predictor:2 + index:1) plus ceil(sampleCount/2) packed nibble bytes.
        int sampleCount = 101; // odd, to exercise the "trailing half nibble" flush path
        byte[] pcm = new byte[sampleCount * 2];
        new Random(42).nextBytes(pcm);

        byte[] encoded = ADPCM.encode(pcm, pcm.length);

        assertEquals(3 + (sampleCount + 1) / 2, encoded.length);
    }

    @Test
    public void decode_indexByteAboveValidRange_isClampedNotThrown() {
        // A corrupt/malicious index byte (table has only 89 entries, 0..88) must not cause an
        // ArrayIndexOutOfBoundsException; the implementation already clamps it into range
        // (decode(), line "if(index>88) index=88;"). 2 data bytes = 4 nibbles = 4 samples = 8
        // PCM bytes.
        byte[] data = new byte[]{0, 0, (byte) 255, 0x12, 0x34};
        byte[] decoded = ADPCM.decode(data); // must not throw
        assertEquals(8, decoded.length);
    }
}
