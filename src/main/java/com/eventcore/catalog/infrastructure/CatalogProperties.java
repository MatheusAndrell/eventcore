package com.eventcore.catalog.infrastructure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.catalog")
public record CatalogProperties(@DefaultValue("classpath:eventcore/catalog/") @NotBlank String location) {
}
