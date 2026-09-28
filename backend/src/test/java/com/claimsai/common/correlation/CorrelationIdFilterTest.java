package com.claimsai.common.correlation;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String run(String incoming, AtomicReference<String> seenInsideRequest) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incoming != null) {
            request.addHeader(CorrelationId.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> seenInsideRequest.set(MDC.get(CorrelationId.MDC_KEY)));
        return response.getHeader(CorrelationId.HEADER);
    }

    @Test
    void keepsASafeIncomingIdAndExposesItToLoggingDuringTheRequest() throws Exception {
        AtomicReference<String> inside = new AtomicReference<>();

        assertThat(run("demo-123", inside)).isEqualTo("demo-123");
        assertThat(inside.get()).isEqualTo("demo-123");
    }

    @Test
    void generatesAnIdWhenNoneIsSent() throws Exception {
        AtomicReference<String> inside = new AtomicReference<>();

        String id = run(null, inside);
        assertThat(id).hasSize(36);
        assertThat(inside.get()).isEqualTo(id);
    }

    @Test
    void replacesValuesThatCouldInjectFakeLogLines() throws Exception {
        String id = run("abc\nERROR forged line", new AtomicReference<>());

        assertThat(id).doesNotContain("forged").hasSize(36);
    }

    @Test
    void clearsTheMdcAfterTheRequestSoPooledThreadsDontLeakIds() throws Exception {
        run("demo-123", new AtomicReference<>());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
}
