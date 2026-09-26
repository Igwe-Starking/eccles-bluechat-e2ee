package starking.eccles.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs against the real Android Keystore, which cannot be faithfully emulated on the local JVM.
 * Each test uses a fresh, randomly-named key alias so runs don't interfere with each other or
 * leave long-lived keys behind in the device's keystore across test runs.
 */
@RunWith(AndroidJUnit4.class)
public class KeystoreAesInstrumentedTest {

    @Test
    public void encryptThenDecrypt_roundTrips() throws Exception {
        KeystoreAes aes = new KeystoreAes(freshAlias());
        byte[] plaintext = "the quick brown fox jumps over the lazy dog".getBytes();

        byte[] blob = aes.encrypt(plaintext);
        byte[] decrypted = aes.decrypt(blob);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encrypt_ofEmptyArray_roundTrips() throws Exception {
        KeystoreAes aes = new KeystoreAes(freshAlias());
        byte[] decrypted = aes.decrypt(aes.encrypt(new byte[0]));
        assertEquals(0, decrypted.length);
    }

    @Test
    public void repeatedEncryption_producesDistinctCiphertextEachTime() throws Exception {
        // A fresh random IV per call is required for AES-GCM safety; reusing (key, IV) breaks
        // both confidentiality and authenticity guarantees.
        KeystoreAes aes = new KeystoreAes(freshAlias());
        byte[] plaintext = "same plaintext".getBytes();

        byte[] first = aes.encrypt(plaintext);
        byte[] second = aes.encrypt(plaintext);

        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(plaintext, aes.decrypt(first));
        assertArrayEquals(plaintext, aes.decrypt(second));
    }

    @Test
    public void decrypt_rejectsTamperedCiphertext() throws Exception {
        KeystoreAes aes = new KeystoreAes(freshAlias());
        byte[] blob = aes.encrypt("integrity-protected data".getBytes());
        blob[blob.length - 1] ^= 0x01;

        assertThrows(GeneralSecurityException.class, () -> aes.decrypt(blob));
    }

    @Test
    public void decrypt_rejectsTruncatedBlob() throws Exception {
        KeystoreAes aes = new KeystoreAes(freshAlias());
        byte[] blob = aes.encrypt("some data".getBytes());
        byte[] truncated = Arrays.copyOf(blob, blob.length - 5);

        assertThrows(GeneralSecurityException.class, () -> aes.decrypt(truncated));
    }

    @Test
    public void decrypt_rejectsNullOrEmptyBlob() {
        KeystoreAes aes = new KeystoreAes(freshAlias());
        assertThrows(GeneralSecurityException.class, () -> aes.decrypt(null));
        assertThrows(GeneralSecurityException.class, () -> aes.decrypt(new byte[0]));
    }

    @Test
    public void differentAliases_areIndependentKeys() throws Exception {
        KeystoreAes a = new KeystoreAes(freshAlias());
        KeystoreAes b = new KeystoreAes(freshAlias());
        byte[] blob = a.encrypt("only readable by alias a".getBytes());

        assertThrows("a different key alias must not be able to decrypt another alias's data",
                GeneralSecurityException.class, () -> b.decrypt(blob));
    }

    @Test
    public void sameAlias_reusesTheSameUnderlyingKeyAcrossInstances() throws Exception {
        String alias = freshAlias();
        KeystoreAes writer = new KeystoreAes(alias);
        byte[] blob = writer.encrypt("persisted under one alias".getBytes());

        // A second KeystoreAes instance constructed with the same alias must be able to decrypt
        // data written by the first (this is how ChatBase reopens/reuses its at-rest key across
        // app restarts).
        KeystoreAes reader = new KeystoreAes(alias);
        assertArrayEquals("persisted under one alias".getBytes(), reader.decrypt(blob));
    }

    @Test
    public void keyExists_reflectsWhetherAliasHasBeenUsed() throws Exception {
        KeystoreAes aes = new KeystoreAes(freshAlias());
        assertFalse(aes.keyExists());
        aes.encrypt("trigger key creation".getBytes());
        assertTrue(aes.keyExists());
    }

    @Test
    public void concurrentEncryptDecrypt_onSameAlias_doesNotCorruptData() throws Exception {
        // Guards against accidental shared mutable state (e.g. a non-thread-safe Cipher field)
        // being introduced later, since ChatBase's single KeystoreAes instance is used from
        // multiple threads (UI thread inserts, background readAll() executor reads).
        String alias = freshAlias();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String msg = "thread-message-" + i;
                tasks.add(() -> {
                    KeystoreAes aes = new KeystoreAes(alias);
                    byte[] blob = aes.encrypt(msg.getBytes());
                    return Arrays.equals(msg.getBytes(), aes.decrypt(blob));
                });
            }
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                assertTrue(f.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String freshAlias() {
        return "test_alias_" + UUID.randomUUID();
    }
}
