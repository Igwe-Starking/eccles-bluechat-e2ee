package starking.eccles.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentValues;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import starking.eccles.bluechat.EcclesApplication;

/**
 * Runs on a real device/emulator for the same reason as {@link ChatBaseInstrumentedTest}: real
 * SQLite persistence. Tests that need a {@link BluetoothDevice} instance (which requires a
 * system Bluetooth adapter to construct via {@link BluetoothAdapter#getRemoteDevice}) are
 * skipped with {@link org.junit.Assume} rather than failed outright on an emulator image with
 * no Bluetooth support at all; the raw-SQL-level tests below don't have that dependency and
 * always run.
 */
@RunWith(AndroidJUnit4.class)
public class EcclesDataInstrumentedTest {

    private EcclesData ecclesData;
    private String dbName;

    @Before
    public void setUp() {
        EcclesApplication app = ApplicationProvider.getApplicationContext();
        // A unique DB file per test avoids any cross-test interference and lets us delete the
        // whole file afterwards instead of having to individually clear rows.
        dbName = "test_contacts_" + UUID.randomUUID() + ".db";
        ecclesData = new EcclesData(app, dbName);
    }

    @After
    public void tearDown() {
        if (ecclesData != null) {
            ecclesData.close();
            ApplicationProvider.<EcclesApplication>getApplicationContext().deleteDatabase(dbName);
        }
    }

    @Test
    public void delete_nonExistentAddress_returnsFalse() {
        // Regression test: same reasoning as
        // ChatBaseInstrumentedTest#delete_nonMatchingIdOrConversation_returnsFalse - delete()
        // must not report success by checking "!=-1" against SQLiteDatabase.delete()'s
        // affected-row-count return value (which is 0, not -1, when nothing matched).
        assertFalse(ecclesData.delete("AA:BB:CC:DD:EE:FF-does-not-exist"));
    }

    @Test
    public void updateNewMessage_nonExistentAddress_returnsFalse() {
        assertFalse(ecclesData.updateNewMessage("AA:BB:CC:DD:EE:FF-does-not-exist", "msg"));
    }

    @Test
    public void insertRawRow_thenRead_roundTrips() {
        insertRawContact("Alice", "AA:BB:CC:DD:EE:01", "hello there");

        try (Cursor c = ecclesData.getReadableDatabase().query("contacts", null,
                EcclesData.COLUMN_ADDRESS + "=?", new String[]{"AA:BB:CC:DD:EE:01"}, null, null, null)) {
            assertTrue(c.moveToFirst());
            assertEquals("Alice", c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_NAME)));
            assertEquals("hello there", c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_LAST_MESSAGE)));
        }
    }

    @Test
    public void address_hasUniqueConstraint_conflictReplacesExistingRow() {
        insertRawContact("Alice", "AA:BB:CC:DD:EE:02", "first message");
        insertRawContact("Alice Renamed", "AA:BB:CC:DD:EE:02", "second message");

        try (Cursor c = ecclesData.getReadableDatabase().query("contacts", null,
                EcclesData.COLUMN_ADDRESS + "=?", new String[]{"AA:BB:CC:DD:EE:02"}, null, null, null)) {
            assertEquals("re-inserting the same address must replace, not duplicate, the row", 1, c.getCount());
            c.moveToFirst();
            assertEquals("Alice Renamed", c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_NAME)));
        }
    }

    @Test
    public void updateNewMessage_changesLastMessageAndDate() {
        insertRawContact("Bob", "AA:BB:CC:DD:EE:03", "old message");

        assertTrue(ecclesData.updateNewMessage("AA:BB:CC:DD:EE:03", "new message"));

        try (Cursor c = ecclesData.getReadableDatabase().query("contacts", null,
                EcclesData.COLUMN_ADDRESS + "=?", new String[]{"AA:BB:CC:DD:EE:03"}, null, null, null)) {
            c.moveToFirst();
            assertEquals("new message", c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_LAST_MESSAGE)));
            assertNotNull(c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_DATE)));
        }
    }

    @Test
    public void delete_removesOnlyMatchingAddress() {
        insertRawContact("Carol", "AA:BB:CC:DD:EE:04", "m1");
        insertRawContact("Dave", "AA:BB:CC:DD:EE:05", "m2");

        assertTrue(ecclesData.delete("AA:BB:CC:DD:EE:04"));

        try (Cursor c = ecclesData.getReadableDatabase().query("contacts", null, null, null, null, null, null)) {
            assertEquals(1, c.getCount());
            c.moveToFirst();
            assertEquals("Dave", c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_NAME)));
        }
    }

    @Test
    public void deleteAll_emptiesTheTable() {
        insertRawContact("Carol", "AA:BB:CC:DD:EE:06", "m1");
        insertRawContact("Dave", "AA:BB:CC:DD:EE:07", "m2");

        ecclesData.deleteAll();

        try (Cursor c = ecclesData.getReadableDatabase().query("contacts", null, null, null, null, null, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void insertViaBluetoothDevice_thenReadBack() {
        EcclesApplication app = ApplicationProvider.getApplicationContext();
        assumeTrue("skipping: no system Bluetooth adapter available on this device/emulator image",
                app.adapter != null);

        BluetoothDevice device = app.adapter.getRemoteDevice("AA:BB:CC:DD:EE:08");
        assertTrue(ecclesData.insert(device, "hi from insert()"));

        try (Cursor c = ecclesData.getReadableDatabase().query("contacts", null,
                EcclesData.COLUMN_ADDRESS + "=?", new String[]{"AA:BB:CC:DD:EE:08"}, null, null, null)) {
            assertTrue(c.moveToFirst());
            assertEquals("hi from insert()", c.getString(c.getColumnIndexOrThrow(EcclesData.COLUMN_LAST_MESSAGE)));
        }
    }

    @Test
    public void read_withNoContacts_returnsNull() {
        assertEquals(null, ecclesData.read(0));
    }

    private void insertRawContact(String name, String address, String lastMessage) {
        ContentValues v = new ContentValues();
        v.put(EcclesData.COLUMN_NAME, name);
        v.put(EcclesData.COLUMN_ADDRESS, address);
        v.put(EcclesData.COLUMN_LAST_MESSAGE, lastMessage);
        v.put(EcclesData.COLUMN_DATE, "test-date");
        ecclesData.getWritableDatabase().insertWithOnConflict("contacts", null, v,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }
}
