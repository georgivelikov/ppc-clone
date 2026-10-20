package dev.cutpay.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Carries a trace across the outbox. The row stores the W3C {@code traceparent} of whoever wrote the
 * event; the relay, which runs on its own thread long after that request ended, starts its publish
 * span as a child of it, so one trace reads "Pay" click, payments, Kafka, ledger.
 */
@SuppressWarnings("try")
public class TraceLink {

    private static final String HEADER = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TraceLink(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current span as a {@code traceparent} value, or null when nothing is being traced. */
    public String current() {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext c = span.context();
        return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.FALSE.equals(c.sampled()) ? "00" : "01");
    }

    /** Runs {@code work} inside a span that continues {@code traceparent}; plain call when there is none. */
    public void within(String name, String traceparent, Runnable work) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || traceparent == null) {
            work.run();
            return;
        }
        Span span = p.extract(Map.of(HEADER, traceparent), Map<String, String>::get).name(name).start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            work.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
