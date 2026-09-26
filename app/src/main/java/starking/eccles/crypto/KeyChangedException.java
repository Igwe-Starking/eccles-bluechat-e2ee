package starking.eccles.crypto;

public final class KeyChangedException extends Exception {

    public final String remoteAddress;
    public final byte[] newFingerprint;
    /**
     * The session computed by the handshake that discovered this key change. The cryptographic
     * handshake itself already succeeded by the time this exception is thrown - only the local
     * trust policy check failed - so if the user chooses to trust the new key, there is no need
     * to re-run the handshake: this already-valid session can simply be installed directly. See
     * {@link SessionManager#forceTrustAndRetry}.
     */
    public final EccSession session;

    public KeyChangedException(String remoteAddress, byte[] newFingerprint, EccSession session) {
        super("Identity key for " + remoteAddress + " changed");
        this.remoteAddress = remoteAddress;
        this.newFingerprint = newFingerprint;
        this.session = session;
    }
}
