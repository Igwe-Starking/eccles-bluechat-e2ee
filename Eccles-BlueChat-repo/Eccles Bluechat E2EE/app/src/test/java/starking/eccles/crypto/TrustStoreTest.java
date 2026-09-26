package starking.eccles.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;
import java.security.SecureRandom;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * SharedPreferences is well-supported by Robolectric, so this runs as a fast local-JVM unit
 * test rather than needing a device, unlike {@link KeystoreAesInstrumentedTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class TrustStoreTest {

    private TrustStore trustStore;

    @Before
    public void setUp() {
        trustStore = new TrustStore(ApplicationProvider.getApplicationContext());
    }

    @Test
    public void check_firstTimeSeeingAnAddress_isNew() {
        assertEquals(TrustStore.Result.NEW, trustStore.check("AA:BB:CC", randomFingerprint()));
    }

    @Test
    public void check_sameFingerprintAgain_isTrusted() {
        byte[] fp = randomFingerprint();
        trustStore.check("AA:BB:CC", fp);
        assertEquals(TrustStore.Result.TRUSTED, trustStore.check("AA:BB:CC", fp));
    }

    @Test
    public void check_differentFingerprint_isChanged() {
        trustStore.check("AA:BB:CC", randomFingerprint());
        assertEquals(TrustStore.Result.CHANGED, trustStore.check("AA:BB:CC", randomFingerprint()));
    }

    @Test
    public void check_afterChangeDetected_repeatedChecksKeepReportingChangedUntilConfirmed() {
        // This is a security-relevant property: a CHANGED result must not silently become the
        // new baseline just because the app asked again. Otherwise an attacker who wins one
        // race (or a single MITM'd session) would only ever see one warning, and every
        // subsequent connection would silently re-trust the attacker's key.
        byte[] original = randomFingerprint();
        byte[] attacker = randomFingerprint();
        trustStore.check("AA:BB:CC", original);
        assertEquals(TrustStore.Result.CHANGED, trustStore.check("AA:BB:CC", attacker));
        assertEquals("a CHANGED result must not implicitly become trusted on the next check",
                TrustStore.Result.CHANGED, trustStore.check("AA:BB:CC", attacker));
    }

    @Test
    public void confirmChange_thenSameFingerprintIsTrusted() {
        byte[] original = randomFingerprint();
        byte[] updated = randomFingerprint();
        trustStore.check("AA:BB:CC", original);
        trustStore.check("AA:BB:CC", updated); // CHANGED, not yet confirmed

        trustStore.confirmChange("AA:BB:CC", updated);

        assertEquals(TrustStore.Result.TRUSTED, trustStore.check("AA:BB:CC", updated));
        // The old key must no longer be trusted either.
        assertEquals(TrustStore.Result.CHANGED, trustStore.check("AA:BB:CC", original));
    }

    @Test
    public void forget_removesTrustSoNextCheckIsNewAgain() {
        byte[] fp = randomFingerprint();
        trustStore.check("AA:BB:CC", fp);
        trustStore.forget("AA:BB:CC");
        assertEquals(TrustStore.Result.NEW, trustStore.check("AA:BB:CC", fp));
    }

    @Test
    public void isKnown_reflectsWhetherAnAddressHasEverBeenChecked() {
        assertFalse(trustStore.isKnown("AA:BB:CC"));
        trustStore.check("AA:BB:CC", randomFingerprint());
        assertTrue(trustStore.isKnown("AA:BB:CC"));
        trustStore.forget("AA:BB:CC");
        assertFalse(trustStore.isKnown("AA:BB:CC"));
    }

    @Test
    public void differentAddresses_areTrackedIndependently() {
        byte[] fpA = randomFingerprint();
        byte[] fpB = randomFingerprint();
        assertEquals(TrustStore.Result.NEW, trustStore.check("AA:AA:AA", fpA));
        assertEquals(TrustStore.Result.NEW, trustStore.check("BB:BB:BB", fpB));
        assertEquals(TrustStore.Result.TRUSTED, trustStore.check("AA:AA:AA", fpA));
        assertEquals(TrustStore.Result.TRUSTED, trustStore.check("BB:BB:BB", fpB));
    }

    private static byte[] randomFingerprint() {
        byte[] fp = new byte[32];
        new SecureRandom().nextBytes(fp);
        return fp;
    }
}
