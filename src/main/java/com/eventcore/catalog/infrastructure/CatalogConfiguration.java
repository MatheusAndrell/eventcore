package com.eventcore.catalog.infrastructure;

import com.eventcore.catalog.application.EventCatalog;
import com.eventcore.platform.json.StrictJsonReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    @Bean
    EventCatalog eventCatalog(ResourceLoader resourceLoader, StrictJsonReader reader, CatalogProperties properties) {
        return new CatalogLoader(resourceLoader, reader).load(properties.location());
    }
}
