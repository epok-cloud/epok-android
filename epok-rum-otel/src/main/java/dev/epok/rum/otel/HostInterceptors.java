package dev.epok.rum.otel;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import okhttp3.Interceptor;
import okhttp3.Response;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/** Named forwarding frames, excluded from init crash attribution even after R8. */
final class HostInterceptors {
    static Interceptor lookup(Supplier<Interceptor> supplier) { return new Lookup(supplier); }
    static Interceptor scoped(BooleanSupplier closed, Interceptor delegate, Context context) {
        return new Scoped(closed, delegate, context);
    }
    static final class Lookup implements Interceptor {
        private final Supplier<Interceptor> supplier;
        Lookup(Supplier<Interceptor> supplier) { this.supplier = supplier; }
        @Override public Response intercept(Chain chain) throws IOException {
            Interceptor delegate = supplier.get();
            return delegate == null ? chain.proceed(chain.request()) : delegate.intercept(chain);
        }
    }
    static final class Scoped implements Interceptor {
        private final BooleanSupplier closed;
        private final Interceptor delegate;
        private final Context context;
        Scoped(BooleanSupplier closed, Interceptor delegate, Context context) {
            this.closed = closed; this.delegate = delegate; this.context = context;
        }
        @Override public Response intercept(Chain chain) throws IOException {
            if (closed.getAsBoolean()) return chain.proceed(chain.request());
            if (context == null) return delegate.intercept(chain);
            // Match upstream resend-count scope to avoid duplicate successful HTTP spans.
            try (Scope ignored = context.makeCurrent()) { return delegate.intercept(chain); }
        }
    }
}
