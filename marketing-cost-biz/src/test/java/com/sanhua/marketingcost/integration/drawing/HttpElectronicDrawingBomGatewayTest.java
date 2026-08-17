package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("QCBP-11 电子图库HTTP Gateway")
class HttpElectronicDrawingBomGatewayTest {
  private HttpServer server;
  private ElectronicDrawingBomProperties properties;
  private final AtomicReference<String> requestBody = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    properties = new ElectronicDrawingBomProperties();
    properties.setMode(ElectronicDrawingMode.HTTP);
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setCurrentBomPath("/current");
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  @Test
  void postsScopedQueryAndParsesNodesWithoutTrustingUpstreamValidFlag() {
    server.createContext("/current", exchange -> respond(exchange, 200, """
        {
          "valid": false,
          "sourceSystem": "ELECTRONIC_DRAWING",
          "productCode": "P-1",
          "materialOrganizationCode": "COMMERCIAL",
          "bomPurpose": "主制造",
          "sourceVersion": "V3",
          "versionStatus": "ACTIVE",
          "effectiveFrom": "2026-01-01",
          "queriedAt": "2026-08-13T10:00:00+08:00",
          "nodes": [{
            "nodeKey":"R", "level":0, "materialCode":"P-1", "materialName":"产品",
            "materialNature":"MANUFACTURE", "quantityPerParent":1, "unit":"件",
            "sortSeq":1, "active":true
          }]
        }
        """));
    server.start();

    ElectronicBomFetchResult result = gateway().fetchCurrentBom(query());

    assertThat(result.status()).isEqualTo(ElectronicBomFetchResult.Status.FOUND);
    assertThat(result.sourceVersion()).isEqualTo("V3");
    assertThat(result.nodes()).hasSize(1);
    assertThat(requestBody.get()).contains("\"productCode\":\"P-1\"")
        .contains("\"materialOrganizationCode\":\"COMMERCIAL\"")
        .contains("\"bomPurpose\":\"主制造\"");
  }

  @Test
  void mapsNotFoundForbiddenAndVoidWithoutInventingSuccess() throws Exception {
    server.createContext("/current", exchange -> respond(exchange, 404, "{}"));
    server.start();
    assertThat(gateway().fetchCurrentBom(query()).status())
        .isEqualTo(ElectronicBomFetchResult.Status.NOT_FOUND);
    server.stop(0);

    setUp();
    server.createContext("/current", exchange -> respond(exchange, 403, "{}"));
    server.start();
    assertThat(gateway().fetchCurrentBom(query()).status())
        .isEqualTo(ElectronicBomFetchResult.Status.FORBIDDEN);
    server.stop(0);

    setUp();
    server.createContext("/current", exchange -> respond(exchange, 200,
        "{\"versionStatus\":\"VOIDED\"}"));
    server.start();
    assertThat(gateway().fetchCurrentBom(query()).status())
        .isEqualTo(ElectronicBomFetchResult.Status.VOID);
  }

  @Test
  void mapsReadTimeoutToStableStatus() {
    properties.setReadTimeoutMs(40);
    server.createContext("/current", exchange -> {
      try {
        Thread.sleep(150);
        respond(exchange, 200, "{}");
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    server.start();

    assertThat(gateway().fetchCurrentBom(query()).status())
        .isEqualTo(ElectronicBomFetchResult.Status.TIMEOUT);
  }

  @Test
  void disabledGatewayAlwaysFailsClosed() {
    ElectronicBomFetchResult result =
        new DisabledElectronicDrawingBomGateway().fetchCurrentBom(query());
    assertThat(result.status())
        .isEqualTo(ElectronicBomFetchResult.Status.INTEGRATION_DISABLED);
    assertThat(result.nodes()).isEmpty();
  }

  @Test
  void httpModeHasOneExplicitSpringInjectionConstructor() {
    assertThat(HttpElectronicDrawingBomGateway.class.getDeclaredConstructors())
        .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
        .singleElement()
        .satisfies(constructor -> assertThat(constructor.getParameterTypes())
            .containsExactly(ElectronicDrawingBomProperties.class, ObjectMapper.class));
  }

  private HttpElectronicDrawingBomGateway gateway() {
    return new HttpElectronicDrawingBomGateway(properties, new ObjectMapper().findAndRegisterModules());
  }

  private ElectronicBomQuery query() {
    return new ElectronicBomQuery("P-1", "COMMERCIAL", "210", "主制造",
        LocalDate.of(2026, 8, 13), "REQ-1");
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
