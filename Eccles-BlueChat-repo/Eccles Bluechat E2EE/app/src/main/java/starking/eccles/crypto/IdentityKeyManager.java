package starking.eccles.crypto;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public final class IdentityKeyManager {

    private static final String CURVE = "secp256r1";
    private static final String KEYSTORE_ALIAS = "eccles_identity_wrap_key";
    private static final String PRIV_FILE = "identity_priv.enc";
    private static final String PUB_FILE = "identity_pub.der";

    private static volatile IdentityKeyManager instance;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final byte[] publicKeyEncoded;
    private final byte[] fingerprint;

    private IdentityKeyManager(PrivateKey priv, PublicKey pub) throws GeneralSecurityException {
        this.privateKey = priv;
        this.publicKey = pub;
        this.publicKeyEncoded = pub.getEncoded();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        this.fingerprint = sha256.digest(publicKeyEncoded);
    }

    public static synchronized IdentityKeyManager get(Context context) throws GeneralSecurityException {
        if (instance != null) return instance;
        Context app = context.getApplicationContext();
        File dir = app.getNoBackupFilesDir();
        File privFile = new File(dir, PRIV_FILE);
        File pubFile = new File(dir, PUB_FILE);
        KeystoreAes wrapper = new KeystoreAes(KEYSTORE_ALIAS);
        try {
            if (privFile.exists() && pubFile.exists()) {
                byte[] wrapped = readFile(privFile);
                byte[] pubEncoded = readFile(pubFile);
                byte[] pkcs8 = wrapper.decrypt(wrapped);
                KeyFactory kf = KeyFactory.getInstance("EC");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubEncoded));
                instance = new IdentityKeyManager(priv, pub);
                return instance;
            }
        } catch (Exception e) {
            throw new GeneralSecurityException("failed to load identity key", e);
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(CURVE));
        KeyPair pair = kpg.generateKeyPair();
        byte[] wrapped = wrapper.encrypt(pair.getPrivate().getEncoded());
        byte[] pubEncoded = pair.getPublic().getEncoded();
        writeFileAtomic(privFile, wrapped);
        writeFileAtomic(pubFile, pubEncoded);
        instance = new IdentityKeyManager(pair.getPrivate(), pair.getPublic());
        return instance;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public byte[] getPublicKeyEncoded() {
        return publicKeyEncoded.clone();
    }

    public byte[] getFingerprint() {
        return fingerprint.clone();
    }

    public String getFingerprintHex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fingerprint.length; i++) {
            sb.append(String.format("%02X", fingerprint[i]));
            if (i < fingerprint.length - 1 && i % 2 == 1) sb.append(' ');
        }
        return sb.toString();
    }

    private static byte[] readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) >= 0) off += r;
            return buf;
        }
    }

    private static void writeFileAtomic(File dest, byte[] data) throws GeneralSecurityException {
        try {
            File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            if (!tmp.renameTo(dest)) throw new GeneralSecurityException("failed to persist identity key file");
        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
    }
}
