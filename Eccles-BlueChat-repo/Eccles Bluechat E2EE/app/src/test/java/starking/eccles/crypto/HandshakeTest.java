package starking.eccles.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end test of {@link Handshake#perform}: runs both sides of the handshake concurrently
 * over an in-memory piped stream pair (standing in for a Bluetooth RFCOMM socket) and verifies
 * that the two peers agree on usable, symmetric session keys, and that basic malformed-input
 * cases are rejected rather than silently accepted.
 */
public class HandshakeTest {

    private ExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void bothPeers_deriveInteroperableSessions() throws Exception {
        KeyPair aliceIdentity = generateEcKeyPair();
        KeyPair bobIdentity = generateEcKeyPair();

        PipedOutputStream aliceOut = new PipedOutputStream();
        PipedInputStream bobIn = new PipedInputStream(aliceOut, 4096);
        PipedOutputStream bobOut = new PipedOutputStream();
        PipedInputStream aliceIn = new PipedInputStream(bobOut, 4096);

        Callable<EccSession> aliceTask = () -> Handshake.perform(
                "bob-address", aliceIn, aliceOut, aliceIdentity.getPrivate(), aliceIdentity.getPublic().getEncoded());
        Callable<EccSession> bobTask = () -> Handshake.perform(
                "alice-address", bobIn, bobOut, bobIdentity.getPrivate(), bobIdentity.getPublic().getEncoded());

        Future<EccSession> aliceFuture = executor.submit(aliceTask);
        Future<EccSession> bobFuture = executor.submit(bobTask);

        EccSession aliceSession = aliceFuture.get(10, TimeUnit.SECONDS);
        EccSession bobSession = bobFuture.get(10, TimeUnit.SECONDS);

        // Each side's session must be able to talk to the other's.
        byte[] fromAlice = aliceSession.encrypt("hi bob".getBytes(), null);
        assertArrayEquals("hi bob".getBytes(), bobSession.decrypt(fromAlice, null));

        byte[] fromBob = bobSession.encrypt("hi alice".getBytes(), null);
        assertArrayEquals("hi alice".getBytes(), aliceSession.decrypt(fromBob, null));

        // Each side must record the correct fingerprint for the *other* party's identity key.
        assertArrayEquals(sha256(bobIdentity.getPublic().getEncoded()), aliceSession.remoteFingerprint);
        assertArrayEquals(sha256(aliceIdentity.getPublic().getEncoded()), bobSession.remoteFingerprint);
    }

    @Test
    public void differentKeyPairsProduceDifferentSessionKeys() throws Exception {
        // Sanity check that the handshake isn't accidentally deriving a fixed/static key: two
        // independent runs with fresh identities and ephemeral keys must not produce a session
        // whose traffic is interchangeable.
        KeyPair aliceIdentity = generateEcKeyPair();
        KeyPair bobIdentity = generateEcKeyPair();
        EccSession[] run1 = performHandshakePair(aliceIdentity, bobIdentity);

        KeyPair aliceIdentity2 = generateEcKeyPair();
        KeyPair bobIdentity2 = generateEcKeyPair();
        EccSession[] run2 = performHandshakePair(aliceIdentity2, bobIdentity2);

        byte[] ct1 = run1[0].encrypt("same plaintext".getBytes(), null);
        // run2's bob session must not be able to decrypt run1's alice ciphertext.
        assertThrows(GeneralSecurityException.class, () -> run2[1].decrypt(ct1, null));
    }

    @Test
    public void rejectsBadMagic() throws Exception {
        PipedOutputStream fakePeerOut = new PipedOutputStream();
        PipedInputStream myIn = new PipedInputStream(fakePeerOut, 4096);
        PipedOutputStream myOut = new PipedOutputStream();
        PipedInputStream fakePeerIn = new PipedInputStream(myOut, 4096);

        KeyPair myIdentity = generateEcKeyPair();

        // A "peer" that just writes garbage instead of speaking the handshake protocol.
        Future<?> fakePeer = executor.submit(() -> {
            try {
                // drain whatever the real side sends so its write doesn't block
                byte[] sink = new byte[4096];
                fakePeerIn.read(sink);
                DataOutputStream d = new DataOutputStream(fakePeerOut);
                d.writeInt(0xDEADBEEF); // wrong magic
                d.flush();
            } catch (IOException ignored) {
            }
            return null;
        });

        assertThrows("a handshake with the wrong magic must be rejected, not silently accepted",
                GeneralSecurityException.class,
                () -> Handshake.perform("peer", myIn, myOut, myIdentity.getPrivate(), myIdentity.getPublic().getEncoded()));

        fakePeer.get(5, TimeUnit.SECONDS);
    }

    private EccSession[] performHandshakePair(KeyPair aliceIdentity, KeyPair bobIdentity) throws Exception {
        PipedOutputStream aliceOut = new PipedOutputStream();
        PipedInputStream bobIn = new PipedInputStream(aliceOut, 4096);
        PipedOutputStream bobOut = new PipedOutputStream();
        PipedInputStream aliceIn = new PipedInputStream(bobOut, 4096);

        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<EccSession> a = ex.submit(() -> Handshake.perform("bob", aliceIn, aliceOut,
                    aliceIdentity.getPrivate(), aliceIdentity.getPublic().getEncoded()));
            Future<EccSession> b = ex.submit(() -> Handshake.perform("alice", bobIn, bobOut,
                    bobIdentity.getPrivate(), bobIdentity.getPublic().getEncoded()));
            return new EccSession[]{a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)};
        } finally {
            ex.shutdownNow();
        }
    }

    private static KeyPair generateEcKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
