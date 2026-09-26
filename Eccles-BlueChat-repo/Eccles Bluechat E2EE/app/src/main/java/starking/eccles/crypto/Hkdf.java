package starking.eccles.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hkdf {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(salt, HMAC_ALGO));
        return mac.doFinal(ikm);
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(prk, HMAC_ALGO));
        int n = (int) Math.ceil((double) length / HASH_LEN);
        if (n > 255) throw new GeneralSecurityException("HKDF requested output too large");
        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        int written = 0;
        for (int i = 1; i <= n; i++) {
            mac.reset();
            mac.update(previous);
            mac.update(info);
            mac.update((byte) i);
            previous = mac.doFinal();
            int toCopy = Math.min(HASH_LEN, length - written);
            System.arraycopy(previous, 0, out, written, toCopy);
            written += toCopy;
        }
        return out;
    }

    public static byte[] deriveKeys(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }
}
