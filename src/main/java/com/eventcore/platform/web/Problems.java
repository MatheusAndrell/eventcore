package com.eventcore.platform.web;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class Problems {

    private static final String TYPE_PREFIX = "urn:eventcore:problem:";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail withViolations(
            HttpStatus status, String slug, String title, String detail, List<String> violations) {
        ProblemDetail problem = of(status, slug, title, detail);
        problem.setProperty("violations", List.copyOf(violations));
        return problem;
    }

    public static ErrorResponseException exception(HttpStatus status, String slug, String title, String detail) {
        return new ErrorResponseException(status, of(status, slug, title, detail), null);
    }

    public static ErrorResponseException exception(
            HttpStatus status, String slug, String title, String detail, List<String> violations) {
        return new ErrorResponseException(status, withViolations(status, slug, title, detail, violations), null);
    }
}
