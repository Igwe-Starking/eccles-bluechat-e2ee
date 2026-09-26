package starking.eccles.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import starking.eccles.Interface.EcclesMessage;
import starking.eccles.Surface.ChatPojo;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.crypto.KeystoreAes;

/**
 * Local persistence for chat message history.
 * <p>
 * Every message payload is encrypted at rest using a device-bound {@link KeystoreAes} key
 * (Android Keystore, AES-256-GCM) before being written to SQLite, independently of the
 * end-to-end encryption used on the wire between devices. Conversation keys are derived from
 * the peer's Bluetooth address via {@link ClassicCompat#purifyAddress}, so lookups are
 * consistent regardless of how the address string was originally cased/formatted.
 */
public class ChatBase extends SQLiteOpenHelper {

    private static final String TABLE = "messages";
    private static final String DB_NAME = "starking.eccles.bluechat.chatbase";
    private static final int DB_VERSION = 3;

    public static final String COLUMN_ID = "ID";
    public static final String COLUMN_SENDER = "SENDER";
    public static final String COLUMN_TYPE = "TYPE";
    public static final String COLUMN_DATE = "DATE";
    public static final String COLUMN_DATA = "DATA";
    public static final String COLUMN_RECIPIENT = "RECEIVER";
    public static final String COLUMN_STATUS = "STATUS";
    public static final String COLUMN_CONVERSATION = "CONVERSATION";

    /** At-rest encryption key for message payloads. Independent of the E2EE session keys. */
    private final KeystoreAes atRest = new KeystoreAes("eccles_local_storage_key");

    public ChatBase(EcclesApplication app) {
        super(app, DB_NAME, null, DB_VERSION);
        this.app = app;
        retryFailedAtRestMigrations();
    }

    private static final String PREF_KEY_FAILED_MIGRATION_ROWS = "chatbase_unencrypted_row_ids";
    private final EcclesApplication app;

    /**
     * Retries encrypting any row that failed during a previous {@link #encryptExistingRows}
     * pass, tracked by ID in SharedPreferences.
     * <p>
     * The version-2-to-3 migration previously logged a per-row encryption failure but still let
     * the database version advance regardless - meaning a row that failed to encrypt (e.g. a
     * transient Keystore failure right after a reboot) would stay plaintext forever, since
     * SQLiteOpenHelper only runs {@link #onUpgrade} once per version transition. This runs on
     * every ChatBase construction (i.e. every app start, cheap no-op once nothing remains to
     * retry) and only touches the specific row IDs recorded as failed, never re-scanning
     * already-succeeded rows - re-encrypting an already-encrypted row would corrupt it.
     */
    private void retryFailedAtRestMigrations() {
        if (app == null || app.pref == null) {
            return;
        }
        String stored = app.pref.getString(PREF_KEY_FAILED_MIGRATION_ROWS, "");
        if (stored.isEmpty()) {
            return;
        }
        java.util.Set<String> stillFailed = new java.util.HashSet<>();
        SQLiteDatabase db = getWritableDatabase();
        for (String idStr : stored.split(",")) {
            if (idStr.isEmpty()) continue;
            try (Cursor c = db.query(TABLE, new String[]{COLUMN_DATA}, COLUMN_ID + "=?", new String[]{idStr}, null, null, null)) {
                if (!c.moveToFirst() || c.isNull(0)) {
                    continue; // row gone or already null; nothing to retry
                }
                try {
                    byte[] encrypted = atRest.encrypt(c.getBlob(0));
                    ContentValues values = new ContentValues();
                    values.put(COLUMN_DATA, encrypted);
                    db.update(TABLE, values, COLUMN_ID + "=?", new String[]{idStr});
                } catch (Exception e) {
                    android.util.Log.e("ChatBase", "retry: still failed to encrypt row " + idStr, e);
                    stillFailed.add(idStr);
                }
            } catch (Exception e) {
                android.util.Log.e("ChatBase", "retryFailedAtRestMigrations row " + idStr, e);
                stillFailed.add(idStr);
            }
        }
        app.pref.edit().putString(PREF_KEY_FAILED_MIGRATION_ROWS, String.join(",", stillFailed)).apply();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "CONVERSATION TEXT NOT NULL, "
                + "SENDER TEXT, "
                + "RECEIVER TEXT, "
                + "TYPE INTEGER NOT NULL, "
                + "DATA BLOB, "
                + "STATUS TEXT, "
                + "DATE TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON " + TABLE + "(CONVERSATION, ID)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            onCreate(db);
            migrateLegacyTables(db);
        }
        if (oldVersion < 3) {
            encryptExistingRows(db);
        }
    }

    /**
     * Version-2-to-3 migration: encrypts message payloads that were persisted before at-rest
     * encryption was introduced. Each row is migrated independently so that one row failing to
     * encrypt doesn't abort the migration for the rest of the table.
     */
    private void encryptExistingRows(SQLiteDatabase db) {
        java.util.Set<String> failedIds = new java.util.HashSet<>();
        try (Cursor c = db.query(TABLE, new String[]{COLUMN_ID, COLUMN_DATA}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                if (c.isNull(1)) {
                    continue;
                }
                byte[] plaintext = c.getBlob(1);
                try {
                    byte[] encrypted = atRest.encrypt(plaintext);
                    ContentValues values = new ContentValues();
                    values.put(COLUMN_DATA, encrypted);
                    db.update(TABLE, values, COLUMN_ID + "=?", new String[]{String.valueOf(id)});
                } catch (Exception e) {
                    android.util.Log.e("ChatBase", "failed to encrypt row " + id + " during migration", e);
                    failedIds.add(String.valueOf(id));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ChatBase", "encryptExistingRows", e);
        }
        // Record any rows that failed so retryFailedAtRestMigrations() can retry them on a
        // later app start - see that method's doc. Without this, a row that failed here (e.g.
        // a transient Keystore error) would stay plaintext forever, since this migration only
        // ever runs once per database version transition.
        if (!failedIds.isEmpty() && app != null && app.pref != null) {
            app.pref.edit().putString(PREF_KEY_FAILED_MIGRATION_ROWS, String.join(",", failedIds)).apply();
        }
    }

    private byte[] decryptAtRest(byte[] ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            return atRest.decrypt(ciphertext);
        } catch (Exception e) {
            android.util.Log.e("ChatBase", "at-rest decryption failed, message may be corrupt or predates encryption", e);
            return null;
        }
    }

    private String key(String address) {
        return ClassicCompat.purifyAddress(address == null ? "" : address);
    }

    /**
     * Version-1-to-2 migration: earlier releases stored each conversation in its own
     * dynamically-named table. This copies every such table's rows into the unified
     * {@code messages} table before those legacy tables are abandoned.
     * <p>
     * Legacy table names are validated against {@code [A-Za-z0-9_]+} before being used to build
     * a query, and are additionally double-quoted as a SQLite identifier for defense-in-depth.
     * SQLite has no way to bind a table name as a query parameter (only values can be
     * parameterized), so identifier quoting plus this allow-list validation is the correct
     * mitigation here, not a "?" placeholder.
     */
    private void migrateLegacyTables(SQLiteDatabase db) {
        try (Cursor tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> ?",
                new String[]{TABLE})) {
            while (tables.moveToNext()) {
                String table = tables.getString(0);
                if (!table.matches("[A-Za-z0-9_]+")) {
                    continue;
                }
                migrateLegacyTable(db, table);
            }
        }
    }

    private void migrateLegacyTable(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT ID,SENDER,RECEIVER,TYPE,DATA,STATUS,DATE FROM \"" + table + "\"", null)) {
            while (c.moveToNext()) {
                ContentValues values = new ContentValues();
                values.put(COLUMN_CONVERSATION, key(table));
                values.put(COLUMN_SENDER, c.getString(1));
                values.put(COLUMN_RECIPIENT, c.getString(2));
                values.put(COLUMN_TYPE, c.getInt(3));
                if (c.isNull(4)) {
                    values.putNull(COLUMN_DATA);
                } else {
                    values.put(COLUMN_DATA, c.getString(4).getBytes(StandardCharsets.UTF_8));
                }
                values.put(COLUMN_STATUS, c.getString(5));
                values.put(COLUMN_DATE, c.getString(6));
                db.insert(TABLE, null, values);
            }
        } catch (Exception e) {
            // Best-effort per-table migration: one bad legacy table must not abort migration of
            // the remaining tables, but silently swallowing this previously made partial/failed
            // migrations invisible. Log it so a lost/partial migration is at least diagnosable.
            android.util.Log.e("ChatBase", "legacy message migration failed for table " + table
                    + ", some history may be missing", e);
        }
    }

    /**
     * Persists a message. The at-rest payload is encrypted before being written; {@code status}
     * (e.g. "sent", "delivered", "failed") is stored alongside it for UI display.
     *
     * @return true if the row was inserted, false on failure (including a null message).
     */
    public boolean insert(String name, String status, EcclesMessage message) {
        if (message == null) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put(COLUMN_CONVERSATION, key(message.sender != null ? message.sender : name));
        values.put(COLUMN_SENDER, message.sender);
        values.put(COLUMN_RECIPIENT, message.to);
        values.put(COLUMN_TYPE, message.subtype);
        values.put(COLUMN_DATE, message.date);

        byte[] encrypted;
        if (message.data == null) {
            // Legitimately no payload (e.g. a call-signaling message) - not a failure.
            encrypted = null;
        } else {
            try {
                encrypted = atRest.encrypt(message.data);
            } catch (Exception e) {
                // Fail closed: encryptAtRest() previously mapped this same exception to null,
                // which insert() then silently stored as an empty (NULL DATA) row while still
                // reporting success - a real message with real content could be silently
                // reduced to nothing, with the caller believing it was saved. A crypto failure
                // must propagate as an insert failure, never as a successful empty message.
                android.util.Log.e("ChatBase", "insert: at-rest encryption failed, refusing to store an empty row", e);
                return false;
            }
        }
        if (encrypted != null) {
            values.put(COLUMN_DATA, encrypted);
        } else {
            values.putNull(COLUMN_DATA);
        }
        if (status != null) {
            values.put(COLUMN_STATUS, status);
        } else {
            values.putNull(COLUMN_STATUS);
        }

        try {
            return getWritableDatabase().insert(TABLE, null, values) != -1;
        } catch (Exception e) {
            android.util.Log.e("ChatBase", "insert", e);
            return false;
        }
    }

    /**
     * Loads the full history for a conversation on a background thread and appends each message
     * to {@code activity}'s adapter on the UI thread, most recent first.
     */
    public void readAll(final ChatActivity activity, String address) {
        final ExecutorService service = Executors.newSingleThreadExecutor();
        final String conversation = key(address);
        service.execute(() -> {
            try (Cursor c = getReadableDatabase().query(TABLE, null, COLUMN_CONVERSATION + "=?",
                    new String[]{conversation}, null, null, COLUMN_ID + " DESC")) {
                while (c.moveToNext()) {
                    byte[] raw = c.isNull(c.getColumnIndexOrThrow(COLUMN_DATA))
                            ? null
                            : decryptAtRest(c.getBlob(c.getColumnIndexOrThrow(COLUMN_DATA)));
                    EcclesMessage m = new EcclesMessage(EcclesMessage.TYPE_CHAT, c.getInt(c.getColumnIndexOrThrow(COLUMN_TYPE)))
                            .setDate(c.getString(c.getColumnIndexOrThrow(COLUMN_DATE)))
                            .setSender(c.getString(c.getColumnIndexOrThrow(COLUMN_SENDER)))
                            .setData(raw);
                    int id = c.getInt(c.getColumnIndexOrThrow(COLUMN_ID));
                    String status = c.getString(c.getColumnIndexOrThrow(COLUMN_STATUS));
                    activity.runOnUiThread(() -> {
                        activity.adapter.pojos.add(0, new ChatPojo(m, activity).setIndex(id).setStatus(status));
                        activity.adapter.notifyDataSetChanged();
                    });
                }
                activity.runOnUiThread(() -> {
                    // Replaces the old ListView.TRANSCRIPT_MODE_NORMAL toggle: RecyclerView has
                    // no auto-scroll "mode" to turn off, so once history has finished loading we
                    // just scroll to the last (most recent) message once, which produces the
                    // same end result the user sees - the chat open scrolled to the bottom.
                    if (!activity.adapter.pojos.isEmpty()) {
                        activity.chatList.scrollToPosition(activity.adapter.pojos.size() - 1);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("ChatBase", "readAll", e);
            } finally {
                service.shutdown();
            }
        });
    }

    /**
     * @return the decrypted payloads of every non-text (attachment) message in a conversation,
     * or null if there are none.
     */
    public String[] getFiles(String address) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, new String[]{COLUMN_DATA},
                COLUMN_CONVERSATION + "=? AND " + COLUMN_TYPE + "!=?",
                new String[]{key(address), String.valueOf(EcclesMessage.SUBTYPE_TEXT)},
                null, null, COLUMN_ID)) {
            while (c.moveToNext()) {
                byte[] b = c.isNull(0) ? null : decryptAtRest(c.getBlob(0));
                if (b != null) {
                    out.add(new String(b, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ChatBase", "getFiles", e);
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    /** @return true if a row was actually deleted, false if nothing matched or an error occurred. */
    public boolean delete(String address, int id) {
        // SQLiteDatabase.delete() returns the number of rows affected (0 if nothing matched),
        // never -1 - only insert() uses -1 as a failure sentinel. ">0" is the correct check for
        // "did this actually delete something".
        try {
            return getWritableDatabase().delete(TABLE, "ID=? AND CONVERSATION=?",
                    new String[]{String.valueOf(id), key(address)}) > 0;
        } catch (Exception e) {
            android.util.Log.e("ChatBase", "delete", e);
            return false;
        }
    }

    public void clear(String address) {
        getWritableDatabase().delete(TABLE, COLUMN_CONVERSATION + "=?", new String[]{key(address)});
    }

    /** @return true if a row was actually updated, false if nothing matched or an error occurred. */
    public boolean updateStatus(String address, String status, int id) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_STATUS, status);
        try {
            return getWritableDatabase().update(TABLE, values, "ID=? AND CONVERSATION=?",
                    new String[]{String.valueOf(id), key(address)}) > 0;
        } catch (Exception e) {
            android.util.Log.e("ChatBase", "updateStatus", e);
            return false;
        }
    }
}
