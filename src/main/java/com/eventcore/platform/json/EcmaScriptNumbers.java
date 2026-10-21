package com.eventcore.platform.json;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

final class EcmaScriptNumbers {

    private static final int MAX_PLAIN_EXPONENT = 21;
    private static final int MIN_PLAIN_EXPONENT = -6;
    private static final MathContext SINGLE_DIGIT = new MathContext(1, RoundingMode.HALF_EVEN);

    private EcmaScriptNumbers() {
    }

    static String format(double value) {
        if (value == 0) {
            return "0";
        }
        BigDecimal shortest = shortestDecimal(Math.abs(value));
        String digits = shortest.unscaledValue().toString();
        int exponent = digits.length() - shortest.scale();
        String magnitude = layout(digits, exponent);
        return value < 0 ? "-" + magnitude : magnitude;
    }

    static BigDecimal shortestDecimal(double value) {
        BigDecimal javaShortest = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        if (javaShortest.precision() != 2) {
            return javaShortest;
        }
        BigDecimal singleDigit = javaShortest.round(SINGLE_DIGIT).stripTrailingZeros();
        return singleDigit.doubleValue() == value ? singleDigit : javaShortest;
    }

    private static String layout(String digits, int exponent) {
        int length = digits.length();
        if (length <= exponent && exponent <= MAX_PLAIN_EXPONENT) {
            return digits + "0".repeat(exponent - length);
        }
        if (0 < exponent && exponent <= MAX_PLAIN_EXPONENT) {
            return digits.substring(0, exponent) + "." + digits.substring(exponent);
        }
        if (MIN_PLAIN_EXPONENT < exponent && exponent <= 0) {
            return "0." + "0".repeat(-exponent) + digits;
        }
        String mantissa = length == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        int scientificExponent = exponent - 1;
        return mantissa + "e" + (scientificExponent < 0 ? "-" : "+") + Math.abs(scientificExponent);
    }
}
