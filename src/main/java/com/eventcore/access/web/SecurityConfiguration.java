package com.eventcore.access.web;

import com.eventcore.access.application.ApiKeyAuthenticator;
import com.eventcore.access.domain.Scope;
import com.eventcore.platform.web.HttpProperties;
import com.eventcore.platform.web.ProblemResponseWriter;
import com.eventcore.platform.web.Problems;
import com.eventcore.platform.web.RequestBodyLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticator authenticator,
            ProblemResponseWriter problems,
            HttpProperties httpProperties) {
        AuthenticationEntryPoint entryPoint = unauthorized(problems);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(authenticator, entryPoint),
                        AnonymousAuthenticationFilter.class)
                .addFilterAfter(new RequestBodyLimitFilter(httpProperties, problems), AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(forbidden(problems)))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/events").hasAuthority(authority(Scope.EVENTS_WRITE))
                        .requestMatchers(HttpMethod.GET, "/v1/events", "/v1/events/*")
                        .hasAuthority(authority(Scope.EVENTS_READ))
                        .requestMatchers(HttpMethod.GET, "/v1/events/*/deliveries", "/v1/deliveries/*")
                        .hasAuthority(authority(Scope.EVENTS_READ))
                        .requestMatchers(HttpMethod.POST, "/v1/deliveries/*/retry")
                        .hasAuthority(authority(Scope.SUBSCRIPTIONS_MANAGE))
                        .requestMatchers("/v1/subscriptions", "/v1/subscriptions/**")
                        .hasAuthority(authority(Scope.SUBSCRIPTIONS_MANAGE))
                        .requestMatchers("/admin/**").hasAuthority(authority(Scope.ADMIN))
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    private static String authority(Scope scope) {
        return ApiKeyAuthentication.authority(scope);
    }

    private static AuthenticationEntryPoint unauthorized(ProblemResponseWriter problems) {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problems.write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "a valid API key is required"));
        };
    }

    private static AccessDeniedHandler forbidden(ProblemResponseWriter problems) {
        return (request, response, exception) -> problems.write(request, response, Problems.of(
                HttpStatus.FORBIDDEN, "insufficient-scope", "Forbidden",
                "the API key does not grant access to this operation"));
    }
}
