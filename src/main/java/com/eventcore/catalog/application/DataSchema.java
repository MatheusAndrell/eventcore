package com.eventcore.catalog.application;

import java.util.List;
import tools.jackson.databind.JsonNode;

public interface DataSchema {

    List<String> violations(JsonNode data);
}
