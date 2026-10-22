package dev.epok.rum.otel;

import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.MessageWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Per-runtime sender: cancellation never affects the host app's OkHttp clients. */
final class ConsentHttpSender implements HttpSender {
    private final HttpSenderConfig config;
    private final ExportGate gate;
    private final OkHttpClient client;
    ConsentHttpSender(HttpSenderConfig config, ExportGate gate) {
        this.config = config;
        this.gate = gate;
        client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .connectTimeout(config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }
    static ComponentLoader loader(ExportGate gate) {
        return new ComponentLoader() {
            @Override public <T> Iterable<T> load(Class<T> type) {
                if (type == HttpSenderProvider.class) return Collections.singletonList(
                        type.cast((HttpSenderProvider) config -> new ConsentHttpSender(config, gate)));
                return ComponentLoader.forClassLoader(ConsentHttpSender.class.getClassLoader()).load(type);
            }
        };
    }
    @Override public void send(MessageWriter writer, Consumer<HttpResponse> response, Consumer<Throwable> error) {
        try {
            // Serialization is outside the admission lock. Withdrawal may cancel it before enqueue.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (config.getCompressor() != null) {
                try (OutputStream compressed = config.getCompressor().compress(bytes)) { writer.writeMessage(compressed); }
            } else writer.writeMessage(bytes);
            Request.Builder request = new Request.Builder().url(config.getEndpoint().toString())
                    .post(RequestBody.create(MediaType.parse(config.getContentType()), bytes.toByteArray()));
            for (Map.Entry<String, List<String>> h : config.getHeadersSupplier().get().entrySet())
                for (String value : h.getValue()) request.addHeader(h.getKey(), value);
            if (config.getCompressor() != null) request.header("Content-Encoding", config.getCompressor().getEncoding());
            request.header("X-Epok-Sender", "consent-http-v1");
            Call call = client.newCall(request.build());
            gate.enqueue(call, new Callback() {
                @Override public void onFailure(Call call, IOException failure) { gate.completed(call); error.accept(failure); }
                @Override public void onResponse(Call call, Response received) {
                    try (Response r = received) {
                        final int code = r.code();
                        final String message = r.message();
                        final byte[] body = r.body() == null ? new byte[0] : r.peekBody(config.getMaxResponseBodySize()).bytes();
                        response.accept(new HttpResponse() {
                            public int getStatusCode() { return code; }
                            public String getStatusMessage() { return message; }
                            public byte[] getResponseBody() { return body; }
                        });
                    } catch (Throwable failure) { error.accept(failure); }
                    finally { gate.completed(call); }
                }
            });
        } catch (Throwable failure) { error.accept(failure); }
    }
    @Override public CompletableResultCode shutdown() {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        return CompletableResultCode.ofSuccess();
    }
}
