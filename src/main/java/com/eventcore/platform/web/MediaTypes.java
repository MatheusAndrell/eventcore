package com.eventcore.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;

public final class MediaTypes {

    private MediaTypes() {
    }

    public static void requireUtf8(HttpServletRequest request) throws HttpMediaTypeNotSupportedException {
        MediaType contentType = MediaType.parseMediaType(request.getContentType());
        if (contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset())) {
            throw new HttpMediaTypeNotSupportedException(contentType, List.of(MediaType.APPLICATION_JSON),
                    HttpMethod.valueOf(request.getMethod()));
        }
    }
}
