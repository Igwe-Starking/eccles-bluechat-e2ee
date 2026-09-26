package starking.eccles.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link FriendlyDate} calls {@code android.util.Base64} and {@code android.util.Log}, which are
 * stubbed out (throw "not mocked") under a plain JVM unit test. Robolectric provides real
 * implementations of those Android SDK classes on the local JVM so these tests can run fast,
 * without a device/emulator, in the same {@code ./gradlew test} pass as the rest of the suite.
 */
@RunWith(RobolectricTestRunner.class)
public class FriendlyDateTest {

    @Test
    public void formatThenSimplify_veryRecentTimestamp_isJustNowOrAMinuteAgo() {
        // format() only stores minute granularity (no seconds), so a call made right before a
        // minute boundary and read back right after can legitimately round up to "a minute ago"
        // even though almost no real time passed; assert on the set of acceptable outcomes
        // rather than the wall-clock-timing-dependent exact string to keep this deterministic.
        String stored = FriendlyDate.format();
        String result = FriendlyDate.simplify(stored);
        assertTrue("expected 'just now' or 'a minute ago' but was: " + result,
                "just now".equals(result) || "a minute ago".equals(result));
    }

    @Test
    public void simplify_ofGarbageInput_returnsNullRatherThanThrowing() {
        assertEquals(null, FriendlyDate.simplify("not valid base64/json at all"));
    }

    @Test
    public void relative_bucketsBySeconds_asJustNow() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 0, 0);
        LocalDateTime now = stored.plusSeconds(45);
        assertEquals("just now", FriendlyDate.relative(stored, now));
    }

    @Test
    public void relative_oneMinute_isSingular() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 0);
        assertEquals("a minute ago", FriendlyDate.relative(stored, stored.plusMinutes(1)));
    }

    @Test
    public void relative_fiveMinutes_isPlural() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 0);
        assertEquals("5 minutes ago", FriendlyDate.relative(stored, stored.plusMinutes(5)));
    }

    @Test
    public void relative_oneHour_isSingular() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 0);
        assertEquals("an hour ago", FriendlyDate.relative(stored, stored.plusHours(1)));
    }

    @Test
    public void relative_threeHours_isPlural() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 0);
        assertEquals("3 hours ago", FriendlyDate.relative(stored, stored.plusHours(3)));
    }

    @Test
    public void relative_yesterday() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 0);
        assertEquals("yesterday", FriendlyDate.relative(stored, stored.plusDays(1).plusMinutes(1)));
    }

    @Test
    public void relative_fiveDaysAgo() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 10, 10, 0);
        assertEquals("5 days ago", FriendlyDate.relative(stored, stored.plusDays(5).plusHours(1)));
    }

    @Test
    public void relative_lastMonth() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 1, 10, 0);
        assertEquals("last month", FriendlyDate.relative(stored, stored.plusDays(31)));
    }

    @Test
    public void relative_lastYear() {
        LocalDateTime stored = LocalDateTime.of(2025, 1, 1, 10, 0);
        assertEquals("last year", FriendlyDate.relative(stored, stored.plusDays(366)));
    }

    /**
     * Regression test for the bug in the original implementation: it compared year/month/day
     * fields independently and returned on the first field where "now" was greater, so a
     * message sent two minutes before midnight on New Year's Eve, read back two minutes after
     * midnight, was reported as "last year" — a message sent seconds ago must never be reported
     * as anything other than "just now".
     */
    @Test
    public void relative_acrossNewYearBoundary_stillReportsJustNow() {
        LocalDateTime stored = LocalDateTime.of(2025, 12, 31, 23, 59, 30);
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 15); // 45 seconds later
        assertEquals("just now", FriendlyDate.relative(stored, now));
    }

    @Test
    public void relative_acrossNewYearBoundary_twoMinutesLater_isNotLastYear() {
        LocalDateTime stored = LocalDateTime.of(2025, 12, 31, 23, 59, 0);
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 1, 0); // 2 minutes later
        assertEquals("2 minutes ago", FriendlyDate.relative(stored, now));
    }

    @Test
    public void relative_acrossMonthBoundary_smallGapNotReportedAsMonthsAgo() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 31, 23, 0);
        LocalDateTime now = LocalDateTime.of(2026, 2, 1, 0, 5); // 65 minutes later
        assertEquals("an hour ago", FriendlyDate.relative(stored, now));
    }

    @Test
    public void relative_futureTimestamp_clockSkewDoesNotProduceNegativeAgo() {
        LocalDateTime stored = LocalDateTime.of(2026, 1, 15, 10, 5);
        LocalDateTime now = LocalDateTime.of(2026, 1, 15, 10, 0); // "now" is before "stored"
        assertEquals("just now", FriendlyDate.relative(stored, now));
    }

    @Test
    public void simplifyNoArg_returnsNonNullTimestampToken() {
        assertNotNull(FriendlyDate.simplify());
    }
}
