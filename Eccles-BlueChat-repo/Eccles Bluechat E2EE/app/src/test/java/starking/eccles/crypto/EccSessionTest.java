package starking.eccles.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link EccSession} in isolation from the handshake: two sessions are built directly
 * from matching key/salt material (as {@link Handshake} would produce for two peers), which
 * lets these tests focus purely on the AEAD framing, nonce construction, and replay/ordering
 * protection without needing a live socket pair.
 */
public class EccSessionTest {

    private EccSession alice;
    private EccSession bob;

    @Before
    public void setUp() {
        byte[] keyAtoB = randomBytes(32);
        byte[] keyBtoA = randomBytes(32);
        byte[] saltAtoB = randomBytes(4);
        byte[] saltBtoA = randomBytes(4);
        byte[] fingerprintA = randomBytes(32);
        byte[] fingerprintB = randomBytes(32);

        // alice sends with (keyAtoB, saltAtoB) and receives with (keyBtoA, saltBtoA);
        // bob is the mirror image, so alice.send == bob.recv and bob.send == alice.recv.
        alice = new EccSession("bob-address", fingerprintB, keyAtoB, keyBtoA, saltAtoB, saltBtoA);
        bob = new EccSession("alice-address", fingerprintA, keyBtoA, keyAtoB, saltBtoA, saltAtoB);
    }

    @Test
    public void roundTrip_aliceToBob() throws Exception {
        byte[] aad = "header".getBytes();
        byte[] plaintext = "hello bob, this is a secret".getBytes();

        byte[] framed = alice.encrypt(plaintext, aad);
        byte[] decrypted = bob.decrypt(framed, aad);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void roundTrip_bobToAlice() throws Exception {
        byte[] aad = "header".getBytes();
        byte[] plaintext = "hello alice".getBytes();

        byte[] framed = bob.encrypt(plaintext, aad);
        byte[] decrypted = alice.decrypt(framed, aad);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void roundTrip_handlesNullAndEmptyPlaintext() throws Exception {
        byte[] framedNull = alice.encrypt(null, null);
        assertArrayEquals(new byte[0], bob.decrypt(framedNull, null));

        byte[] framedEmpty = alice.encrypt(new byte[0], null);
        assertArrayEquals(new byte[0], bob.decrypt(framedEmpty, null));
    }

    @Test
    public void multipleMessages_eachUsesADistinctNonce() throws Exception {
        // Reusing a GCM (key, nonce) pair is a catastrophic AEAD failure (it breaks both
        // confidentiality and integrity), so consecutive frames from the same session must
        // never be identical even for identical plaintext.
        byte[] plaintext = "same message twice".getBytes();
        byte[] first = alice.encrypt(plaintext, null);
        byte[] second = alice.encrypt(plaintext, null);

        assertFalse("two ciphertexts for the same plaintext must differ (distinct nonces)",
                Arrays.equals(first, second));

        assertArrayEquals(plaintext, bob.decrypt(first, null));
        assertArrayEquals(plaintext, bob.decrypt(second, null));
    }

    @Test
    public void decrypt_rejectsReplayedFrame() throws Exception {
        byte[] framed = alice.encrypt("only once".getBytes(), null);
        assertArrayEquals("only once".getBytes(), bob.decrypt(framed, null));

        assertThrows("replaying an already-accepted frame must be rejected",
                GeneralSecurityException.class, () -> bob.decrypt(framed, null));
    }

    @Test
    public void decrypt_rejectsOutOfOrderOldFrame() throws Exception {
        byte[] first = alice.encrypt("first".getBytes(), null);
        byte[] second = alice.encrypt("second".getBytes(), null);

        assertArrayEquals("second".getBytes(), bob.decrypt(second, null));
        // 'first' has a lower counter than the highest one bob has already accepted.
        assertThrows(GeneralSecurityException.class, () -> bob.decrypt(first, null));
    }

    @Test
    public void decrypt_acceptsGapInCounters() throws Exception {
        // A dropped Bluetooth frame should not permanently break the session: any strictly
        // increasing counter is accepted, not just the immediate next one.
        byte[] first = alice.encrypt("first".getBytes(), null);
        byte[] second = alice.encrypt("second".getBytes(), null);
        byte[] third = alice.encrypt("third".getBytes(), null);

        assertArrayEquals("first".getBytes(), bob.decrypt(first, null));
        // 'second' is dropped / never delivered.
        assertArrayEquals("third".getBytes(), bob.decrypt(third, null));
    }

    @Test
    public void decrypt_rejectsTamperedCiphertext() throws Exception {
        byte[] framed = alice.encrypt("integrity matters".getBytes(), null);
        byte[] tampered = framed.clone();
        tampered[tampered.length - 1] ^= 0x01; // flip a bit inside the GCM tag/ciphertext

        assertThrows(GeneralSecurityException.class, () -> bob.decrypt(tampered, null));
    }

    @Test
    public void decrypt_rejectsMismatchedAad() throws Exception {
        byte[] framed = alice.encrypt("bound to header".getBytes(), "header-v1".getBytes());
        assertThrows("AAD is authenticated; decrypting with a different AAD must fail",
                GeneralSecurityException.class, () -> bob.decrypt(framed, "header-v2".getBytes()));
    }

    @Test
    public void decrypt_rejectsTruncatedFrame() {
        assertThrows(GeneralSecurityException.class, () -> bob.decrypt(new byte[]{1, 2, 3}, null));
    }

    @Test
    public void decrypt_rejectsNullFrame() {
        assertThrows(GeneralSecurityException.class, () -> bob.decrypt(null, null));
    }

    @Test
    public void wrongSessionCannotDecrypt() throws Exception {
        // A third party session (fresh, unrelated key material) must not be able to decrypt
        // traffic between alice and bob even though it uses the same framing/algorithm.
        EccSession eve = new EccSession("eve-address", randomBytes(32), randomBytes(32), randomBytes(32),
                randomBytes(4), randomBytes(4));
        byte[] framed = alice.encrypt("secret".getBytes(), null);
        assertThrows(GeneralSecurityException.class, () -> eve.decrypt(framed, null));
    }

    @Test
    public void manyMessages_allRoundTripInOrder() throws Exception {
        for (int i = 0; i < 500; i++) {
            byte[] plaintext = ("message-" + i).getBytes();
            byte[] framed = alice.encrypt(plaintext, null);
            assertArrayEquals(plaintext, bob.decrypt(framed, null));
        }
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
