package starking.eccles.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.security.SecureRandom;
import org.junit.Before;
import org.junit.Test;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.Interface.EcclesReader;
import starking.eccles.bluechat.Interface.EcclesWriter;

/**
 * Tests the length-prefixed, AEAD-authenticated wire framing that {@link EcclesWriter} and
 * {@link EcclesReader} use to move an {@link EcclesMessage} across an already-established
 * {@link EccSession}. This file lives alongside the other crypto tests (rather than under
 * {@code starking.eccles.bluechat.Interface}) purely so it can use {@link EccSession}'s
 * package-private constructor to build a matching session pair directly, the same way
 * {@link EccSessionTest} does.
 */
public class EcclesWriterReaderTest {

    private EccSession alice;
    private EccSession bob;

    @Before
    public void setUp() {
        byte[] keyAtoB = randomBytes(32);
        byte[] keyBtoA = randomBytes(32);
        byte[] saltAtoB = randomBytes(4);
        byte[] saltBtoA = randomBytes(4);
        alice = new EccSession("bob", randomBytes(32), keyAtoB, keyBtoA, saltAtoB, saltBtoA);
        bob = new EccSession("alice", randomBytes(32), keyBtoA, keyAtoB, saltBtoA, saltAtoB);
    }

    @Test
    public void roundTrip_textMessage() throws Exception {
        EcclesMessage sent = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setData("hello, this is encrypted end to end".getBytes());

        EcclesMessage received = writeThenRead(sent);

        assertArrayEquals(sent.data, received.data);
        assertNull(received.voice);
        assertNull(received.video);
        org.junit.Assert.assertEquals(sent.type, received.type);
        org.junit.Assert.assertEquals(sent.subtype, received.subtype);
    }

    @Test
    public void roundTrip_allThreePayloadFields() throws Exception {
        EcclesMessage sent = new EcclesMessage(EcclesMessage.TYPE_CALL, EcclesMessage.SUBTYPE_CALL_CONFIG)
                .setData("d".getBytes())
                .setVoice(randomBytes(256))
                .setVideo(randomBytes(1024));

        EcclesMessage received = writeThenRead(sent);

        assertArrayEquals(sent.data, received.data);
        assertArrayEquals(sent.voice, received.voice);
        assertArrayEquals(sent.video, received.video);
    }

    @Test
    public void roundTrip_emptyMessage() throws Exception {
        EcclesMessage sent = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT);
        EcclesMessage received = writeThenRead(sent);
        assertNull(received.data);
        assertNull(received.voice);
        assertNull(received.video);
    }

    @Test
    public void tamperedWireBytes_areRejectedAsCorrupted() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new EcclesWriter(wire, alice).writeMessage(
                new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT).setData("secret".getBytes()));

        byte[] bytes = wire.toByteArray();
        bytes[bytes.length - 1] ^= 0x01; // corrupt the last byte of the encrypted data field

        EcclesReader reader = new EcclesReader(new ByteArrayInputStream(bytes), bob);
        assertThrows("a corrupted/tampered frame must fail authentication, not decrypt to garbage silently",
                StreamCorruptedException.class, reader::readMessage);
    }

    @Test
    public void wrongSession_cannotDecryptFrame() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new EcclesWriter(wire, alice).writeMessage(
                new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT).setData("secret".getBytes()));

        EccSession eve = new EccSession("eve", randomBytes(32), randomBytes(32), randomBytes(32), randomBytes(4), randomBytes(4));
        EcclesReader reader = new EcclesReader(new ByteArrayInputStream(wire.toByteArray()), eve);
        assertThrows(StreamCorruptedException.class, reader::readMessage);
    }

    @Test
    public void emptyStream_readMessageReturnsNullInsteadOfThrowing() throws Exception {
        EcclesReader reader = new EcclesReader(new ByteArrayInputStream(new byte[0]), bob);
        assertNull("a cleanly closed/empty stream should signal end-of-messages, not an error", reader.readMessage());
    }

    @Test
    public void oversizedDeclaredFieldLength_isRejected() {
        // A malicious/corrupt peer claiming a huge field length must be rejected before any
        // large allocation happens, rather than the reader trying to allocate e.g. 2^31 bytes.
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(wire);
        assertThrows(Exception.class, () -> {
            d.writeInt(EcclesWriter.MAGIC);
            d.writeShort(EcclesWriter.VERSION);
            d.writeShort(0);
            d.writeInt(EcclesMessage.TYPE_CHAT);
            d.writeInt(EcclesMessage.SUBTYPE_TEXT);
            d.writeInt(Integer.MAX_VALUE); // declared data length far larger than anything sent
            d.flush();
            new EcclesReader(new ByteArrayInputStream(wire.toByteArray()), bob).readMessage();
        });
    }

    private EcclesMessage writeThenRead(EcclesMessage m) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        boolean wrote = new EcclesWriter(wire, alice).writeMessage(m);
        org.junit.Assert.assertTrue(wrote);
        EcclesReader reader = new EcclesReader(new ByteArrayInputStream(wire.toByteArray()), bob);
        return reader.readMessage();
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
