package starking.eccles.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class KeystoreAes {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final String alias;

    public KeystoreAes(String alias) {
        this.alias = alias;
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        try {
            ks.load(null);
        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
        if (!ks.containsAlias(alias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            kg.init(spec);
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(alias, null);
    }

    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return out;
    }

    public byte[] decrypt(byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < 1) throw new GeneralSecurityException("empty ciphertext");
        int ivLen = blob[0] & 0xFF;
        if (ivLen <= 0 || ivLen > 32 || blob.length < 1 + ivLen) throw new GeneralSecurityException("malformed ciphertext");
        byte[] iv = Arrays.copyOfRange(blob, 1, 1 + ivLen);
        byte[] ct = Arrays.copyOfRange(blob, 1 + ivLen, blob.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ct);
    }

    public boolean keyExists() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        try {
            ks.load(null);
        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
        return ks.containsAlias(alias);
    }
}
