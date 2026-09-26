package starking.eccles.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.crypto.KeystoreAes;

/**
 * Runs on a real device/emulator because it exercises real SQLite (not an in-memory fake) and
 * real Android Keystore-backed at-rest encryption, neither of which Robolectric simulates with
 * full fidelity for this app's purposes. Requires {@code ./gradlew connectedAndroidTest} (or
 * running the "Android Instrumented Tests" configuration in Android Studio) against a
 * connected device or emulator.
 */
@RunWith(AndroidJUnit4.class)
public class ChatBaseInstrumentedTest {

    private ChatBase chatBase;
    // Every test uses a fresh random conversation key so tests can run in any order/in parallel
    // against the single shared on-device database file without clobbering each other, and so
    // a failed test doesn't leave rows behind that make a later run's assertions wrong.
    private String conversation;

    @Before
    public void setUp() {
        EcclesApplication app = ApplicationProvider.getApplicationContext();
        chatBase = new ChatBase(app);
        conversation = "AA:BB:CC:DD:EE:" + UUID.randomUUID().toString().substring(0, 2).toUpperCase();
    }

    @After
    public void tearDown() {
        if (chatBase != null && conversation != null) {
            chatBase.clear(conversation);
        }
    }

    @Test
    public void delete_nonMatchingIdOrConversation_returnsFalse() {
        // Regression test: SQLiteDatabase.delete() returns the affected-row count (0 if
        // nothing matched), never -1. delete() used to check "!=-1", which is always true for
        // that API, so it always reported success even when no row was actually removed. It
        // now checks ">0".
        EcclesMessage m = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData("x".getBytes()).setDate("d");
        chatBase.insert(conversation, "sent", m);

        assertFalse("deleting a ID that doesn't exist in this conversation must report failure",
                chatBase.delete(conversation, 999999));
        assertFalse("deleting from a conversation that doesn't exist must report failure",
                chatBase.delete("no-such-conversation-" + UUID.randomUUID(), 1));
    }

    @Test
    public void updateStatus_nonMatchingRow_returnsFalse() {
        // Same regression as delete_nonMatchingIdOrConversation_returnsFalse, for update().
        assertFalse(chatBase.updateStatus(conversation, "delivered", 999999));
    }

    @Test
    public void insert_thenReadBackViaSql_roundTripsPlaintext() {
        EcclesMessage m = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation)
                .setData("hello from an instrumented test".getBytes(StandardCharsets.UTF_8))
                .setDate("some-date-token");

        assertTrue(chatBase.insert(conversation, "sent", m));

        try (Cursor c = chatBase.getWritableDatabase().query("messages", null,
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("sent", c.getString(c.getColumnIndexOrThrow(ChatBase.COLUMN_STATUS)));
            assertEquals(EcclesMessage.SUBTYPE_TEXT, c.getInt(c.getColumnIndexOrThrow(ChatBase.COLUMN_TYPE)));
        }
    }

    @Test
    public void insertedMessageData_isEncryptedAtRest_notStoredAsPlaintext() throws Exception {
        byte[] plaintext = "this must not be readable directly from the DB file".getBytes(StandardCharsets.UTF_8);
        EcclesMessage m = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData(plaintext).setDate("d");
        assertTrue(chatBase.insert(conversation, "sent", m));

        byte[] rawStoredBytes;
        try (Cursor c = chatBase.getWritableDatabase().query("messages", new String[]{ChatBase.COLUMN_DATA},
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            assertTrue(c.moveToFirst());
            rawStoredBytes = c.getBlob(0);
        }

        assertNotNull(rawStoredBytes);
        assertFalse("plaintext must not appear verbatim in the stored blob",
                containsSubsequence(rawStoredBytes, plaintext));

        // Independently decrypt using the same Keystore alias ChatBase uses, to confirm the
        // stored blob really is this plaintext encrypted (and not, say, corrupted garbage).
        KeystoreAes independentDecryptor = new KeystoreAes("eccles_local_storage_key");
        byte[] decrypted = independentDecryptor.decrypt(rawStoredBytes);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void insert_withNullData_storesNullDataColumn() {
        EcclesMessage m = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setDate("d");
        assertTrue(chatBase.insert(conversation, "sent", m));

        try (Cursor c = chatBase.getWritableDatabase().query("messages", new String[]{ChatBase.COLUMN_DATA},
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            assertTrue(c.moveToFirst());
            assertTrue(c.isNull(0));
        }
    }

    @Test
    public void insert_nullMessage_returnsFalseAndInsertsNothing() {
        assertFalse(chatBase.insert(conversation, "sent", null));
        try (Cursor c = chatBase.getWritableDatabase().query("messages", null,
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void delete_removesOnlyMatchingRow() {
        EcclesMessage m1 = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData("one".getBytes()).setDate("d1");
        EcclesMessage m2 = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData("two".getBytes()).setDate("d2");
        chatBase.insert(conversation, "sent", m1);
        chatBase.insert(conversation, "sent", m2);

        int idToDelete;
        try (Cursor c = chatBase.getWritableDatabase().query("messages", new String[]{ChatBase.COLUMN_ID},
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, ChatBase.COLUMN_ID + " ASC", null)) {
            assertEquals(2, c.getCount());
            c.moveToFirst();
            idToDelete = c.getInt(0);
        }

        assertTrue(chatBase.delete(conversation, idToDelete));

        try (Cursor c = chatBase.getWritableDatabase().query("messages", null,
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            assertEquals(1, c.getCount());
        }
    }

    @Test
    public void clear_removesAllRowsForConversation() {
        for (int i = 0; i < 3; i++) {
            chatBase.insert(conversation, "sent", new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                    .setSender(conversation).setData(("m" + i).getBytes()).setDate("d"));
        }
        chatBase.clear(conversation);
        try (Cursor c = chatBase.getWritableDatabase().query("messages", null,
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void updateStatus_changesOnlyTheStatusColumn() {
        EcclesMessage m = new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData("x".getBytes()).setDate("d");
        chatBase.insert(conversation, "sending", m);

        int id;
        try (Cursor c = chatBase.getWritableDatabase().query("messages", new String[]{ChatBase.COLUMN_ID},
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            c.moveToFirst();
            id = c.getInt(0);
        }

        assertTrue(chatBase.updateStatus(conversation, "delivered", id));

        try (Cursor c = chatBase.getWritableDatabase().query("messages", new String[]{ChatBase.COLUMN_STATUS},
                ChatBase.COLUMN_CONVERSATION + "=?", new String[]{conversation}, null, null, null)) {
            c.moveToFirst();
            assertEquals("delivered", c.getString(0));
        }
    }

    @Test
    public void getFiles_returnsOnlyNonTextAttachmentPaths() {
        chatBase.insert(conversation, "sent", new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData("just text, not a file path".getBytes()).setDate("d"));
        chatBase.insert(conversation, "sent", new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_IMAGE)
                .setSender(conversation).setData("/data/user/0/app/files/photo.jpg".getBytes()).setDate("d"));

        String[] files = chatBase.getFiles(conversation);
        assertNotNull(files);
        assertEquals(1, files.length);
        assertEquals("/data/user/0/app/files/photo.jpg", files[0]);
    }

    @Test
    public void getFiles_conversationWithNoAttachments_returnsNull() {
        chatBase.insert(conversation, "sent", new EcclesMessage(EcclesMessage.TYPE_CHAT, EcclesMessage.SUBTYPE_TEXT)
                .setSender(conversation).setData("only text".getBytes()).setDate("d"));
        assertNull(chatBase.getFiles(conversation));
    }

    /** Naive subsequence search, sufficient for a small test payload. */
    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
