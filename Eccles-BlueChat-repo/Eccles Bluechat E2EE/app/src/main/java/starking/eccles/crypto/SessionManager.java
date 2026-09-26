package starking.eccles.crypto;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SessionManager {

    private static final long HANDSHAKE_TIMEOUT_SECONDS = 15;

    private static volatile SessionManager instance;

    private final Context appContext;
    private final TrustStore trustStore;
    private final Map<BluetoothSocket, EccSession> sessions = new ConcurrentHashMap<>();
    /**
     * Bounded, least-recently-used address->lock cache, capped at {@link #MAX_ADDRESS_LOCKS}
     * entries.
     * <p>
     * A plain unbounded {@code ConcurrentHashMap} would not work here: entries (one tiny
     * {@code Object} per distinct Bluetooth address ever handshaken with) would never be
     * removed for the lifetime of the app process, growing without bound for a long-lived
     * instance that connects to many distinct peers over time.
     * <p>
     * A correct, fully unbounded-growth-free approach would need reference-counted or
     * weak-reference-based lock cleanup (e.g. Guava's {@code Striped} locks) to guarantee two
     * concurrent handshake attempts for the same address always synchronize on the exact same
     * lock object - implementing that by hand risks a subtle new race (an evicted-then-recreated
     * lock briefly failing to exclude a genuinely concurrent handshake to the same address).
     * Bounding the cache size via LRU eviction is a much simpler, safe mitigation: growth is
     * capped, and since a single device can only have one active BluetoothSocket connection at
     * a time in this app's model, two truly concurrent handshake attempts to the very same
     * address are already an inherently rare edge case - eviction narrowing that further is an
     * acceptable, clearly-bounded tradeoff rather than an unbounded memory leak.
     */
    private static final int MAX_ADDRESS_LOCKS = 256;
    private final Map<String, Object> addressLocks = Collections.synchronizedMap(
            new LinkedHashMap<String, Object>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > MAX_ADDRESS_LOCKS;
                }
            });
    private final ExecutorService handshakeExecutor = Executors.newCachedThreadPool();

    private SessionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.trustStore = new TrustStore(appContext);
    }

    public static synchronized SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context);
        return instance;
    }

    public TrustStore getTrustStore() {
        return trustStore;
    }

    @SuppressWarnings("MissingPermission")
    public EccSession obtain(final BluetoothSocket socket) throws IOException, GeneralSecurityException, KeyChangedException {
        EccSession existing = sessions.get(socket);
        if (existing != null) return existing;

        final String address = socket.getRemoteDevice().getAddress();
        final Object lock;
        synchronized (addressLocks) {
            Object existingLock = addressLocks.get(address);
            if (existingLock == null) {
                existingLock = new Object();
                addressLocks.put(address, existingLock);
            }
            lock = existingLock;
        }

        synchronized (lock) {
            existing = sessions.get(socket);
            if (existing != null) return existing;

            Future<EccSession> future = handshakeExecutor.submit((Callable<EccSession>) () -> {
                IdentityKeyManager id = IdentityKeyManager.get(appContext);
                return Handshake.perform(address, socket.getInputStream(), socket.getOutputStream(),
                        id.getPrivateKey(), id.getPublicKeyEncoded());
            });

            EccSession session;
            try {
                session = future.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                try { socket.close(); } catch (IOException ignored) {}
                throw new IOException("secure handshake with " + address + " timed out after " + HANDSHAKE_TIMEOUT_SECONDS + "s");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof GeneralSecurityException) throw (GeneralSecurityException) cause;
                throw new IOException("secure handshake with " + address + " failed", cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("secure handshake with " + address + " interrupted", ie);
            }

            TrustStore.Result result = trustStore.check(address, session.remoteFingerprint);
            if (result == TrustStore.Result.CHANGED) {
                throw new KeyChangedException(address, session.remoteFingerprint, session);
            }
            sessions.put(socket, session);
            return session;
        }
    }

    /**
     * Confirms the user has chosen to trust a changed identity key and installs the session
     * that was already computed by the handshake which discovered the change (see
     * {@link KeyChangedException#session}).
     * <p>
     * Previously this only called {@link TrustStore#confirmChange}, updating the trust policy
     * but never actually installing a session - the connection remained unusable after the
     * user tapped "Trust New Key" despite the dialog implying it would continue. Installing the
     * session here means the next {@link #obtain} call for this socket returns it immediately
     * via the existing-session fast path above, with no second handshake attempt (which would
     * likely fail/desync with a peer not expecting one). The caller is still responsible for
     * actually resuming reads on this socket - see {@code EcclesActivity#onIdentityKeyChanged}.
     */
    public void forceTrustAndRetry(BluetoothSocket socket, EccSession session, byte[] fingerprint, String address) {
        trustStore.confirmChange(address, fingerprint);
        sessions.put(socket, session);
    }

    public void invalidate(BluetoothSocket socket) {
        sessions.remove(socket);
    }
}
