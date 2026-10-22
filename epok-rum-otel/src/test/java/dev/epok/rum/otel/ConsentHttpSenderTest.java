package dev.epok.rum.otel;

import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.MessageWriter;
import java.io.OutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConsentHttpSenderTest {
    @Test public void closingGateCancelsActualInFlightSenderCall() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            HttpSenderConfig config = (HttpSenderConfig) Proxy.newProxyInstance(HttpSenderConfig.class.getClassLoader(),
                    new Class<?>[]{HttpSenderConfig.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getEndpoint": return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1/traces");
                            case "getContentType": return "application/x-protobuf";
                            case "getTimeout": return Duration.ofSeconds(30);
                            case "getConnectTimeout": return Duration.ofSeconds(2);
                            case "getHeadersSupplier": return (Supplier<?>) Collections::emptyMap;
                            case "getMaxResponseBodySize": return 1024L;
                            default: return null;
                        }
                    });
            ExportGate gate = new ExportGate(true);
            ConsentHttpSender sender = new ConsentHttpSender(config, gate);
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try {
                sender.send(new MessageWriter() {
                    public void writeMessage(OutputStream out) throws IOException { out.write(1); }
                    public int getContentLength() { return 1; }
                }, response -> fail("Collector intentionally sends no response"), error -> { failure.set(error); failed.countDown(); });
                try (Socket received = server.accept()) {
                    received.setSoTimeout(3000);
                    assertTrue("The request must actually be in flight", received.getInputStream().read() >= 0);
                    gate.close();
                    assertTrue("Gate must cancel without waiting for the 30-second timeout", failed.await(2, TimeUnit.SECONDS));
                    assertNotNull(failure.get());
                }
            } finally { gate.close(); sender.shutdown(); }
        }
    }
}
