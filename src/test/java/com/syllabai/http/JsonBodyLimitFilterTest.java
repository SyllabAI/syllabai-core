package com.syllabai.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Deep-audit 09-28 M4: JSON request bodies are capped. A declared
 * {@code Content-Length} over budget is rejected with the ApiError-shaped 413
 * before a byte is read; chunked (undeclared) bodies are capped mid-stream by
 * the counting wrapper; multipart and OPTIONS pass through untouched; the
 * master switch disables everything.
 */
class JsonBodyLimitFilterTest {

    private static final long CAP = 1_000;

    private final JsonBodyLimitFilter filter =
            new JsonBodyLimitFilter(JsonBodyLimitProperties.of(true, CAP));

    private static MockHttpServletRequest post(String uri, Long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (contentLength != null) {
            request.setContent(new byte[contentLength.intValue()]);
        }
        return request;
    }

    @Test
    @DisplayName("M4: a declared body over budget is rejected with 413 before "
            + "the chain runs")
    void declaredLengthOverCapRejected() throws Exception {
        MockHttpServletRequest request =
                post("/api/v1/teacher/curriculum/drafts", CAP + 1);
        // the mock stores setContent only as an internal field — real clients
        // declare the length as a header, which is what the filter reads
        request.addHeader("Content-Length", String.valueOf(CAP + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"status\":413")
                .contains("payload_too_large")
                .contains("request body exceeds the allowed size");
        assertThat(chain.getRequest()).isNull();   // controller never ran
    }

    @Test
    @DisplayName("M4: a body under budget passes through")
    void underCapPassesThrough() throws Exception {
        MockHttpServletRequest request = post("/api/v1/tutor/ask", CAP - 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();   // the wrapped request reached the chain
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("M4: a chunked body (no trustworthy declared length) aborts "
            + "mid-read when the streaming count crosses the budget")
    void streamedBodyCappedMidRead() throws Exception {
        // a lying/absent Content-Length must not matter: the wrapper counts
        // every byte and aborts the moment the budget is crossed
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/teacher/content/glm-ocr/pair");
        request.setContent(new byte[(int) CAP + 1]);
        request.addHeader("Content-Length", String.valueOf(CAP / 2));   // header lies: half the real size
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain readingChain = (req, res) -> {
            try {
                req.getInputStream().readAllBytes();   // drains the body — cap fires here
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, readingChain))
                .isInstanceOf(com.syllabai.http.JsonBodyLimitFilter.BodyTooLargeException.class)
                .hasMessageContaining("exceeds the allowed size");
    }

    @Test
    @DisplayName("M4: multipart/form-data passes through untouched (governed by "
            + "spring.servlet.multipart limits)")
    void multipartPassesThrough() throws Exception {
        MockHttpServletRequest request = post("/api/v1/admin/question-bank/ingest", CAP + 1);
        request.setContentType("multipart/form-data; boundary=----x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();   // no 413, no wrapping rejection
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("M4: OPTIONS preflight never touches the cap")
    void optionsPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS", "/api/v1/tutor/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("M4: the master switch disables the cap entirely")
    void disabledFilterPassesThrough() throws Exception {
        JsonBodyLimitFilter off = new JsonBodyLimitFilter(JsonBodyLimitProperties.of(false, CAP));
        MockHttpServletRequest request = post("/api/v1/tutor/ask", CAP + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        off.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();   // no 413 despite the huge body
    }
}
