package org.tkit.onecx.test.domain.services;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tkit.onecx.test.domain.models.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.uritemplate.UriTemplate;

@ApplicationScoped
public class TestService {

    private static final Logger log = LoggerFactory.getLogger(TestService.class);

    protected static final String[] CMD_CONFIG = { "nginx", "-T" };

    @Inject
    K8sService k8sService;

    @Inject
    K8sExecService k8sExecService;

    @Inject
    NginxService nginxService;

    @Inject
    QuarkusService quarkusService;

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    public TestResponse execute(TestRequest request) throws SecurityException {

        var selector = k8sService.findServiceSelector(request.getService());
        if (selector.isEmpty()) {
            throw new ServiceException("no service found");
        }

        var pods = k8sService.findPodsBySelector(selector);
        if (pods.isEmpty()) {
            throw new ServiceException("no pods found");
        }
        var pod = pods.get(0);

        var nginxConfig = k8sExecService.execCommandOnPod(pod, CMD_CONFIG);
        if (nginxConfig == null || nginxConfig.isEmpty()) {
            throw new ServiceException("no nginx config found");
        }
        var proxyPassLocations = nginxService.getProxyPassLocation(nginxConfig);

        if (proxyPassLocations.isEmpty()) {
            throw new ServiceException("no proxy pass locations found");
        }

        ProxyConfiguration quarkusProxyConfiguration = findQuarkusProxyConfiguration(proxyPassLocations);

        if (quarkusProxyConfiguration == null) {
            log.error("No Quarkus proxy configuration found");
            throw new ServiceException("No Quarkus proxy configuration found");
        }

        log.info("Proxy pass locations found {}", quarkusProxyConfiguration);

        final var url = url(request);

        var result = new TestResponse();
        result.setId(request.getId());
        result.setService(request.getService());
        result.setUrl(request.getUrl());
        result.setExecutions(new ArrayList<>());
        result.setWhitelistedPaths(new ArrayList<>());

        testQuarkusQService("health", result, url, quarkusProxyConfiguration, "/q/health");
        testQuarkusQService("metrics", result, url, quarkusProxyConfiguration, "/q/metrics");
        testQuarkusQService("openapi", result, url, quarkusProxyConfiguration, "/q/openapi");
        testQuarkusQService("swagger-ui", result, url, quarkusProxyConfiguration, "/q/swagger-ui");

        try {
            log.info("OpenAPI location path: {}, proxy path: {}", quarkusProxyConfiguration.getLocation(),
                    quarkusProxyConfiguration.getProxyPass());
            var openapi = quarkusService.getOpenApi(quarkusProxyConfiguration.getProxyPass());

            testOpenApi(result, url, quarkusProxyConfiguration, openapi);
        } catch (Exception ex) {
            log.error("Error execute test for {} - {}, error: {}", quarkusProxyConfiguration.getProxyPass(),
                    quarkusProxyConfiguration.getLocation(), ex.getMessage(), ex);
            result.getExecutions().add(createExecutionError(quarkusProxyConfiguration.getProxyPass(),
                    quarkusProxyConfiguration.getLocation(), url, ex.getMessage()));
        }

        var failed = result.getExecutions().stream().anyMatch(x -> x.getStatus() != TestExecution.Status.OK);
        result.setStatus(failed ? TestResponse.Status.FAILED : TestResponse.Status.OK);

        String jsonResult = toJson(result);

        log.info("Security test result {}", jsonResult);

        return result;
    }

    private ProxyConfiguration findQuarkusProxyConfiguration(List<ProxyConfiguration> proxyPassLocations) {
        return proxyPassLocations.stream()
                .filter(pc -> quarkusService.testQuarkusEndpoint(pc.getProxyPass()) == 200)
                .findAny()
                .orElseGet(() -> null);
    }

    private void testQuarkusQService(String name, TestResponse result, String domain, ProxyConfiguration pc, String path) {
        var uri = createUri(domain, pc, path);
        try {
            log.info("{} path: {} proxy: {} uri: {}", name, path, pc, uri);
            var request = WebClient
                    .create(vertx, new WebClientOptions().setVerifyHost(false).setFollowRedirects(false).setTrustAll(true))
                    .requestAbs(HttpMethod.GET, uri);

            var response = request.send().await().atMost(Duration.ofSeconds(5));
            var code = response.statusCode();

            var status = code >= Response.Status.BAD_REQUEST.getStatusCode() ? TestExecution.Status.OK
                    : TestExecution.Status.FAILED;

            log.info("{}-test {} {} {}", name, uri, code, status);

            result.getExecutions().add(createExecution(path, pc.getLocation(), status, uri, code));

        } catch (Exception ex) {
            log.error("Error execute {} test for {} - {}, error: {}", name, path, pc.getLocation(), ex.getMessage(), ex);
            result.getExecutions().add(createExecution(path, pc.getLocation(), TestExecution.Status.OK, uri, ex.getMessage()));
        }
    }

    private void testOpenApi(TestResponse result, String domain, ProxyConfiguration proxyConfiguration, OpenAPI openapi) {
        if (openapi.getPaths() == null) {
            log.warn("No paths found in OpenAPI definition");
            return;
        }
        openapi.getPaths().getPathItems().forEach((path, item) -> {

            var uri = createUri(domain, proxyConfiguration, path);
            item.getOperations()
                    .forEach((method, op) -> {
                        log.info("Test operation {} for path {}", op.getOperationId(), path);
                        execute(result, uri, path, proxyConfiguration.getLocation(), method, op);
                    }

                    );
        });
    }

    private boolean hasXonecxNoSecurity(Operation op, String path) {
        if (op.getExtensions() != null
                && op.getExtensions().containsKey("x-onecx")) {

            Object xOnecx = op.getExtensions().get("x-onecx");
            if (xOnecx instanceof Map) {
                // x-onecx is a Map
                Map<String, String> xOnecxExtensions = (Map<String, String>) xOnecx;
                if (xOnecxExtensions.containsKey("security")
                        && "none".equalsIgnoreCase(xOnecxExtensions.get("security"))) {
                    log.info("Ignored operation: {} for path {} due to x-onecx security: none configuration",
                            op.getOperationId(), path);
                    return true;
                }
            }
        }
        return false;
    }

    private void execute(TestResponse result, String uri, String path, String proxyPath, PathItem.HttpMethod method,
            Operation op) {
        try {
            if (hasXonecxNoSecurity(op, path)) {
                result.getWhitelistedPaths().add(path);
                return;
            }

            var request = WebClient.create(vertx, new WebClientOptions().setVerifyHost(false).setTrustAll(true))
                    .requestAbs(io.vertx.core.http.HttpMethod.valueOf(method.name()), UriTemplate.of(uri));

            if (op.getParameters() != null) {
                op.getParameters().forEach(p -> {
                    if (Parameter.In.PATH == p.getIn()) {
                        request.setTemplateParam(p.getName(), result.getId());
                    }
                });
            }

            var response = request.send().await().atMost(Duration.ofSeconds(5));
            var code = response.statusCode();
            var status = code == Response.Status.UNAUTHORIZED.getStatusCode() ? TestExecution.Status.OK
                    : TestExecution.Status.FAILED;
            if (status == TestExecution.Status.FAILED) {
                log.error("Security test failed  {} {} {}", method, uri, code);
            } else {
                log.info("Security test passed  {} {} {}", method, uri, code);
            }
            result.getExecutions().add(createExecution(path, proxyPath, status, uri, code));
        } catch (Exception ex) {
            result.getExecutions().add(createExecutionError(path, proxyPath, uri, ex.getMessage()));
        }
    }

    private TestExecution createExecutionError(String path, String proxyPath, String uri, String error) {
        var e = new TestExecution();
        e.setPath(path);
        e.setProxy(proxyPath);
        e.setError(error);
        e.setUrl(uri);
        e.setStatus(TestExecution.Status.ERROR);
        return e;
    }

    private TestExecution createExecution(String path, String proxyPath, TestExecution.Status status, String uri, int code) {
        var e = new TestExecution();
        e.setPath(path);
        e.setProxy(proxyPath);
        e.setStatus(status);
        e.setUrl(uri);
        e.setCode(code);
        return e;
    }

    private TestExecution createExecution(String path, String proxyPath, TestExecution.Status status, String uri,
            String error) {
        var e = new TestExecution();
        e.setPath(path);
        e.setProxy(proxyPath);
        e.setStatus(status);
        e.setUrl(uri);
        e.setError(error);
        return e;
    }

    private String url(TestRequest dto) {
        var tmp = dto.getUrl();
        if (tmp.endsWith("/")) {
            tmp = tmp.substring(0, tmp.length() - 1);
        }
        return tmp;
    }

    private String createUri(String domain, ProxyConfiguration pc, String path) {
        String mergedPath = removePathPrefix(pc.getProxyPassFull(), path);

        return domain + pc.getLocation() + mergedPath;
    }

    /**
     * Find overlap of 1st string end and start of 2nd string.
     *
     * @return length of the overlap
     */
    private int findOverlapLength(String str1, String str2) {
        for (int i = 0; i < str1.length(); i++) {
            String substring = str1.substring(i);
            if (str2.startsWith(substring)) {
                return substring.length();
            }
        }
        return 0;
    }

    /**
     * Remove common path prefix from openapi path to avoid duplicate url path
     */
    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize object to JSON: {}", ex.getMessage());
            return String.valueOf(value);
        }
    }

    private String removePathPrefix(String proxyPassFull, String openApiPath) {
        return openApiPath.substring(findOverlapLength(proxyPassFull, openApiPath));
    }
}
