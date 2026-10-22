package dev.epok.rum.otel;

import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeContractTest {
    @Test public void metricsCollectionDisabledAndDiskBufferEnabled() {
        assertFalse(OtelBridge.rumConfig().getMetricsEnabled());
        assertTrue(OtelBridge.rumConfig().getTracingEnabled());
        assertTrue(OtelBridge.rumConfig().getLoggingEnabled());
        assertTrue(OtelBridge.rumConfig().getDiskBufferingConfig().getEnabled());
    }
    @Test public void duplicateAttributeDecoratorsAreDisabled() {
        assertFalse(OtelBridge.rumConfig().shouldIncludeScreenAttributes());
        assertFalse(OtelBridge.rumConfig().shouldIncludeNetworkAttributes());
    }
}
