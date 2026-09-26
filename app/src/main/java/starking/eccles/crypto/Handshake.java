package starking.eccles.crypto;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

public final class Handshake {

    public static final int MAGIC = 0x45434831;
    public static final short VERSION = 1;
    private static final int MAX_KEY_LEN = 512;
    private static final byte[] HKDF_SALT = new byte[]{
            (byte) 0x45, (byte) 0x63, (byte) 0x63, (byte) 0x6C, (byte) 0x65, (byte) 0x73,
            (byte) 0x42, (byte) 0x6C, (byte) 0x75, (byte) 0x65, (byte) 0x43, (byte) 0x68,
            (byte) 0x61, (byte) 0x74, (byte) 0x56, (byte) 0x33, (byte) 0x53, (byte) 0x65,
            (byte) 0x73, (byte) 0x73, (byte) 0x69, (byte) 0x6F, (byte) 0x6E, (byte) 0x53,
            (byte) 0x61, (byte) 0x6C, (byte) 0x74, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01
    };
    private static final byte[] HKDF_INFO = "EcclesBluechatV3SessionKeys".getBytes();

    private Handshake() {}

    public static EccSession perform(String remoteAddress, InputStream rawIn, OutputStream rawOut,
                                      PrivateKey myIdentityPriv, byte[] myIdentityPubEncoded)
            throws IOException, GeneralSecurityException {

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ephemeral = kpg.generateKeyPair();
        byte[] myEphPub = ephemeral.getPublic().getEncoded();

        DataOutputStream out = new DataOutputStream(rawOut);
        DataInputStream in = new DataInputStream(rawIn);

        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeShort(myIdentityPubEncoded.length);
        out.write(myIdentityPubEncoded);
        out.writeShort(myEphPub.length);
        out.write(myEphPub);
        out.flush();

        int magic = in.readInt();
        if (magic != MAGIC) throw new GeneralSecurityException("unexpected handshake magic, peer is not running a compatible secure version");
        short version = in.readShort();
        if (version != VERSION) throw new GeneralSecurityException("unsupported handshake version " + version);
        int idLen = in.readUnsignedShort();
        if (idLen <= 0 || idLen > MAX_KEY_LEN) throw new GeneralSecurityException("invalid peer identity key length");
        byte[] peerIdPubEnc = new byte[idLen];
        in.readFully(peerIdPubEnc);
        int ephLen = in.readUnsignedShort();
        if (ephLen <= 0 || ephLen > MAX_KEY_LEN) throw new GeneralSecurityException("invalid peer ephemeral key length");
        byte[] peerEphPubEnc = new byte[ephLen];
        in.readFully(peerEphPubEnc);

        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey peerIdPub = kf.generatePublic(new X509EncodedKeySpec(peerIdPubEnc));
        PublicKey peerEphPub = kf.generatePublic(new X509EncodedKeySpec(peerEphPubEnc));

        boolean isHigh = compare(myIdentityPubEncoded, peerIdPubEnc) > 0;

        byte[] term1, term2, term3;
        if (isHigh) {
            term1 = dh(myIdentityPriv, peerEphPub);
            term2 = dh(ephemeral.getPrivate(), peerIdPub);
        } else {
            term1 = dh(ephemeral.getPrivate(), peerIdPub);
            term2 = dh(myIdentityPriv, peerEphPub);
        }
        term3 = dh(ephemeral.getPrivate(), peerEphPub);

        byte[] ikm = new byte[term1.length + term2.length + term3.length];
        System.arraycopy(term1, 0, ikm, 0, term1.length);
        System.arraycopy(term2, 0, ikm, term1.length, term2.length);
        System.arraycopy(term3, 0, ikm, term1.length + term2.length, term3.length);
        Arrays.fill(term1, (byte) 0);
        Arrays.fill(term2, (byte) 0);
        Arrays.fill(term3, (byte) 0);

        byte[] okm = Hkdf.deriveKeys(HKDF_SALT, ikm, HKDF_INFO, 72);
        Arrays.fill(ikm, (byte) 0);

        byte[] keyHtoL = Arrays.copyOfRange(okm, 0, 32);
        byte[] saltHtoL = Arrays.copyOfRange(okm, 32, 36);
        byte[] keyLtoH = Arrays.copyOfRange(okm, 36, 68);
        byte[] saltLtoH = Arrays.copyOfRange(okm, 68, 72);
        Arrays.fill(okm, (byte) 0);

        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        byte[] peerFingerprint = sha256.digest(peerIdPubEnc);

        EccSession session;
        if (isHigh) {
            session = new EccSession(remoteAddress, peerFingerprint, keyHtoL, keyLtoH, saltHtoL, saltLtoH);
        } else {
            session = new EccSession(remoteAddress, peerFingerprint, keyLtoH, keyHtoL, saltLtoH, saltHtoL);
        }
        return session;
    }

    private static byte[] dh(PrivateKey priv, PublicKey pub) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(priv);
        ka.doPhase(pub, true);
        return ka.generateSecret();
    }

    private static int compare(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = a[i] & 0xFF, bv = b[i] & 0xFF;
            if (av != bv) return av - bv;
        }
        return a.length - b.length;
    }
}
