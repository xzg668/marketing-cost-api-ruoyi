package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库 Excel HTTP 获取适配器")
class HttpElectronicDrawingExcelAcquisitionAdapterTest {

  private static final String DRAWING_NO = "J40AH-40HY-03";
  private static final String REQUEST_ID = "REQ-EXCEL-1";
  private static final Clock SHANGHAI_CLOCK = Clock.fixed(
      Instant.parse("2026-08-30T01:30:00Z"), ZoneId.of("Asia/Shanghai"));

  private HttpServer server;
  private ExecutorService executor;
  private ElectronicDrawingBomProperties properties;
  private final AtomicReference<ResponseSpec> response = new AtomicReference<>();
  private final AtomicReference<String> requestUri = new AtomicReference<>();
  private final AtomicReference<String> requestIdHeader = new AtomicReference<>();
  private final AtomicReference<String> authenticationHeader = new AtomicReference<>();
  private byte[] xlsx;

  @BeforeEach
  void setUp() throws Exception {
    xlsx = workbook();
    response.set(ResponseSpec.success(xlsx));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newSingleThreadExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v1/electronic-drawing/bom-excel", this::respond);
    server.start();

    properties = new ElectronicDrawingBomProperties();
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setExcelBomPath("/api/v1/electronic-drawing/bom-excel");
    properties.setConnectTimeoutMs(300);
    properties.setReadTimeoutMs(500);
    properties.setMaxExcelFileSizeBytes(1024 * 1024);
  }

  @AfterEach
  void tearDown() {
    stopServer();
  }

  @Test
  @DisplayName("200 返回完整二进制和文件来源证据且时间固定为上海口径")
  void acquiresValidatedXlsxEvidence() {
    ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired =
        adapter(builder -> builder.header("X-Test-Authorization", "signed-value"))
            .acquire(new ElectronicDrawingExcelAcquisitionPort.Query(DRAWING_NO, REQUEST_ID));

    assertThat(acquired.content()).isEqualTo(xlsx);
    assertThat(acquired.fileName()).isEqualTo("电子图库明细 J40AH-40HY-03.xlsx");
    assertThat(acquired.contentType())
        .isEqualTo(HttpElectronicDrawingExcelAcquisitionAdapter.XLSX_CONTENT_TYPE);
    assertThat(acquired.fileSize()).isEqualTo(xlsx.length);
    assertThat(acquired.sha256()).isEqualTo(sha256(xlsx));
    assertThat(acquired.drawingNo()).isEqualTo(DRAWING_NO);
    assertThat(acquired.requestId()).isEqualTo(REQUEST_ID);
    assertThat(acquired.sourceSystem()).isEqualTo("MOCK_EXCEL");
    assertThat(acquired.acquiredAt()).isEqualTo(LocalDateTime.of(2026, 8, 30, 9, 30));
    assertThat(requestUri.get()).isEqualTo(
        "/api/v1/electronic-drawing/bom-excel?drawingNo=" + DRAWING_NO);
    assertThat(requestIdHeader.get()).isEqualTo(REQUEST_ID);
    assertThat(authenticationHeader.get()).isEqualTo("signed-value");

    byte[] callerCopy = acquired.content();
    callerCopy[0] = 0;
    assertThat(acquired.content()).isEqualTo(xlsx);
  }

  @Test
  @DisplayName("图号为空、配置为空和响应图号或requestId不一致均被拒绝")
  void rejectsInvalidBindingContext() {
    assertFailure(
        () -> adapter().acquire(new ElectronicDrawingExcelAcquisitionPort.Query(" ", REQUEST_ID)),
        ElectronicDrawingExcelAcquisitionException.REQUEST_INVALID,
        false);

    properties.setBaseUrl(" ");
    assertFailure(
        () -> adapter().acquire(new ElectronicDrawingExcelAcquisitionPort.Query(DRAWING_NO, REQUEST_ID)),
        ElectronicDrawingExcelAcquisitionException.CONFIG_INVALID,
        false);
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

    response.set(ResponseSpec.success(xlsx).withDrawingNo("OTHER-DRAWING"));
    assertFailure(
        () -> adapter().acquire(new ElectronicDrawingExcelAcquisitionPort.Query(DRAWING_NO, REQUEST_ID)),
        ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
        false);

    response.set(ResponseSpec.success(xlsx).withRequestId("OTHER-REQUEST"));
    assertFailure(
        () -> adapter().acquire(new ElectronicDrawingExcelAcquisitionPort.Query(DRAWING_NO, REQUEST_ID)),
        ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
        false);
  }

  @Test
  @DisplayName("Content-Type、Content-Disposition、大小和SHA不符合契约时拒绝")
  void rejectsInvalidFileProtocol() {
    response.set(ResponseSpec.success(xlsx).withContentType("application/json"));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
        false);

    response.set(ResponseSpec.success(xlsx).withContentDisposition("inline; filename=bad.xlsx"));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
        false);

    response.set(ResponseSpec.success(xlsx).withContentDisposition(
        "attachment; filename=../unsafe.xlsx"));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
        false);

    properties.setMaxExcelFileSizeBytes(xlsx.length - 1L);
    response.set(ResponseSpec.success(xlsx));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.FILE_TOO_LARGE,
        false);
    properties.setMaxExcelFileSizeBytes(1024 * 1024);

    response.set(ResponseSpec.success(xlsx).withSha256("0".repeat(64)));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
        true);
  }

  @Test
  @DisplayName("损坏但SHA一致的内容仍被识别为无效xlsx")
  void rejectsCorruptXlsxContainer() {
    byte[] corrupt = "this-is-not-a-valid-xlsx-file".getBytes(StandardCharsets.UTF_8);
    response.set(ResponseSpec.success(corrupt));

    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.FILE_INVALID,
        true);
  }

  @Test
  @DisplayName("404与技术错误严格区分，500、超时和断网均可重试")
  void classifiesNotFoundAndRetryableFailures() {
    response.set(ResponseSpec.error(404));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.BOM_NOT_FOUND,
        false);

    response.set(ResponseSpec.error(500));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
        true);

    properties.setReadTimeoutMs(40);
    response.set(ResponseSpec.success(xlsx).withDelayMs(150));
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
        true);

    properties.setReadTimeoutMs(200);
    stopServer();
    assertFailure(
        () -> acquire(),
        ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
        true);
  }

  @Test
  @DisplayName("空requestId由适配器生成且重试配置保留给后续产品编排")
  void generatesRequestIdAndExposesRetryConfiguration() {
    response.set(ResponseSpec.success(xlsx).withRequestId("REQ-GENERATED"));
    ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired =
        adapter().acquire(new ElectronicDrawingExcelAcquisitionPort.Query(DRAWING_NO, null));
    assertThat(acquired.requestId()).isEqualTo("REQ-GENERATED");
    assertThat(requestIdHeader.get()).isEqualTo("REQ-GENERATED");

    ElectronicDrawingBomProperties defaults = new ElectronicDrawingBomProperties();
    assertThat(defaults.getExcelBomPath())
        .isEqualTo("/api/v1/electronic-drawing/bom-excel");
    assertThat(defaults.getMaxExcelFileSizeBytes()).isEqualTo(10L * 1024 * 1024);
    assertThat(defaults.getRetryMaxAttempts()).isEqualTo(3);
    assertThat(defaults.getRetryInitialBackoffMs()).isEqualTo(500);
  }

  @Test
  @DisplayName("配置型鉴权实现只通过扩展点添加Authorization")
  void configuredAuthenticatorAddsAuthorizationHeader() {
    properties.setAuthorization("  Bearer test-token  ");
    java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(
        URI.create("http://127.0.0.1/test"));

    new ConfiguredElectronicDrawingExcelRequestAuthenticator(properties).authenticate(builder);

    assertThat(builder.GET().build().headers().firstValue("Authorization"))
        .contains("Bearer test-token");
  }

  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquire() {
    return adapter().acquire(
        new ElectronicDrawingExcelAcquisitionPort.Query(DRAWING_NO, REQUEST_ID));
  }

  private HttpElectronicDrawingExcelAcquisitionAdapter adapter() {
    return adapter(builder -> { });
  }

  private HttpElectronicDrawingExcelAcquisitionAdapter adapter(
      ElectronicDrawingExcelRequestAuthenticator authenticator) {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    return new HttpElectronicDrawingExcelAcquisitionAdapter(
        properties, authenticator, client, SHANGHAI_CLOCK, () -> "REQ-GENERATED");
  }

  private void respond(HttpExchange exchange) throws IOException {
    requestUri.set(exchange.getRequestURI().toString());
    requestIdHeader.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
    authenticationHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Authorization"));
    ResponseSpec spec = response.get();
    if (spec.delayMs() > 0) {
      try {
        Thread.sleep(spec.delayMs());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    if (spec.contentType() != null) {
      exchange.getResponseHeaders().set("Content-Type", spec.contentType());
    }
    if (spec.contentDisposition() != null) {
      exchange.getResponseHeaders().set("Content-Disposition", spec.contentDisposition());
    }
    if (spec.drawingNo() != null) {
      exchange.getResponseHeaders().set("X-Drawing-No", spec.drawingNo());
    }
    if (spec.requestId() != null) {
      exchange.getResponseHeaders().set("X-Request-Id", spec.requestId());
    }
    if (spec.sha256() != null) {
      exchange.getResponseHeaders().set("X-File-Sha256", spec.sha256());
    }
    if (spec.source() != null) {
      exchange.getResponseHeaders().set("X-Electronic-Drawing-Source", spec.source());
    }
    byte[] body = spec.body();
    try {
      exchange.sendResponseHeaders(spec.status(), body.length);
      exchange.getResponseBody().write(body);
    } catch (IOException ignored) {
      // 超时测试中客户端主动断开属于预期行为。
    } finally {
      exchange.close();
    }
  }

  private void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  private static void assertFailure(
      ThrowingCallable call, String expectedCode, boolean retryable) {
    assertThatThrownBy(call)
        .isInstanceOf(ElectronicDrawingExcelAcquisitionException.class)
        .satisfies(throwable -> {
          ElectronicDrawingExcelAcquisitionException exception =
              (ElectronicDrawingExcelAcquisitionException) throwable;
          assertThat(exception.getCode()).isEqualTo(expectedCode);
          assertThat(exception.isRetryable()).isEqualTo(retryable);
        });
  }

  private static byte[] workbook() throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      workbook.createSheet("BOM").createRow(0).createCell(0).setCellValue("电子图库");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record ResponseSpec(
      int status,
      byte[] body,
      String contentType,
      String contentDisposition,
      String drawingNo,
      String requestId,
      String sha256,
      String source,
      long delayMs) {

    private static ResponseSpec success(byte[] body) {
      String encoded = URLEncoder.encode(
          "电子图库明细 J40AH-40HY-03.xlsx", StandardCharsets.UTF_8);
      return new ResponseSpec(
          200,
          body,
          HttpElectronicDrawingExcelAcquisitionAdapter.XLSX_CONTENT_TYPE,
          "attachment; filename=electronic-drawing.xlsx; filename*=UTF-8''" + encoded,
          DRAWING_NO,
          REQUEST_ID,
          HttpElectronicDrawingExcelAcquisitionAdapterTest.sha256(body),
          "MOCK_EXCEL",
          0);
    }

    private static ResponseSpec error(int status) {
      return new ResponseSpec(
          status,
          "{}".getBytes(StandardCharsets.UTF_8),
          "application/json",
          null,
          null,
          null,
          null,
          null,
          0);
    }

    private ResponseSpec withContentType(String value) {
      return new ResponseSpec(
          status, body, value, contentDisposition, drawingNo, requestId, sha256, source, delayMs);
    }

    private ResponseSpec withContentDisposition(String value) {
      return new ResponseSpec(
          status, body, contentType, value, drawingNo, requestId, sha256, source, delayMs);
    }

    private ResponseSpec withDrawingNo(String value) {
      return new ResponseSpec(
          status, body, contentType, contentDisposition, value, requestId, sha256, source, delayMs);
    }

    private ResponseSpec withRequestId(String value) {
      return new ResponseSpec(
          status, body, contentType, contentDisposition, drawingNo, value, sha256, source, delayMs);
    }

    private ResponseSpec withSha256(String value) {
      return new ResponseSpec(
          status, body, contentType, contentDisposition, drawingNo, requestId, value, source, delayMs);
    }

    private ResponseSpec withDelayMs(long value) {
      return new ResponseSpec(
          status, body, contentType, contentDisposition, drawingNo, requestId, sha256, source, value);
    }
  }
}
