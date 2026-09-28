package com.syllabai.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JSON request-body size cap (deep-audit 09-28 M4) — the servlet layer's
 * multipart limits never apply to {@code application/json} bodies, so before
 * this filter existed a single oversized POST (teacher draft, marking batch,
 * GLM OCR pair) could make Jackson allocate the whole document and OOM the
 * single-instance pod.
 *
 * <p>Two enforcement modes:</p>
 * <ul>
 *   <li><strong>fast path</strong> — a {@code Content-Length} header above the
 *       budget is rejected with 413 before a single body byte is read;</li>
 *   <li><strong>streaming path</strong> — for chunked bodies (no declared
 *       length) the request is wrapped in a counting stream that aborts the
 *       moment the budget is crossed, throwing
 *       {@link BodyTooLargeException}; the global exception handler maps it to
 *       the same 413 shape. Memory never exceeds the budget.</li>
 * </ul>
 *
 * <p>{@code multipart/form-data} is deliberately passed through untouched —
 * those uploads are governed by {@code spring.servlet.multipart.max-*-size}.
 * The filter sits at the very front of the servlet chain (before security):
 * body size is not secret, and an oversized body is the one failure an
 * unauthenticated caller can cause that costs memory.</p>
 */
public class JsonBodyLimitFilter extends OncePerRequestFilter {

    private final JsonBodyLimitProperties properties;

    public JsonBodyLimitFilter(JsonBodyLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!Boolean.TRUE.equals(properties.enabled())
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || isMultipart(request)) {
            chain.doFilter(request, response);
            return;
        }
        long max = properties.maxJsonBodyBytes();
        String contentLength = request.getHeader("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength.strip()) > max) {
                    reject(response);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // malformed length header — fall through to stream counting,
                // which is authoritative regardless of what the header claims
            }
        }
        chain.doFilter(new CappedBodyRequest(request, max), response);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    /** the ApiError field shape every other boundary returns */
    private void reject(HttpServletResponse response) throws IOException {
        logRejected(properties.maxJsonBodyBytes());
        response.setStatus(413);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":413,\"error\":\"payload_too_large\","
                + "\"message\":\"request body exceeds the allowed size\","
                + "\"timestamp\":\"" + Instant.now() + "\"}");
    }

    private static void logRejected(long max) {
        org.slf4j.LoggerFactory.getLogger(JsonBodyLimitFilter.class)
                .info("request body rejected: exceeds the {} byte JSON body budget", max);
    }

    /** thrown by the capped stream mid-read; the global exception handler
     *  maps it to the same 413 ApiError the fast path writes */
    public static final class BodyTooLargeException extends RuntimeException {
        public BodyTooLargeException(long max) {
            super("request body exceeds the allowed size of " + max + " bytes");
        }
    }

    private static final class CappedBodyRequest extends HttpServletRequestWrapper {
        private final long max;

        CappedBodyRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CappedStream(super.getInputStream(), max);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class CappedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long max;
        private long count;

        CappedStream(ServletInputStream delegate, long max) {
            this.delegate = delegate;
            this.max = max;
        }

        private void count(int n) {
            count += n;
            if (count > max) {
                throw new BodyTooLargeException(max);
            }
        }

        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override public boolean isReady() {
            return delegate.isReady();
        }

        @Override public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
