package starking.eccles.bluechat.bclassic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.service.Listener;

/**
 * {@link ClassicCompat#uuid} is a pure-Java constant (computed from {@link UUID#nameUUIDFromBytes})
 * and {@link ClassicCompat#checkOnline} only calls mockable Android SDK methods, so this runs as
 * a plain JUnit test (Mockito mocks bypass the real "not mocked" stub bodies without needing
 * Robolectric or a device).
 * <p>
 * This file guards against three call sites ({@link EcclesActivity}, {@link Listener}, and this
 * class) independently deriving the app's RFCOMM service UUID and drifting out of sync - for
 * example if one of them ever used a differently-spelled seed string. A mismatch would silently
 * break online/peer detection, because the UUID this class checks incoming devices against would
 * no longer match the UUID the other two classes advertise/connect with. All three derive from
 * the single {@link ClassicCompat#SERVICE_NAME_SEED} constant, so this test pins that seed's
 * exact value and the fact that it is used consistently.
 */
public class ClassicCompatUuidTest {

    @Test
    public void serviceNameSeed_isTheCorrectlySpelledWord() {
        assertEquals("Ecclesiastes", ClassicCompat.SERVICE_NAME_SEED);
    }

    @Test
    public void uuid_isDerivedFromTheCanonicalSeedConstant() {
        UUID expected = UUID.nameUUIDFromBytes(ClassicCompat.SERVICE_NAME_SEED.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, ClassicCompat.uuid);
    }

    @Test
    public void uuid_matchesWhatEcclesActivityAndListenerAdvertiseWith() {
        // EcclesActivity.connect() and Listener.run() both call
        // device.createRfcommSocketToServiceRecord(ClassicCompat.uuid) /
        // adapter.listenUsingRfcommWithServiceRecord("Eccles", ClassicCompat.uuid) directly, so
        // as long as they keep referencing this same constant (rather than re-deriving their
        // own UUID from a hardcoded string, which is what caused the original bug), they are
        // guaranteed to agree with checkOnline()'s expectations. This pins the exact UUID value
        // (independently computed from UUID.nameUUIDFromBytes("Ecclesiastes")) so an accidental
        // future change to SERVICE_NAME_SEED is caught explicitly rather than silently shifting
        // what devices are considered "online".
        assertEquals(UUID.fromString("90d3f2a6-52fa-30d5-aaa3-076d0f2f62be"), ClassicCompat.uuid);
    }

    @Test
    public void checkOnline_deviceAdvertisingTheEcclesUuid_isOnline() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getUuids()).thenReturn(new ParcelUuid[]{new ParcelUuid(ClassicCompat.uuid)});

        assertTrue(ClassicCompat.checkOnline(device));
    }

    @Test
    public void checkOnline_deviceAdvertisingOnlyUnrelatedUuids_isNotOnline() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getUuids()).thenReturn(new ParcelUuid[]{
                new ParcelUuid(UUID.randomUUID()),
                new ParcelUuid(UUID.randomUUID())
        });

        assertFalse(ClassicCompat.checkOnline(device));
    }

    @Test
    public void checkOnline_deviceAdvertisingTheOldMisspelledUuid_isNotOnline() {
        // If this constant were ever accidentally reverted to the misspelled seed, this test
        // documents exactly what breaks: a device advertising the *correct* service (using the
        // real "Ecclesiastes"-derived UUID that EcclesActivity/Listener use) would not be seen
        // as online, because checkOnline() would be comparing against the wrong UUID.
        UUID misspelledSeedUuid = UUID.nameUUIDFromBytes("Ecclesiates".getBytes(StandardCharsets.UTF_8));
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getUuids()).thenReturn(new ParcelUuid[]{new ParcelUuid(misspelledSeedUuid)});

        assertFalse("checkOnline() must key off the same seed the rest of the app uses to "
                        + "advertise/connect, not the historical misspelling",
                ClassicCompat.checkOnline(device));
    }

    @Test
    public void checkOnline_deviceWithNoUuids_isNotOnline() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getUuids()).thenReturn(new ParcelUuid[0]);
        assertFalse(ClassicCompat.checkOnline(device));
    }

    @Test
    public void checkOnline_getUuidsReturnsNull_doesNotThrow() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getUuids()).thenReturn(null);
        assertFalse(ClassicCompat.checkOnline(device));
    }
}
