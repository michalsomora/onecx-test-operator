package org.tkit.onecx.test.operator;

import static jakarta.ws.rs.core.Response.Status.OK;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.Expectation;
import org.mockserver.model.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mockserver.test.InjectMockServerClient;
import io.quarkiverse.mockserver.test.MockServerTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.RestAssured;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import io.smallrye.openapi.api.SmallRyeOpenAPI;

@QuarkusTestResource(MockServerTestResource.class)
public abstract class AbstractTest {

    public static final String[] CMD_CONFIG = { "nginx", "-T" };

    private static final List<String> MOCK_IDS = new ArrayList<>();

    private static final Logger log = LoggerFactory.getLogger(AbstractTest.class);

    protected static final String ADMIN = "admin";
    protected static final String ALICE = "alice";

    protected KeycloakTestClient keycloakClient = new KeycloakTestClient();

    static {
        RestAssured.config = RestAssuredConfig.config().objectMapperConfig(
                ObjectMapperConfig.objectMapperConfig().jackson2ObjectMapperFactory(
                        (cls, charset) -> {
                            ObjectMapper objectMapper = new ObjectMapper();
                            objectMapper.registerModule(new JavaTimeModule());
                            objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
                            return objectMapper;
                        }));
    }

    @Inject
    protected KubernetesClient client;

    @InjectMockServerClient
    protected MockServerClient mockServerClient;

    protected void createServiceAndPod(String serviceName, String podName) {
        createServiceAndPod(serviceName, podName, true);
    }

    protected void createServiceAndPod(String serviceName, String podName, boolean selectLabel) {
        var selector = Map.of("app", podName);

        var service = new ServiceBuilder().withNewMetadata().withName(serviceName).endMetadata()
                .withNewSpec()
                .withSelector(selector)
                .addNewPort().withName("test-port").withProtocol("TCP").withPort(80).withTargetPort(new IntOrString(80))
                .endPort()
                .withType("LoadBalancer")
                .endSpec()
                .build();

        var pod = new PodBuilder()
                .withNewMetadata().withName(podName).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(podName)
                .withImage("nginx")
                .addNewPort().withContainerPort(80).endPort()
                .endContainer()
                .endSpec()
                .build();
        if (selectLabel) {
            pod.getMetadata().getLabels().putAll(selector);
        }

        var s = client.resource(service).create();
        log.info("Created service with name {}", s.getMetadata().getName());
        var p = client.resource(pod).create();
        log.info("Created pod with name {}", p.getMetadata().getName());
    }

    protected String createNginxConfig(String path, String mockUrl) {
        var tmp = """
                location = /error/ {
                alias    /usr/share/nginx/html/static/;
                    try_files $uri $uri/ = 404;
                }
                # configuration file /etc/nginx/conf.d/locations/locations.conf:
                location ${TEST_PATH} {
                    proxy_pass ${TEST_PROXY};
                    proxy_set_header Host            $host;
                    proxy_set_header X-Forwarded-For $remote_addr;
                }
                """;
        tmp = tmp.replace("${TEST_PATH}", path);
        tmp = tmp.replace("${TEST_PROXY}", mockUrl);
        return tmp;
    }

    protected String createNginxConfigWithMultipleConfigs(String path, String mockUrl) {
        var tmp = """
                location = /error/ {
                alias    /usr/share/nginx/html/static/;
                    try_files $uri $uri/ = 404;
                }
                # configuration of static files
                location ${TEST_PATH}/webresources {
                    proxy_pass http://web-resources-app/web-resources/;
                    proxy_set_header Host            $host;
                    proxy_set_header X-Forwarded-For $remote_addr;
                }
                # configuration file /etc/nginx/conf.d/locations/locations.conf:
                location ${TEST_PATH} {
                    proxy_pass   ${TEST_PROXY}/cool-prefix-rs;
                    proxy_set_header Host            $host;
                    proxy_set_header X-Forwarded-For $remote_addr;
                }
                """;
        tmp = tmp.replace("${TEST_PATH}", path);
        tmp = tmp.replace("${TEST_PROXY}", mockUrl);
        return tmp;
    }

    protected String createNginxConfigNoLocation() {
        return """
                # configuration file /etc/nginx/conf.d/locations/locations.conf:

                """;
    }

    protected String createNginxConfigNoProxyPass(String path) {
        var tmp = """
                location = /error/ {
                alias    /usr/share/nginx/html/static/;
                    try_files $uri $uri/ = 404;
                }
                # configuration file /etc/nginx/conf.d/locations/locations.conf:
                location ${TEST_PATH} {
                    proxy_set_header Host            $host;
                    proxy_set_header X-Forwarded-For $remote_addr;
                }
                """;
        tmp = tmp.replace("${TEST_PATH}", path);
        return tmp;
    }

    protected void createMockQMetrics(String path, Response.Status status) {
        addExpectation(mockServerClient.when(request().withPath(path + "/q/metrics").withMethod(HttpMethod.GET))
                .respond(httpRequest -> response().withStatusCode(status.getStatusCode())));
    }

    protected void createMockQHealth(String path, Response.Status status) {
        addExpectation(mockServerClient.when(request().withPath(path + "/q/health").withMethod(HttpMethod.GET))
                .respond(httpRequest -> response().withStatusCode(status.getStatusCode())));
    }

    protected void createMockQOpenApi(String path, Response.Status status) {
        addExpectation(mockServerClient.when(request().withPath(path + "/q/openapi").withMethod(HttpMethod.GET))
                .respond(httpRequest -> response().withStatusCode(status.getStatusCode())));
    }

    protected void createMockQSwaggerUI(String path, Response.Status status) {
        addExpectation(mockServerClient.when(request().withPath(path + "/q/swagger-ui").withMethod(HttpMethod.GET))
                .respond(httpRequest -> response().withStatusCode(status.getStatusCode())));
    }

    protected void createOpenApiMock(OpenAPI openAPI) {
        try {
            createOpenApiMock(SmallRyeOpenAPI.builder().withInitialModel(openAPI).build().toYAML());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void createOpenApiMock(String body) {
        addExpectation(mockServerClient.when(request().withPath("/q/openapi").withMethod(HttpMethod.GET))
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(body)));
    }

    protected void createResponse(String path, String apiPath, Response.Status status) {
        addExpectation(mockServerClient.when(request().withPath(path + apiPath).withMethod(HttpMethod.GET))
                .respond(httpRequest -> response().withStatusCode(status.getStatusCode())));
    }

    protected void addExpectation(Expectation[] exceptions) {
        for (Expectation e : List.of(exceptions)) {
            MOCK_IDS.add(e.getId());
        }

    }

    protected void clearExpectation(MockServerClient client) {
        MOCK_IDS.forEach(x -> {
            try {
                client.clear(x);
            } catch (Exception ex) {
                //  mockId not existing
            }
        });
        MOCK_IDS.clear();
    }

    protected void mockQuarkusEndpoints(String path) {
        //mock quarkus health api
        createMockQHealth("", OK);
        //mock all quarkus apis via proxy as 401
        createMockQMetrics(path, UNAUTHORIZED);
        createMockQHealth(path, UNAUTHORIZED);
        createMockQSwaggerUI(path, UNAUTHORIZED);
        createMockQOpenApi(path, UNAUTHORIZED);
    }
}
