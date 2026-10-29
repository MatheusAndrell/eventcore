package com.eventcore.platform.web;

import com.eventcore.platform.identity.CorrelationId;
import com.eventcore.platform.identity.UuidV7Generator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = CorrelationIdFilter.class.getName();

    private final UuidV7Generator ids;

    public CorrelationIdFilter(UuidV7Generator ids) {
        this.ids = ids;
    }

    public static CorrelationId current(HttpServletRequest request) {
        return find(request).orElseThrow(
                () -> new IllegalStateException("CorrelationIdFilter did not process this request"));
    }

    public static Optional<CorrelationId> find(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof CorrelationId value ? Optional.of(value) : Optional.empty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId correlationId = CorrelationId.parse(request.getHeader(HEADER))
                .orElseGet(() -> new CorrelationId(ids.next().toString()));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.value());
        try (var _ = MDC.putCloseable(CorrelationId.MDC_KEY, correlationId.value())) {
            chain.doFilter(request, response);
        }
    }
}
