package com.eventcore.platform.json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class JsonCanonicalizer {

    public byte[] canonicalize(JsonNode node) {
        return canonicalText(node).getBytes(StandardCharsets.UTF_8);
    }

    public String canonicalText(JsonNode node) {
        StringBuilder output = new StringBuilder();
        write(node, "", output);
        return output.toString();
    }

    private static void write(JsonNode node, String pointer, StringBuilder output) {
        switch (node.getNodeType()) {
            case OBJECT -> writeObject(node, pointer, output);
            case ARRAY -> writeArray(node, pointer, output);
            case STRING -> writeString(node.stringValue(), pointer, output);
            case NUMBER -> writeNumber(node.decimalValue(), pointer, output);
            case BOOLEAN -> output.append(node.booleanValue());
            case NULL -> output.append("null");
            case BINARY, POJO, MISSING -> throw new IllegalArgumentException(
                    "node type " + node.getNodeType() + " has no JSON text representation at " + pointer);
        }
    }

    private static void writeObject(JsonNode node, String pointer, StringBuilder output) {
        List<String> names = new ArrayList<>(node.propertyNames());
        names.sort(null);
        output.append('{');
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            String childPointer = pointer + "/" + escapePointer(name);
            if (index > 0) {
                output.append(',');
            }
            writeString(name, childPointer, output);
            output.append(':');
            write(node.get(name), childPointer, output);
        }
        output.append('}');
    }

    private static void writeArray(JsonNode node, String pointer, StringBuilder output) {
        output.append('[');
        for (int index = 0; index < node.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            write(node.get(index), pointer + "/" + index, output);
        }
        output.append(']');
    }

    private static void writeNumber(BigDecimal value, String pointer, StringBuilder output) {
        double approximation = value.doubleValue();
        if (Double.isInfinite(approximation) || !representsExactly(approximation, value)) {
            throw new CanonicalizationException(CanonicalizationException.Reason.UNREPRESENTABLE_NUMBER, pointer);
        }
        output.append(EcmaScriptNumbers.format(approximation));
    }

    private static boolean representsExactly(double approximation, BigDecimal value) {
        if (approximation == 0) {
            return value.signum() == 0;
        }
        BigDecimal shortest = EcmaScriptNumbers.shortestDecimal(Math.abs(approximation));
        return (approximation < 0 ? shortest.negate() : shortest).compareTo(value) == 0;
    }

    private static void writeString(String value, String pointer, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw unpairedSurrogate(pointer);
                }
                output.append(current).append(value.charAt(++index));
            } else if (Character.isLowSurrogate(current)) {
                throw unpairedSurrogate(pointer);
            } else {
                appendEscaped(current, output);
            }
        }
        output.append('"');
    }

    private static void appendEscaped(char character, StringBuilder output) {
        switch (character) {
            case '"' -> output.append("\\\"");
            case '\\' -> output.append("\\\\");
            case '\b' -> output.append("\\b");
            case '\f' -> output.append("\\f");
            case '\n' -> output.append("\\n");
            case '\r' -> output.append("\\r");
            case '\t' -> output.append("\\t");
            default -> {
                if (character < 0x20) {
                    output.append(String.format("\\u%04x", (int) character));
                } else {
                    output.append(character);
                }
            }
        }
    }

    private static CanonicalizationException unpairedSurrogate(String pointer) {
        return new CanonicalizationException(CanonicalizationException.Reason.UNPAIRED_SURROGATE, pointer);
    }

    private static String escapePointer(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
