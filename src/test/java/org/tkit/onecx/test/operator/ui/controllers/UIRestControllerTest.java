package org.tkit.onecx.test.operator.ui.controllers;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.*;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.microprofile.openapi.OASFactory.*;
import static org.eclipse.microprofile.openapi.OASFactory.createOperation;

import java.util.UUID;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.tkit.onecx.test.domain.services.K8sExecService;
import org.tkit.onecx.test.operator.AbstractTest;
import org.tkit.onecx.test.operator.rs.v1.mappers.ExceptionMapper;

import gen.org.tkit.onecx.test.operator.rs.v1.model.ExecutionStatusDTO;
import gen.org.tkit.onecx.test.operator.rs.v1.model.ProblemDetailResponseDTO;
import gen.org.tkit.onecx.test.operator.rs.v1.model.SecurityTestResponseDTO;
import gen.org.tkit.onecx.test.operator.ui.model.TestRequestDTO;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;

@QuarkusTest
@WithKubernetesTestServer
@TestHTTPEndpoint(UIController.class)
class UIRestControllerTest extends AbstractTest {

    @InjectMock
    K8sExecService k8sExecService;

    @BeforeEach
    void resetExpectation() {
        clearExpectation(mockServerClient);
    }

    @Test
    void uiBadRequestTest() {

        var dto = given().when()
                .auth().oauth2(keycloakClient.getAccessToken(ALICE))

                .contentType(APPLICATION_JSON)
                .post("")
                .then()

                .statusCode(BAD_REQUEST.getStatusCode())
                .extract().as(ProblemDetailResponseDTO.class);

        assertThat(dto).isNotNull();

        assertThat(dto.getErrorCode()).isEqualTo(ExceptionMapper.ErrorCodes.CONSTRAINT_VIOLATIONS.name());
    }

    @Test
    void runNoServiceFoundTest() {
        var service = "ui-test-service-1";
        var pod = "ui-test-1";

        createServiceAndPod(service, pod, false);

        var mockUrl = ConfigProvider.getConfig().getValue("quarkus.mockserver.endpoint",
                String.class);

        var request = new TestRequestDTO()
                .id(UUID.randomUUID().toString())
                .service("does-not-exists")
                .url(mockUrl);

        var dto = given().when()
                .auth().oauth2(keycloakClient.getAccessToken(ALICE))

                .body(request).contentType(APPLICATION_JSON)
                .post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .extract().as(ProblemDetailResponseDTO.class);

        assertThat(dto).isNotNull();

        assertThat(dto.getErrorCode()).isEqualTo(ExceptionMapper.ErrorCodes.SERVICE_ERROR.name());
        assertThat(dto.getDetail()).isEqualTo("no service found");
    }

    @Test
    void runFailedTest() {
        var id = UUID.randomUUID().toString();
        var service = "ui-test-service-ui-3";
        var pod = "ui-test-3";
        var path = "/mfe/test-ui/api";
        var apiPath = "/test-ui/{id}";

        var mockUrl = ConfigProvider.getConfig().getValue("quarkus.mockserver.endpoint",
                String.class);

        createServiceAndPod(service, pod);
        Mockito.when(k8sExecService.execCommandOnPod(pod, CMD_CONFIG))
                .thenReturn(createNginxConfig(path, mockUrl));

        createOpenApiMock(createOpenAPI().addServer(createServer().url("http://localhost:8080"))
                .paths(createPaths()
                        .addPathItem(apiPath,
                                createPathItem()
                                        .GET(createOperation()
                                                .addParameter(createParameter().in(Parameter.In.PATH).name("id"))
                                                .addParameter(createParameter().in(null).name("a"))
                                                .addParameter(createParameter().in(Parameter.In.QUERY).name("q"))))
                        .addPathItem("/failed", createPathItem().GET(createOperation()))));

        createResponse(path, "/test/" + id, FORBIDDEN);
        createResponse(path, "/failed", OK);

        createQuarkusHealthMock();

        createMockQMetrics(path, BAD_REQUEST);
        createMockQHealth(path, BAD_REQUEST);
        createMockQSwaggerUI(path, OK);
        createMockQOpenApi(path, NOT_FOUND);

        var request = new TestRequestDTO()
                .id(id)
                .service(service)
                .quarkus(true)
                .url(mockUrl);

        var dto = given().when()
                .auth().oauth2(keycloakClient.getAccessToken(ALICE))

                .body(request).contentType(APPLICATION_JSON)
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .extract().as(SecurityTestResponseDTO.class);

        assertThat(dto).isNotNull();

        assertThat(dto.getId()).isEqualTo(request.getId());
        assertThat(dto.getStatus()).isEqualTo(ExecutionStatusDTO.FAILED);
        assertThat(dto.getExecutions()).isNotNull();
    }

    @Test
    void runWrongDomainTest() {
        var id = UUID.randomUUID().toString();
        var service = "ui-test-service-ui-31";
        var pod = "ui-test-31";
        var path = "/mfe/test-ui/api";
        var apiPath = "/test-ui/{id}";

        var mockUrl = ConfigProvider.getConfig().getValue("quarkus.mockserver.endpoint",
                String.class);

        createServiceAndPod(service, pod);
        Mockito.when(k8sExecService.execCommandOnPod(pod, CMD_CONFIG))
                .thenReturn(createNginxConfig(path, mockUrl));

        createOpenApiMock(createOpenAPI().addServer(createServer().url("http://localhost:8080"))
                .paths(createPaths()
                        .addPathItem(apiPath,
                                createPathItem()
                                        .GET(createOperation()
                                                .addParameter(createParameter().in(Parameter.In.PATH).name("id"))
                                                .addParameter(createParameter().in(null).name("a"))
                                                .addParameter(createParameter().in(Parameter.In.QUERY).name("q"))))
                        .addPathItem("/failed", createPathItem().GET(createOperation()))));

        createResponse(path, "/test/" + id, FORBIDDEN);
        createResponse(path, "/failed", OK);

        createQuarkusHealthMock();

        createMockQMetrics(path, BAD_REQUEST);
        createMockQHealth(path, BAD_REQUEST);
        createMockQSwaggerUI(path, OK);
        createMockQOpenApi(path, NOT_FOUND);

        var request = new TestRequestDTO()
                .id(id)
                .service(service)
                .quarkus(true)
                .url(mockUrl + "1");

        var dto = given().when()
                .auth().oauth2(keycloakClient.getAccessToken(ALICE))

                .body(request).contentType(APPLICATION_JSON)
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .extract().as(SecurityTestResponseDTO.class);

        assertThat(dto).isNotNull();

        assertThat(dto.getId()).isEqualTo(request.getId());
        assertThat(dto.getStatus()).isEqualTo(ExecutionStatusDTO.FAILED);
        assertThat(dto.getExecutions()).isNotNull();
    }

    private void createQuarkusHealthMock() {
        createMockQHealth("", OK);
    }
}
