package com.eventcore.catalog.infrastructure;

import com.eventcore.catalog.application.DataSchema;
import com.networknt.schema.Schema;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class NetworkntDataSchema implements DataSchema {

    private final Schema schema;

    NetworkntDataSchema(Schema schema) {
        this.schema = schema;
    }

    @Override
    public List<String> violations(JsonNode data) {
        return schema.validate(data).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }
}
