package dev.epok.rum.otel;

import android.app.Application;
import io.opentelemetry.android.OpenTelemetryRum;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ColdStartParentTest {
    @Test public void coldStartNeverInheritsCurrentSpan() throws Exception {
        InMemorySpanExporter sink = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(sink)).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        OtelBridge bridge = new OtelBridge((Application) RuntimeEnvironment.getApplication(),
                new EpokRumConfig("http://127.0.0.1:9", "dummy", "unit", "1"));
        OpenTelemetryRum rum = (OpenTelemetryRum) Proxy.newProxyInstance(OpenTelemetryRum.class.getClassLoader(),
                new Class<?>[]{OpenTelemetryRum.class}, (proxy, method, args) -> sdk);
        Field field = OtelBridge.class.getDeclaredField("rum"); field.setAccessible(true); field.set(bridge, rum);
        Span active = provider.get("test").spanBuilder("Created").startSpan();
        try (Scope ignored = active.makeCurrent()) { bridge.recordColdStart(1000, 2000, "MainActivity"); }
        assertFalse(sink.getFinishedSpanItems().get(0).getParentSpanContext().isValid());
        active.end(); provider.close();
    }
}
