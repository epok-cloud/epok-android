package dev.epok.rum.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.function.Supplier;

/** Pinned SDK onEnding hook: instrumentations have finished setting their own attributes. */
@SuppressWarnings({"unchecked", "rawtypes"})
final class RumContext implements ExtendedSpanProcessor, LogRecordProcessor {
    private final Supplier<Attributes> attributes;
    private volatile boolean closed;
    RumContext(Supplier<Attributes> attributes) { this.attributes = attributes; }
    @Override public void onEnding(ReadWriteSpan span) {
        Attributes existing = span.toSpanData().getAttributes();
        safeAttributes().forEach((key, value) -> {
            if (existing.get(key) == null) span.setAttribute((io.opentelemetry.api.common.AttributeKey) key, value);
        });
    }
    @Override public void onEmit(Context context, ReadWriteLogRecord log) {
        Attributes existing = log.toLogRecordData().getAttributes();
        safeAttributes().forEach((key, value) -> {
            if (existing.get(key) == null) log.setAttribute((io.opentelemetry.api.common.AttributeKey) key, value);
        });
    }
    private Attributes safeAttributes() {
        if (closed) return Attributes.empty();
        try {
            io.opentelemetry.api.common.AttributesBuilder safe = Attributes.builder();
            attributes.get().forEach((key, value) -> {
                if (!key.getKey().startsWith("network.carrier.")) safe.put((io.opentelemetry.api.common.AttributeKey) key, value);
            });
            return safe.build();
        } catch (VirtualMachineError error) { throw error; }
        catch (Throwable ignored) { return Attributes.empty(); }
    }
    @Override public boolean isOnEndingRequired() { return true; }
    @Override public boolean isStartRequired() { return false; }
    @Override public boolean isEndRequired() { return false; }
    @Override public void onStart(Context context, ReadWriteSpan span) {}
    @Override public void onEnd(ReadableSpan span) {}
    @Override public CompletableResultCode shutdown() { closed = true; return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    @Override public void close() { shutdown(); }
}
