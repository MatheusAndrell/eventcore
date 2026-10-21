package com.eventcore.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ProblemResponseWriter problems;

    public RequestBodyLimitFilter(HttpProperties properties, ProblemResponseWriter problems) {
        this.maxBytes = properties.maxRequestBodySize().toBytes();
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            rejectTooLarge(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(Math.toIntExact(maxBytes + 1));
        if (body.length > maxBytes) {
            rejectTooLarge(request, response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void rejectTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Connection", "close");
        problems.write(request, response, Problems.of(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large",
                "Payload too large", "request body exceeds " + maxBytes + " bytes"));
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("asynchronous reads are not supported for buffered bodies");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
