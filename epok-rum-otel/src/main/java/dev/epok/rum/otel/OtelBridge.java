package dev.epok.rum.otel;

import android.app.Application;
import android.content.Context;
import io.opentelemetry.android.OpenTelemetryRumBuilder;
import io.opentelemetry.android.instrumentation.AndroidInstrumentation;
import java.util.ArrayList;
import java.util.List;
import io.opentelemetry.android.OpenTelemetryRum;
import io.opentelemetry.android.RumBuilder;
import io.opentelemetry.android.AndroidResource;
import io.opentelemetry.android.agent.session.SessionConfig;
import io.opentelemetry.android.agent.session.SessionIdTimeoutHandler;
import io.opentelemetry.android.agent.session.SessionManager;
import io.opentelemetry.android.config.OtelRumConfig;
import io.opentelemetry.android.features.diskbuffering.DiskBufferingConfig;
import io.opentelemetry.android.features.diskbuffering.SignalFromDiskExporter;
import io.opentelemetry.android.features.diskbuffering.scheduler.ScheduleEnablement;
import io.opentelemetry.android.internal.services.Services;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.instrumentation.library.okhttp.internal.OkHttpSingletons;
import kotlin.Unit;
import okhttp3.Interceptor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import android.os.Handler;
import android.os.Looper;

/**
 * Small Java ABI bridge: upstream 1.7 carries Kotlin 2.4 metadata, while our public
 * API and implementation compile with Kotlin 2.1. No metadata-check bypass is used.
 * All calls here are confined to the pinned upstream version.
 */
final class OtelBridge implements RumRuntime {
    private final ExportGate gate = new ExportGate(false);
    private final DiskSchedule schedule;
    private final Application app;
    private final EpokRumConfig config;
    private final Thread.UncaughtExceptionHandler hostCrashHandler;
    private OpenTelemetryRum rum;
    private OpenTelemetrySdk sdk;
    private final StartupParents startupParents = new StartupParents();
    private PrivateSpans spans;
    private PrivateLogs logs;
    private volatile boolean closed;
    private boolean stopped;
    private final Object mainTaskLock = new Object();
    private FutureTask<Void> mainTask;
    private Services services;
    private final List<InstalledInstrumentation> installed = new ArrayList<>();

    OtelBridge(Application app, EpokRumConfig config) {
        this.app = app;
        this.config = config;
        this.hostCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        this.schedule = new DiskSchedule(() -> ConsentStorage.INSTANCE.purge(app));
    }

    @Override public synchronized void initialize() {
        if (closed) return;
        initializeSdk();
    }

    private void initializeSdk() {
        String endpoint = config.getEndpoint().replaceAll("/+$", "");
        spans = new PrivateSpans(OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint + "/v1/traces").addHeader("X-API-Key", config.getIngestKey())
                .setComponentLoader(ConsentHttpSender.loader(gate))
                .setTimeout(5, TimeUnit.SECONDS).setRetryPolicy(null).setCompression("gzip").build(), gate, startupParents);
        logs = new PrivateLogs(OtlpHttpLogRecordExporter.builder()
                .setEndpoint(endpoint + "/v1/logs").addHeader("X-API-Key", config.getIngestKey())
                .setComponentLoader(ConsentHttpSender.loader(gate))
                .setTimeout(5, TimeUnit.SECONDS).setRetryPolicy(null).setCompression("gzip").build(), gate);
        OtelRumConfig options = rumConfig();
        Clock clock = Clock.getDefault();
        SessionConfig sessions = SessionConfig.withDefaults();
        SessionIdTimeoutHandler timeout = new SessionIdTimeoutHandler(sessions, clock);
        onMain(() -> {
            services = Services.get(app);
            services.getAppLifecycle().registerListener(timeout);
        });
        Resource resource = Privacy.INSTANCE.resource(AndroidResource.createDefault(app)).toBuilder()
                .put("rum", true).put("telemetry.sdk.name", "epok-rum-android")
                .put("epok.rum.version", BuildConfig.EPOK_RUM_VERSION)
                .put("telemetry.sdk.version", BuildConfig.EPOK_RUM_VERSION)
                .put("epok.otel.version", "1.7.0")
                .put("service.name", config.getServiceName())
                .put("service.version", serviceVersion(app, config)).build();
        // Capture once during initialization; late records must never re-register a provider.
        final var networkProvider =
                io.opentelemetry.android.internal.services.network.NetworkProviderHolder.get(app);
        java.util.function.Supplier<io.opentelemetry.api.common.Attributes> contextAttributes = () -> {
            if (closed) return io.opentelemetry.api.common.Attributes.empty();
            io.opentelemetry.api.common.Attributes network = new io.opentelemetry.android.common.internal.features.networkattributes.CurrentNetworkAttributesExtractor()
                    .extract(networkProvider.getCurrentNetwork());
            return network.toBuilder().put("app.screen.name", services.getVisibleScreenTracker().getCurrentlyVisibleScreen()).build();
        };
        OpenTelemetryRumBuilder builder = RumBuilder.builder(app, options.disableInstrumentationDiscovery())
                .setClock(clock).setResource(resource)
                .setSessionProvider(SessionManager.create(timeout, sessions, clock))
                .setExportScheduleEnablement(schedule)
                .addTracerProviderCustomizer((provider, context) -> {
                    addRumContext(provider, null, contextAttributes);
                    return provider.addSpanProcessor(startupParents);
                })
                .addLoggerProviderCustomizer((provider, context) -> {
                    addRumContext(null, provider, contextAttributes);
                    return provider;
                })
                .addSpanExporterCustomizer(ignored -> spans)
                .addLogRecordExporterCustomizer(ignored -> logs)
                .addOtelReadyListener(otel -> { sdk = (OpenTelemetrySdk) otel; return Unit.INSTANCE; });
        for (AndroidInstrumentation instrumentation : builder.getInstrumentationLoader().getAll()) {
            InstalledInstrumentation tracked = new InstalledInstrumentation(instrumentation);
            installed.add(tracked);
            builder.addInstrumentation(tracked);
        }
        try { rum = builder.build(); }
        finally {
            // Also fence a partial build that installed CrashFlushHandler before failing.
            Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
            if (handler != hostCrashHandler) fenceCrashHandler(handler);
        }
        // Await the SDK's asynchronous disk initializer. Otherwise immediate withdrawal
        // can delete the buffer just before that initializer recreates it.
        schedule.awaitReady();
    }

    @Override public synchronized void enable() {
        if (!closed) gate.enable();
    }

    @Override public synchronized void recordColdStart(long start, long end, String activity) {
        if (!closed && rum != null) rum.getOpenTelemetry().getTracer("dev.epok.rum.otel")
                .spanBuilder("app.start").setNoParent().setAttribute("start.type", "cold").setAttribute("app.start.activity", activity)
                .setStartTimestamp(start, TimeUnit.NANOSECONDS).startSpan().end(end, TimeUnit.NANOSECONDS);
    }

    static void addRumContext(io.opentelemetry.sdk.trace.SdkTracerProviderBuilder traces,
            io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder logs,
            java.util.function.Supplier<io.opentelemetry.api.common.Attributes> attributes) {
        RumContext processor = new RumContext(attributes);
        if (traces != null) traces.addSpanProcessor(processor);
        // Must run before the upstream batch processor takes its immutable snapshot.
        if (logs != null) logs.addLogRecordProcessorFirst(processor);
    }

    static OtelRumConfig rumConfig() {
        // Generic decorators overwrite the instrumentations' own attributes. SDK 1.65 counts
        // replacements as drops; RumContext restores their effect with set-if-absent writes.
        return new OtelRumConfig().setMetricsDisabled().disableScreenAttributes().disableNetworkAttributes()
                .setDiskBufferingConfig(DiskBufferingConfig.create(true));
    }

    private static String serviceVersion(Application app, EpokRumConfig config) {
        if (config.getServiceVersion() != null) return config.getServiceVersion();
        try {
            String version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
            return version == null ? "unknown" : version;
        } catch (Exception ignored) { return "unknown"; }
    }

    // Upstream ProcessLifecycleOwner registration requires the main thread. Only this
    // small lifecycle step runs there; SDK/exporter construction and readiness stay off it.
    private void onMain(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            long began = android.os.SystemClock.elapsedRealtimeNanos();
            try { action.run(); }
            finally { android.util.Log.i("EpokRum", "main_task_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1e6); }
        }, null);
        synchronized (mainTaskLock) {
            if (closed) throw new IllegalStateException("Initialization cancelled");
            mainTask = task;
            new Handler(Looper.getMainLooper()).post(task);
        }
        try { task.get(10, TimeUnit.SECONDS); }
        catch (Exception error) { task.cancel(false); throw new IllegalStateException("Lifecycle initialization failed", error); }
        finally { synchronized (mainTaskLock) { mainTask = null; } }
    }

    @Override public void deactivate() {
        closed = true;
        gate.close();
        synchronized (mainTaskLock) { if (mainTask != null) mainTask.cancel(false); }
    }

    @Override public void shutdown() {
        deactivate();
        // Cancel a queued main-thread step BEFORE waiting for initialize's lock. This lets
        // withdrawal from the main thread cancel initialization without a lock inversion.
        synchronized (mainTaskLock) {
            closed = true;
            if (mainTask != null) mainTask.cancel(false);
        }
        gate.close();
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            schedule.disable();
            // Detach lifecycle instrumentation on the main thread while its provider is still
            // alive. Activity callbacks already dispatched by Android may run before this task.
            // Shutting the provider first races ExtendedSdkTracer's no-op span-builder cast.
            FutureTask<Void> detach = new FutureTask<>(() -> {
                long began = android.os.SystemClock.elapsedRealtimeNanos();
                try { for (InstalledInstrumentation instrumentation : installed) instrumentation.close(); }
                finally { android.util.Log.i("EpokRum", "main_cleanup_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1e6); }
            }, null);
            if (Looper.myLooper() == Looper.getMainLooper()) detach.run();
            else new Handler(Looper.getMainLooper()).post(detach);
            // Only the cleanup worker waits; withdrawal has already fenced and returned.
            // Do not time out and shut the provider down while callbacks are still registered.
            boolean interrupted = false;
            for (;;) {
                try { detach.get(); break; }
                catch (InterruptedException error) { interrupted = true; }
                catch (java.util.concurrent.ExecutionException error) {
                    android.util.Log.e("EpokRum", "Lifecycle detach failed", error); break;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            try {
                if (sdk != null) sdk.shutdown().join(10, TimeUnit.SECONDS);
            } finally {
                Runnable cleanup = () -> {
                    long began = android.os.SystemClock.elapsedRealtimeNanos();
                    try {
                        if (rum != null) rum.shutdown();
                        else if (services != null) services.close();
                    } catch (RuntimeException | LinkageError error) { android.util.Log.e("EpokRum", "Lifecycle cleanup failed", error); }
                    finally { android.util.Log.i("EpokRum", "main_cleanup_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1e6); }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run();
                else new Handler(Looper.getMainLooper()).post(cleanup);
                if (spans != null) spans.shutdown().join(10, TimeUnit.SECONDS);
                if (logs != null) logs.shutdown().join(10, TimeUnit.SECONDS);
            }
        }
    }

    @Override public Interceptor connectionInterceptor() {
        Interceptor delegate = OkHttpSingletons.connectionErrorInterceptor;
        io.opentelemetry.context.Context resendContext =
                io.opentelemetry.instrumentation.api.semconv.http.HttpClientRequestResendCount
                        .initialize(io.opentelemetry.context.Context.current());
        return HostInterceptors.scoped(() -> closed, delegate, resendContext);
    }
    @Override public Interceptor networkInterceptor() {
        return HostInterceptors.scoped(() -> closed, OkHttpSingletons.tracingInterceptor, null);
    }

    void fenceCrashHandler(Thread.UncaughtExceptionHandler handler) {
        // Bypass the entire SDK chain when closed, including upstream CrashFlushHandler.
        // Calling the immediately preceding handler would still flush SDK disk buffers.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (!closed && gate.isOpen()) handler.uncaughtException(thread, error);
            else if (hostCrashHandler != null) hostCrashHandler.uncaughtException(thread, error);
        });
    }

    private final class InstalledInstrumentation implements AndroidInstrumentation {
        private final AndroidInstrumentation delegate;
        private Context context;
        private OpenTelemetryRum owner;
        InstalledInstrumentation(AndroidInstrumentation delegate) { this.delegate = delegate; }
        @Override public String getName() { return delegate.getName(); }
        @Override public void install(Context context, OpenTelemetryRum owner) {
            this.context = context;
            this.owner = owner;
            Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
            try { delegate.install(context, owner); }
            finally {
                Thread.UncaughtExceptionHandler after = Thread.getDefaultUncaughtExceptionHandler();
                if (after != before) {
                    fenceCrashHandler(after);
                }
            }
        }
        @Override public void uninstall(Context context, OpenTelemetryRum owner) { close(); }
        void close() {
            if (owner == null) return;
            try { delegate.uninstall(context, owner); }
            catch (RuntimeException | LinkageError error) { android.util.Log.e("EpokRum", "Instrumentation cleanup failed", error); }
            finally { owner = null; context = null; }
        }
    }

    static final class DiskSchedule implements ScheduleEnablement {
        private final CountDownLatch ready = new CountDownLatch(1);
        private ScheduledExecutorService executor;
        private boolean stopped;
        private final Runnable lateCleanup;
        DiskSchedule(Runnable lateCleanup) { this.lateCleanup = lateCleanup; }
        @Override public synchronized void enable() {
            if (!stopped) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "epok-otel-disk"); t.setDaemon(true); return t;
                });
                executor.scheduleWithFixedDelay(() -> {
                    try {
                        SignalFromDiskExporter reader = SignalFromDiskExporter.get();
                        if (reader != null) exportSignals(reader::exportSpansFromDisk,
                                reader::exportLogsFromDisk, reader::exportMetricsFromDisk);
                    } catch (RuntimeException | LinkageError error) {
                        // ScheduledExecutorService otherwise silently cancels all future runs.
                        android.util.Log.e("EpokRum", "Disk export failed", error);
                    }
                }, 10, 10, TimeUnit.SECONDS);
            }
            if (stopped) lateCleanup.run();
            ready.countDown();
        }
        static void exportSignals(Runnable... exporters) {
            for (Runnable exporter : exporters) {
                try { exporter.run(); }
                catch (RuntimeException | LinkageError error) {
                    android.util.Log.e("EpokRum", "Disk export failed", error);
                }
            }
        }
        @Override public synchronized void disable() {
            stopped = true;
            if (executor != null) executor.shutdownNow();
        }
        void awaitReady() { awaitReady(10, TimeUnit.SECONDS); }
        void awaitReady(long timeout, TimeUnit unit) {
            try {
                if (!ready.await(timeout, unit)) throw new IllegalStateException("OTel disk initialization timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
    }
}
