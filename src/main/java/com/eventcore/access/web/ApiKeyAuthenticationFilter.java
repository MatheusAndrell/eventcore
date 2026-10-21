package com.eventcore.access.web;

import com.eventcore.access.application.ApiKeyAuthenticator;
import com.eventcore.access.application.AuthenticatedClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyAuthenticator authenticator;
    private final AuthenticationEntryPoint entryPoint;

    ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, AuthenticationEntryPoint entryPoint) {
        this.authenticator = authenticator;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AuthenticatedClient> client = authorization.regionMatches(true, 0, BEARER_PREFIX, 0,
                BEARER_PREFIX.length())
                ? authenticator.authenticate(authorization.substring(BEARER_PREFIX.length()).strip())
                : Optional.empty();
        if (client.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("invalid API key"));
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthentication(client.get()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
