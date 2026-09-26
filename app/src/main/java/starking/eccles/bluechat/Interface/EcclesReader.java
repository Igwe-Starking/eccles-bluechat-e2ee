package starking.eccles.bluechat.Interface;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamCorruptedException;
import java.security.GeneralSecurityException;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.crypto.EccSession;

public class EcclesReader extends DataInputStream {

    public final InputStream input;
    public static final int MAX_FIELD_SIZE = EcclesWriter.MAX_FRAME_SIZE;
    /**
     * Additional, much tighter bound applied only to the data field of TEXT-subtype messages
     * (see {@link #readMessage}). A chat text message has no legitimate reason to be anywhere
     * near the general {@link #MAX_FIELD_SIZE} - the wire format otherwise allows the same
     * data/voice/video fields for every message type/subtype combination with no semantic
     * schema at all, so without this a malicious peer could declare a TEXT message with a
     * near-MAX_FIELD_SIZE data field and still pass the general bound. 256 KiB is far more than
     * any real chat message, including pasted long text.
     */
    public static final int MAX_TEXT_FIELD_SIZE = 256 * 1024;
    private final EccSession session;

    public EcclesReader(InputStream in, EccSession session) {
        super(in);
        this.input = in;
        this.session = session;
    }

    public void clean() {}

    public static void skipAll(InputStream in) throws IOException {
        while (in.read() != -1) {}
    }

    private byte[] field(int len, long remaining) throws IOException {
        if (len < 0 || len > MAX_FIELD_SIZE || len > remaining) {
            throw new StreamCorruptedException("invalid Eccles field length: " + len);
        }
        byte[] b = new byte[len];
        readFully(b);
        return b;
    }

    public synchronized EcclesMessage readMessage() throws IOException {
        final int magic;
        try {
            magic = readInt();
        } catch (EOFException e) {
            return null;
        }
        if (magic != EcclesWriter.MAGIC) {
            throw new StreamCorruptedException("bad Eccles magic - peer is not running a compatible encrypted protocol version");
        }
        short version = readShort();
        if (version != EcclesWriter.VERSION) {
            throw new StreamCorruptedException("unsupported Eccles protocol v" + version);
        }
        readShort();
        int type = readInt(), subtype = readInt();

        int dl = readInt();
        // Type-aware bound: a TEXT message's data field gets the much tighter
        // MAX_TEXT_FIELD_SIZE cap instead of the general MAX_FIELD_SIZE, since real chat text
        // is never anywhere near that large - see the MAX_TEXT_FIELD_SIZE field doc.
        int dataCap = subtype == EcclesMessage.SUBTYPE_TEXT ? MAX_TEXT_FIELD_SIZE : MAX_FIELD_SIZE;
        byte[] encData = field(dl, dataCap);
        int vl = readInt();
        byte[] encVoice = field(vl, (long) MAX_FIELD_SIZE - dl);
        int xl = readInt();
        byte[] encVideo = field(xl, (long) MAX_FIELD_SIZE - dl - vl);

        try {
            byte[] header = headerAad(type, subtype);
            byte[] data = session.decrypt(encData, aad(header, 0));
            byte[] voice = session.decrypt(encVoice, aad(header, 1));
            byte[] video = session.decrypt(encVideo, aad(header, 2));
            return new EcclesMessage(type, subtype)
                    .setData(data.length == 0 ? null : data)
                    .setVoice(voice.length == 0 ? null : voice)
                    .setVideo(video.length == 0 ? null : video);
        } catch (GeneralSecurityException e) {
            throw new StreamCorruptedException("Eccles frame failed authentication - possible tampering: " + e.getMessage());
        }
    }

    private static byte[] headerAad(int type, int subtype) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeInt(EcclesWriter.MAGIC);
            d.writeShort(EcclesWriter.VERSION);
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
}
