package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionPort;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@DisplayName("电子图库 Excel 适配器连接 Python 模拟 API")
class MockElectronicDrawingExcelApiIntegrationTest {

  private static final String DRAWING_NO = "J40AH-40HY-03";
  private static final String EXPECTED_SHA256 =
      "d7774cee69a23a87ef7463a12bc577d3532fc853a346abd43ee0461d333b0a07";
  private static final Clock SHANGHAI_CLOCK = Clock.fixed(
      Instant.parse("2026-08-30T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static Process mockApi;
  private static int port;
  private ElectronicDrawingBomProperties properties;

  @BeforeAll
  static void startMockApi() throws Exception {
    Path script = locateWorkspaceFile(
        "outputs/electronic-drawing-e2e-simulator/mock_electronic_drawing_excel_api.py");
    assertThat(Files.isRegularFile(script)).as("模拟 API 脚本").isTrue();
    port = availablePort();
    mockApi = new ProcessBuilder(
        "python3",
        script.toString(),
        "--host", "127.0.0.1",
        "--port", Integer.toString(port),
        "--timeout-seconds", "0.2")
        .redirectErrorStream(true)
        .start();
    waitUntilHealthy();
  }

  @AfterAll
  static void stopMockApi() throws Exception {
    if (mockApi == null) return;
    mockApi.destroy();
    if (!mockApi.waitFor(2, TimeUnit.SECONDS)) {
      mockApi.destroyForcibly();
      mockApi.waitFor(2, TimeUnit.SECONDS);
    }
  }

  @BeforeEach
  void setUp() {
    properties = new ElectronicDrawingBomProperties();
    properties.setBaseUrl("http://127.0.0.1:" + port);
    properties.setExcelBomPath("/api/v1/electronic-drawing/bom-excel");
    properties.setConnectTimeoutMs(300);
    properties.setReadTimeoutMs(500);
    properties.setMaxExcelFileSizeBytes(1024 * 1024);
  }

  @Test
  @DisplayName("success 返回真实 J40 xlsx 及来源证据")
  void success() {
    ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired = adapter().acquire(query());

    assertThat(acquired.fileName())
        .isEqualTo("电子图库明细 J40AH-40HY-03-MX-电子表.xlsx");
    assertThat(acquired.fileSize()).isEqualTo(8698);
    assertThat(acquired.content()).hasSize(8698);
    assertThat(acquired.sha256()).isEqualTo(EXPECTED_SHA256);
    assertThat(acquired.drawingNo()).isEqualTo(DRAWING_NO);
    assertThat(acquired.requestId()).isEqualTo("MOCK-INTEGRATION-1");
    assertThat(acquired.sourceSystem()).isEqualTo("MOCK_EXCEL");
    assertThat(acquired.acquiredAt().toString()).isEqualTo("2026-08-30T10:00");
  }

  @Test
  @DisplayName("not_found 只映射为明确无BOM且不可重试")
  void notFound() {
    properties.setExcelBomPath(
        "/api/v1/electronic-drawing/bom-excel?scenario=not_found");
    assertFailure(ElectronicDrawingExcelAcquisitionException.BOM_NOT_FOUND, false);
  }

  @Test
  @DisplayName("server_error 映射为可重试技术错误")
  void serverError() {
    properties.setExcelBomPath(
        "/api/v1/electronic-drawing/bom-excel?scenario=server_error");
    assertFailure(ElectronicDrawingExcelAcquisitionException.QUERY_RETRY, true);
  }

  @Test
  @DisplayName("corrupt 即使响应SHA一致也识别为损坏xlsx")
  void corrupt() {
    properties.setExcelBomPath(
        "/api/v1/electronic-drawing/bom-excel?scenario=corrupt");
    assertFailure(ElectronicDrawingExcelAcquisitionException.FILE_INVALID, true);
  }

  @Test
  @DisplayName("timeout 映射为可重试技术错误")
  void timeout() {
    properties.setReadTimeoutMs(50);
    properties.setExcelBomPath(
        "/api/v1/electronic-drawing/bom-excel?scenario=timeout");
    assertFailure(ElectronicDrawingExcelAcquisitionException.QUERY_RETRY, true);
  }

  private HttpElectronicDrawingExcelAcquisitionAdapter adapter() {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    return new HttpElectronicDrawingExcelAcquisitionAdapter(
        properties,
        builder -> { },
        client,
        SHANGHAI_CLOCK,
        () -> "UNUSED");
  }

  private ElectronicDrawingExcelAcquisitionPort.Query query() {
    return new ElectronicDrawingExcelAcquisitionPort.Query(
        DRAWING_NO, "MOCK-INTEGRATION-1");
  }

  private void assertFailure(String code, boolean retryable) {
    assertThatThrownBy(() -> adapter().acquire(query()))
        .isInstanceOf(ElectronicDrawingExcelAcquisitionException.class)
        .satisfies(throwable -> {
          ElectronicDrawingExcelAcquisitionException exception =
              (ElectronicDrawingExcelAcquisitionException) throwable;
          assertThat(exception.getCode()).isEqualTo(code);
          assertThat(exception.isRetryable()).isEqualTo(retryable);
        });
  }

  private static void waitUntilHealthy() throws Exception {
    HttpClient healthClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(200))
        .build();
    URI health = URI.create("http://127.0.0.1:" + port + "/health");
    Exception last = null;
    for (int attempt = 0; attempt < 50; attempt++) {
      if (!mockApi.isAlive()) {
        throw new IllegalStateException(
            "模拟 API 启动失败：" + new String(mockApi.getInputStream().readAllBytes()));
      }
      try {
        HttpResponse<String> response = healthClient.send(
            HttpRequest.newBuilder(health).timeout(Duration.ofMillis(300)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && response.body().contains("\"status\": \"UP\"")) {
          return;
        }
      } catch (IOException | InterruptedException exception) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        last = exception;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("模拟 API 在限定时间内未就绪", last);
  }

  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static Path locateWorkspaceFile(String relativePath) {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    for (int depth = 0; depth < 6 && current != null; depth++, current = current.getParent()) {
      Path candidate = current.resolve(relativePath);
      if (Files.exists(candidate)) return candidate;
    }
    throw new IllegalStateException("找不到工作区文件：" + relativePath);
  }
}
