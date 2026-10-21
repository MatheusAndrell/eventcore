package com.eventcore.platform.json;

import java.io.InputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class StrictJsonReader {

    public static final int MAX_NESTING_DEPTH = 32;
    public static final int MAX_STRING_LENGTH = 64 * 1024;
    public static final int MAX_NUMBER_LENGTH = 100;

    private final JsonMapper mapper;

    public StrictJsonReader() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public JsonNode read(byte[] json) {
        try {
            return requirePresent(mapper.readTree(json));
        } catch (JacksonException exception) {
            throw new InvalidJsonException(describe(exception), exception);
        }
    }

    public JsonNode read(InputStream json) {
        try {
            return requirePresent(mapper.readTree(json));
        } catch (JacksonException exception) {
            throw new InvalidJsonException(describe(exception), exception);
        }
    }

    private static JsonNode requirePresent(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            throw new InvalidJsonException("document is empty");
        }
        return node;
    }

    private static String describe(JacksonException exception) {
        if (exception.getLocation() == null) {
            return exception.getOriginalMessage();
        }
        return exception.getOriginalMessage()
                + " (line " + exception.getLocation().getLineNr()
                + ", column " + exception.getLocation().getColumnNr() + ")";
    }
}
