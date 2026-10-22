package dev.epok.rum.otel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.robolectric.shadows.ShadowLog;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DiskIsolationTest {
    @Test public void diskFailureIsLoggedWithCause() {
        ShadowLog.clear();
        UnsupportedOperationException failure = new UnsupportedOperationException("recovered record");
        OtelBridge.DiskSchedule.exportSignals(() -> { throw failure; });
        assertTrue(ShadowLog.getLogsForTag("EpokRum").stream().anyMatch(item ->
                item.type == android.util.Log.ERROR && "Disk export failed".equals(item.msg)
                        && item.throwable == failure));
    }
    @Test public void eachSignalStillRunsWhenAnotherThrows() {
        for (int failing = 0; failing < 3; failing++) {
            final int failure = failing;
            List<Integer> calls = new ArrayList<>();
            Runnable[] signals = new Runnable[3];
            for (int i = 0; i < 3; i++) {
                final int index = i;
                signals[i] = () -> {
                    calls.add(index);
                    if (index == failure) throw new UnsupportedOperationException("recovered record");
                };
            }
            OtelBridge.DiskSchedule.exportSignals(signals);
            assertEquals(Arrays.asList(0, 1, 2), calls);
        }
    }
}
