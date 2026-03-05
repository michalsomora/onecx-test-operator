package org.tkit.onecx.test.domain.metrics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Custom metrics for the /test/security endpoint.
 * <p>
 * Exposes a counter {@code onecx_security_test_requests_total} with a {@code service} tag
 * so that you can see how many times each distinct service name was tested.
 * Example Prometheus output:
 *
 * <pre>
 *   onecx_security_test_requests_total{service="my-service",status="OK"} 3.0
 *   onecx_security_test_requests_total{service="other-service",status="FAILED"} 1.0
 * </pre>
 */
@ApplicationScoped
public class SecurityTestMetrics {

    static final String METRIC_NAME = "security_test_requests_total";
    static final String TAG_SERVICE = "service";
    static final String TAG_STATUS = "status";

    @Inject
    MeterRegistry registry;

    /**
     * Increment the request counter for the given service name and outcome status.
     *
     * @param service the value of {@link org.tkit.onecx.test.domain.models.TestRequest#getService()}
     * @param status {@code "OK"}, {@code "FAILED"} or {@code "ERROR"}
     */
    public void incrementRequest(String service, String status) {
        Counter.builder(METRIC_NAME)
                .description("Total number of security test requests per service")
                .tag(TAG_SERVICE, service)
                .tag(TAG_STATUS, status)
                .register(registry)
                .increment();
    }
}
