package com.eventcore.platform.web;

import com.eventcore.platform.identity.UuidV7Generator;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class PlatformWebConfiguration {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter(UuidV7Generator ids) {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter(ids));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    ProblemResponseWriter problemResponseWriter(JsonMapper mapper) {
        return new ProblemResponseWriter(mapper);
    }

    @Bean
    OpenAPI eventcoreOpenApi() {
        return new OpenAPI()
                .info(new Info().title("EventCore API").version("v1"))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("EventCore API key in the format ec_<keyId>_<secret>")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
