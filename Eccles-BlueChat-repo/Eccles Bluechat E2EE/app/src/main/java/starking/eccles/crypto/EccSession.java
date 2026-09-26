package starking.eccles.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class EccSession {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    public final String remoteAddress;
    public final byte[] remoteFingerprint;

    private final SecretKeySpec sendKey;
    private final SecretKeySpec recvKey;
    private final byte[] sendSalt;
    private final byte[] recvSalt;
    private final AtomicLong sendCounter = new AtomicLong(0);
    private long lastRecvCounter = -1;

    EccSession(String remoteAddress, byte[] remoteFingerprint, byte[] sendKeyBytes, byte[] recvKeyBytes,
               byte[] sendSalt, byte[] recvSalt) {
        this.remoteAddress = remoteAddress;
        this.remoteFingerprint = remoteFingerprint;
        this.sendKey = new SecretKeySpec(sendKeyBytes, "AES");
        this.recvKey = new SecretKeySpec(recvKeyBytes, "AES");
        this.sendSalt = sendSalt;
        this.recvSalt = recvSalt;
    }

    private static byte[] nonce(byte[] salt, long counter) {
        byte[] n = new byte[NONCE_BYTES];
        System.arraycopy(salt, 0, n, 0, 4);
        for (int i = 0; i < 8; i++) {
            n[4 + i] = (byte) (counter >>> (8 * (7 - i)));
        }
        return n;
    }

    public synchronized byte[] encrypt(byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        long counter = sendCounter.getAndIncrement();
        byte[] iv = nonce(sendSalt, counter);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] ct = cipher.doFinal(plaintext == null ? new byte[0] : plaintext);
        byte[] out = new byte[8 + ct.length];
        for (int i = 0; i < 8; i++) out[i] = (byte) (counter >>> (8 * (7 - i)));
        System.arraycopy(ct, 0, out, 8, ct.length);
        return out;
    }

    public synchronized byte[] decrypt(byte[] framed, byte[] aad) throws GeneralSecurityException {
        if (framed == null || framed.length < 8) throw new GeneralSecurityException("truncated ciphertext");
        long counter = 0;
        for (int i = 0; i < 8; i++) counter = (counter << 8) | (framed[i] & 0xFF);
        if (counter <= lastRecvCounter) throw new GeneralSecurityException("replayed or out-of-order frame rejected");
        byte[] iv = nonce(recvSalt, counter);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] ct = Arrays.copyOfRange(framed, 8, framed.length);
        byte[] pt = cipher.doFinal(ct);
        lastRecvCounter = counter;
        return pt;
    }
}
