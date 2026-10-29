package com.eventcore.platform.tracing;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class TraceContextPropagation {

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContextPropagation(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    public Map<String, String> current() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return Map.of();
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator.inject(context, carrier, Map::put);
        return Map.copyOf(carrier);
    }
}
