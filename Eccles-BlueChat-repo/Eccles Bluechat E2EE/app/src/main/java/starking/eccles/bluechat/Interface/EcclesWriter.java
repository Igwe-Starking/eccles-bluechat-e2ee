package starking.eccles.bluechat.Interface;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.crypto.EccSession;

public class EcclesWriter extends DataOutputStream {

    public static final int MAGIC = 0x45434C53;
    public static final short VERSION = 3;
    /**
     * Reduced from the previous 64 MiB. That limit let a compromised or malicious peer declare
     * an attacker-controlled field length up to 64 MiB and force this app to allocate that much
     * memory BEFORE the frame's GCM authentication tag is even checked - repeated rapidly, a
     * real memory-exhaustion / OOM surface. 20 MiB is still generous headroom over any real
     * attachment this app sends (the in-app UI itself already warns above just 3 MB, with
     * "Always Send" as the only way to exceed it) while meaningfully shrinking the worst-case
     * allocation. See also {@link EcclesReader#MAX_TEXT_FIELD_SIZE} for an additional, much
     * tighter bound specific to text messages.
     */
    public static final int MAX_FRAME_SIZE = 20 * 1024 * 1024;
    public final OutputStream output;
    private final EccSession session;

    public EcclesWriter(OutputStream out, EccSession session) throws IOException {
        super(out);
        this.output = out;
        this.session = session;
    }

    public synchronized boolean writeMessage(EcclesMessage m) throws IOException {
        if (m == null) throw new IOException("null EcclesMessage");
        int dl = m.data == null ? 0 : m.data.length;
        int vl = m.voice == null ? 0 : m.voice.length;
        int xl = m.video == null ? 0 : m.video.length;
        long payload = (long) dl + vl + xl;
        if (dl > MAX_FRAME_SIZE || vl > MAX_FRAME_SIZE || xl > MAX_FRAME_SIZE || payload > MAX_FRAME_SIZE) {
            throw new IOException("Eccles frame exceeds 64 MiB limit");
        }
        try {
            byte[] header = headerAad(m.type, m.subtype);
            byte[] encData = session.encrypt(m.data, aad(header, 0));
            byte[] encVoice = session.encrypt(m.voice, aad(header, 1));
            byte[] encVideo = session.encrypt(m.video, aad(header, 2));

            writeInt(MAGIC);
            writeShort(VERSION);
            writeShort(0);
            writeInt(m.type);
            writeInt(m.subtype);
            writeInt(encData.length);
            write(encData);
            writeInt(encVoice.length);
            write(encVoice);
            writeInt(encVideo.length);
            write(encVideo);
            flush();
            return true;
        } catch (GeneralSecurityException e) {
            throw new IOException("failed to encrypt Eccles frame", e);
        }
    }

    private static byte[] headerAad(int type, int subtype) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeInt(MAGIC);
            d.writeShort(VERSION);
            d.writeInt(type);
            d.writeInt(subtype);
        } catch (IOException ignored) {}
        return b.toByteArray();
    }

    private static byte[] aad(byte[] header, int fieldIndex) {
        byte[] out = new byte[header.length + 1];
        System.arraycopy(header, 0, out, 0, header.length);
        out[header.length] = (byte) fieldIndex;
        return out;
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        output.flush();
    }
}
