package dev.epok.rum.otel;

import io.opentelemetry.api.common.*;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.logs.*;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.*;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class RumContextTest {
    @Test public void supplierFailuresAndShutdownCannotBreakHostOrLeakCarrier() {
        for (Throwable failure : new Throwable[]{new IllegalStateException("provider"), new LinkageError("provider")}) {
            RumContext context = new RumContext(() -> { if (failure instanceof Error) throw (Error) failure; throw (RuntimeException) failure; });
            InMemorySpanExporter spans = InMemorySpanExporter.create();
            InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
            try (SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(context).addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
                 SdkLoggerProvider lp = SdkLoggerProvider.builder().addLogRecordProcessor(context).addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()) {
                try {
                    tp.get("host").spanBuilder("GET").startSpan().end();
                    lp.get("host").logRecordBuilder().setEventName("app.crash").emit();
                } catch (Throwable error) { fail("Supplier failure escaped into host: " + error); }
                assertEquals(1, spans.getFinishedSpanItems().size()); assertEquals(1, logs.getFinishedLogRecordItems().size());
            }
        }
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RumContext context = new RumContext(() -> { calls.incrementAndGet(); return Attributes.builder().put("network.carrier.name", "private").put("app.screen.name", "Main").build(); });
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
        try (SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(context).addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
             SdkLoggerProvider lp = SdkLoggerProvider.builder().addLogRecordProcessor(context).addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()) {
            tp.get("host").spanBuilder("before").startSpan().end(); lp.get("host").logRecordBuilder().emit();
            assertNull(spans.getFinishedSpanItems().get(0).getAttributes().get(AttributeKey.stringKey("network.carrier.name")));
            assertNull(logs.getFinishedLogRecordItems().get(0).getAttributes().get(AttributeKey.stringKey("network.carrier.name")));
            context.shutdown(); int count = calls.get();
            tp.get("host").spanBuilder("after").startSpan().end(); lp.get("host").logRecordBuilder().emit();
            assertEquals("Closed processor must never query providers", count, calls.get());
        }
    }
    @Test public void virtualMachineErrorsPropagate() {
        RumContext context = new RumContext(() -> { throw new OutOfMemoryError("test"); });
        try (SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(context).build()) {
            try { tp.get("host").spanBuilder("GET").startSpan().end(); fail("VM error swallowed"); }
            catch (OutOfMemoryError expected) { }
        }
    }

    @Test public void crashAnrJankAndHttpKeepContextWithoutOverwrites() throws Exception {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
        SdkTracerProviderBuilder trace = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans));
        SdkLoggerProviderBuilder log = SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs));
        java.lang.reflect.Method install = null;
        for (java.lang.reflect.Method m : OtelBridge.class.getDeclaredMethods()) if (m.getName().equals("addRumContext")) install = m;
        assertNotNull("Production bridge must install set-if-absent context processors", install);
        Supplier<Attributes> context = () -> Attributes.builder().put("app.screen.name", "Main")
            .put("network.connection.type", "wifi").put("network.connection.subtype", "test").put("network.carrier.name", "private").build();
        install.invoke(null, trace, log, context);
        try (SdkTracerProvider tp = trace.build(); SdkLoggerProvider lp = log.build()) {
            for (String event : new String[]{"app.crash", "device.anr", "app.jank"})
                lp.get("test").logRecordBuilder().setEventName(event).emit();
            io.opentelemetry.api.trace.Span http = tp.get("okhttp").spanBuilder("GET").startSpan();
            http.setAttribute("app.screen.name", "instrumentation"); http.end();
            lp.get("test").logRecordBuilder().setEventName("owned").setAttribute(AttributeKey.stringKey("network.connection.type"), "cell").emit();
            assertEquals(4, logs.getFinishedLogRecordItems().size());
            for (io.opentelemetry.sdk.logs.data.LogRecordData row : logs.getFinishedLogRecordItems()) {
                assertEquals("Main", row.getAttributes().get(AttributeKey.stringKey("app.screen.name")));
                assertEquals(row.getEventName().equals("owned") ? "cell" : "wifi", row.getAttributes().get(AttributeKey.stringKey("network.connection.type")));
                assertEquals("test", row.getAttributes().get(AttributeKey.stringKey("network.connection.subtype")));
                assertEquals(row.getAttributes().size(), row.getTotalAttributeCount());
            }
            io.opentelemetry.sdk.trace.data.SpanData row = spans.getFinishedSpanItems().get(0);
            assertEquals("instrumentation", row.getAttributes().get(AttributeKey.stringKey("app.screen.name")));
            assertEquals("wifi", row.getAttributes().get(AttributeKey.stringKey("network.connection.type")));
            assertEquals("test", row.getAttributes().get(AttributeKey.stringKey("network.connection.subtype")));
            assertEquals(row.getAttributes().size(), row.getTotalAttributeCount());
        }
    }
}
