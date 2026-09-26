package starking.eccles.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

public final class TrustStore {

    private static final String PREFS = "eccles_identity_trust";

    public enum Result { NEW, TRUSTED, CHANGED }

    private final SharedPreferences prefs;

    public TrustStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Result check(String remoteAddress, byte[] fingerprint) {
        String encoded = Base64.encodeToString(fingerprint, Base64.NO_WRAP);
        String existing = prefs.getString(remoteAddress, null);
        if (existing == null) {
            prefs.edit().putString(remoteAddress, encoded).apply();
            return Result.NEW;
        }
        if (existing.equals(encoded)) return Result.TRUSTED;
        return Result.CHANGED;
    }

    public synchronized void confirmChange(String remoteAddress, byte[] fingerprint) {
        prefs.edit().putString(remoteAddress, Base64.encodeToString(fingerprint, Base64.NO_WRAP)).apply();
    }

    public synchronized void forget(String remoteAddress) {
        prefs.edit().remove(remoteAddress).apply();
    }

    public synchronized boolean isKnown(String remoteAddress) {
        return prefs.contains(remoteAddress);
    }
}
