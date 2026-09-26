package starking.eccles.data;

import android.bluetooth.BluetoothDevice;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.util.EcclesIcon;
import starking.eccles.util.FriendlyDate;

/**
 * Local persistence for the contacts/conversations list: display name, address, cached icon,
 * and last-message preview.
 */
public class EcclesData extends SQLiteOpenHelper {

    private static final String TABLE = "contacts";
    private static final int DB_VERSION = 2;

    public static final String COLUMN_ID = "ID";
    public static final String COLUMN_NAME = "NAME";
    public static final String COLUMN_ADDRESS = "ADDRESS";
    public static final String COLUMN_IMAGE = "IMAGE";
    public static final String COLUMN_LAST_MESSAGE = "LASTMESSAGE";
    public static final String COLUMN_DATE = "DATE";

    private final EcclesApplication app;
    private final String dbName;

    public EcclesData(EcclesApplication app, String dbName) {
        super(app, dbName, null, DB_VERSION);
        this.app = app;
        this.dbName = dbName;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "NAME TEXT, "
                + "ADDRESS TEXT UNIQUE, "
                + "IMAGE BLOB, "
                + "DATE TEXT, "
                + "LASTMESSAGE TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            onCreate(db);
            migrateLegacyContacts(db);
        }
    }

    /**
     * Version-1-to-2 migration: contacts used to live in a per-install, dynamically-named
     * legacy database file; this copies them into the unified {@code contacts} table.
     * <p>
     * {@code dbName} is an internal legacy database filename, not externally-controlled input,
     * but it is still validated against an allow-list and safely quoted before being used as a
     * SQL identifier. SQLite has no way to bind a table name as a query parameter (only values
     * can be parameterized), so identifier validation plus quoting is the correct mitigation
     * here, not a "?" placeholder.
     */
    private void migrateLegacyContacts(SQLiteDatabase db) {
        if (dbName == null || !dbName.matches("[A-Za-z0-9 _.\\-]+")) {
            android.util.Log.w("EcclesData", "skipping legacy migration: unexpected legacy db name format");
            return;
        }
        String legacyTable = dbName.replace("\"", "\"\"");
        try (Cursor c = db.rawQuery("SELECT NAME,ADDRESS,IMAGE,DATE,LASTMESSAGE FROM \"" + legacyTable + "\"", null)) {
            while (c.moveToNext()) {
                ContentValues values = new ContentValues();
                values.put(COLUMN_NAME, c.getString(0));
                values.put(COLUMN_ADDRESS, c.getString(1));
                values.put(COLUMN_IMAGE, c.getBlob(2));
                values.put(COLUMN_DATE, c.getString(3));
                values.put(COLUMN_LAST_MESSAGE, c.getString(4));
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Exception e) {
            // Best-effort legacy migration: we intentionally don't rethrow here (a failed
            // migration must not prevent the app's contacts database from opening), but
            // silently swallowing this previously made migration failures invisible and
            // undebuggable. Log it so a lost/partial migration is at least diagnosable.
            android.util.Log.e("EcclesData", "legacy contact migration failed, some contacts may be missing", e);
        }
    }

    /** Inserts or replaces (by address) a contact discovered via Bluetooth. */
    public boolean insert(BluetoothDevice device, String lastMessage) {
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_NAME, device.getName());
            values.put(COLUMN_ADDRESS, device.getAddress());
            values.put(COLUMN_IMAGE, EcclesIcon.convertToBytes(ClassicCompat.queryDeviceIcon(device.getAddress(), app)));
            values.put(COLUMN_LAST_MESSAGE, lastMessage);
            values.put(COLUMN_DATE, FriendlyDate.format());
            return getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (Exception e) {
            android.util.Log.e("EcclesData", "insert failed", e);
            return false;
        }
    }

    /** @return true if a row was actually updated, false if the address wasn't found or an error occurred. */
    public boolean updateNewMessage(String address, String lastMessage) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_LAST_MESSAGE, lastMessage);
        values.put(COLUMN_DATE, FriendlyDate.format());
        // SQLiteDatabase.update() returns the affected-row count (0 if nothing matched), never
        // -1 - only insert() uses -1 as a failure sentinel. ">0" is the correct check for
        // "did this actually update something".
        try {
            return getWritableDatabase().update(TABLE, values, COLUMN_ADDRESS + " = ?", new String[]{address}) > 0;
        } catch (Exception e) {
            android.util.Log.e("EcclesData", "updateNewMessage", e);
            return false;
        }
    }

    /** @return every contact, most recently updated first, or null if there are none. */
    public EcclesPojo[] read(int client) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, null, null, null, null, COLUMN_ID + " DESC")) {
            if (cursor.getCount() == 0) {
                return null;
            }
            EcclesPojo[] pojos = new EcclesPojo[cursor.getCount()];
            int i = 0;
            while (cursor.moveToNext()) {
                BluetoothDevice device = app.adapter.getRemoteDevice(
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADDRESS)));
                String preview = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LAST_MESSAGE))
                        + "\b" + cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DATE));
                pojos[i++] = new EcclesPojo(
                        device,
                        app.currentActivity,
                        R.layout.pojo_view,
                        EcclesIcon.resize(60, 60,
                                EcclesIcon.convertToBitmap(cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_IMAGE))),
                                app.currentActivity),
                        false,
                        preview,
                        client);
            }
            return pojos;
        } catch (Exception e) {
            android.util.Log.e("EcclesData", "read", e);
            return null;
        }
    }

    /** @return true if a row was actually deleted, false if the address wasn't found or an error occurred. */
    public boolean delete(String address) {
        try {
            return getWritableDatabase().delete(TABLE, COLUMN_ADDRESS + " = ?", new String[]{address}) > 0;
        } catch (Exception e) {
            android.util.Log.e("EcclesData", "delete", e);
            return false;
        }
    }

    public void deleteAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }
}
