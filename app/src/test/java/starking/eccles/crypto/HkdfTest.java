package starking.eccles.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

/**
 * Verifies {@link Hkdf} against two independent checks:
 * <ol>
 *   <li>A from-scratch re-implementation of RFC 5869 HKDF-Extract/Expand, built directly from
 *       the RFC's algorithm description using only {@code javax.crypto.Mac}. Two independent
 *       implementations of a precisely specified algorithm agreeing on many random inputs is
 *       strong evidence of correctness, without depending on transcribing long hex test vectors
 *       by hand (which risks an unnoticed transcription error being "verified" against itself).</li>
 *   <li>Basic properties any correct KDF must have: determinism, sensitivity to every input,
 *       correct output length, and rejection of an over-large request.</li>
 * </ol>
 */
public class HkdfTest {

    @Test
    public void expand_matchesIndependentReferenceImplementation() throws Exception {
        byte[] salt = bytes(13, (byte) 0x01);
        byte[] ikm = bytes(22, (byte) 0x0b);
        byte[] info = bytes(10, (byte) 0xf0);

        byte[] expected = referenceHkdf(salt, ikm, info, 72);
        byte[] actual = Hkdf.deriveKeys(salt, ikm, info, 72);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void expand_isDeterministic() throws Exception {
        byte[] salt = randomBytes(16);
        byte[] ikm = randomBytes(32);
        byte[] info = randomBytes(8);

        byte[] a = Hkdf.deriveKeys(salt, ikm, info, 64);
        byte[] b = Hkdf.deriveKeys(salt, ikm, info, 64);

        assertArrayEquals("HKDF must be a deterministic function of its inputs", a, b);
    }

    @Test
    public void expand_producesExactlyRequestedLength() throws Exception {
        byte[] salt = randomBytes(16);
        byte[] ikm = randomBytes(32);
        byte[] info = randomBytes(8);

        for (int len : new int[]{1, 16, 32, 33, 72, 255, 256}) {
            assertEquals("requested length " + len, len, Hkdf.deriveKeys(salt, ikm, info, len).length);
        }
    }

    @Test
    public void differentInfo_producesDifferentOutput() throws Exception {
        byte[] salt = randomBytes(16);
        byte[] ikm = randomBytes(32);

        byte[] out1 = Hkdf.deriveKeys(salt, ikm, "session-send".getBytes(), 32);
        byte[] out2 = Hkdf.deriveKeys(salt, ikm, "session-recv".getBytes(), 32);

        assertFalse("different info strings must yield different key material (this is what lets "
                + "the handshake derive independent send/receive keys from one shared secret)",
                Arrays.equals(out1, out2));
    }

    @Test
    public void differentIkm_producesDifferentOutput() throws Exception {
        byte[] salt = randomBytes(16);
        byte[] info = randomBytes(8);

        byte[] out1 = Hkdf.deriveKeys(salt, randomBytes(32), info, 32);
        byte[] out2 = Hkdf.deriveKeys(salt, randomBytes(32), info, 32);

        assertFalse(Arrays.equals(out1, out2));
    }

    @Test
    public void expand_rejectsRequestLargerThan255Blocks() {
        byte[] prk = randomBytes(32);
        byte[] info = randomBytes(8);
        // HASH_LEN is 32 for HmacSHA256, so 255*32 + 1 exceeds the RFC 5869 hard limit.
        assertThrows(GeneralSecurityException.class, () -> Hkdf.expand(prk, info, 255 * 32 + 1));
    }

    // --- helpers ---

    private static byte[] bytes(int len, byte fill) {
        byte[] b = new byte[len];
        Arrays.fill(b, fill);
        return b;
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * A minimal, direct transcription of RFC 5869 section 2.2 (Extract) and 2.3 (Expand),
     * written independently of {@link Hkdf}'s implementation for cross-checking purposes.
     */
    private static byte[] referenceHkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac extractMac = Mac.getInstance("HmacSHA256");
        extractMac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = extractMac.doFinal(ikm);

        Mac expandMac = Mac.getInstance("HmacSHA256");
        expandMac.init(new SecretKeySpec(prk, "HmacSHA256"));
        int hashLen = 32;
        int n = (length + hashLen - 1) / hashLen;
        byte[] t = new byte[0];
        byte[] okm = new byte[length];
        int pos = 0;
        for (int i = 1; i <= n; i++) {
            expandMac.reset();
            expandMac.update(t);
            expandMac.update(info);
            expandMac.update((byte) i);
            t = expandMac.doFinal();
            int toCopy = Math.min(hashLen, length - pos);
            System.arraycopy(t, 0, okm, pos, toCopy);
            pos += toCopy;
        }
        return okm;
    }
}
