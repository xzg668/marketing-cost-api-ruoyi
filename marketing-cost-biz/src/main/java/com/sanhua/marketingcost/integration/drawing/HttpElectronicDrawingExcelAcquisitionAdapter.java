package com.sanhua.marketingcost.integration.drawing;

import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionPort;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 电子图库 Excel 二进制 HTTP 适配器；只下载并校验来源证据，不解析业务明细。 */
@Component
public class HttpElectronicDrawingExcelAcquisitionAdapter
    implements ElectronicDrawingExcelAcquisitionPort {

  static final String XLSX_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String HEADER_REQUEST_ID = "X-Request-Id";
  private static final String HEADER_DRAWING_NO = "X-Drawing-No";
  private static final String HEADER_FILE_SHA256 = "X-File-Sha256";
  private static final String HEADER_SOURCE = "X-Electronic-Drawing-Source";
  private static final Pattern UTF8_FILE_NAME = Pattern.compile(
      "(?i)(?:^|;)\\s*filename\\*=UTF-8''([^;]+)");
  private static final Pattern BASIC_FILE_NAME = Pattern.compile(
      "(?i)(?:^|;)\\s*filename=(?:\"([^\"]+)\"|([^;]+))");

  private final ElectronicDrawingBomProperties properties;
  private final ElectronicDrawingExcelRequestAuthenticator authenticator;
  private final HttpClient client;
  private final Clock clock;
  private final Supplier<String> requestIdSupplier;

  @Autowired
  public HttpElectronicDrawingExcelAcquisitionAdapter(
      ElectronicDrawingBomProperties properties,
      ElectronicDrawingExcelRequestAuthenticator authenticator) {
    this(
        properties,
        authenticator,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Clock.system(CostPricingPeriodUtils.BUSINESS_ZONE),
        () -> "EDRAW-" + UUID.randomUUID());
  }

  HttpElectronicDrawingExcelAcquisitionAdapter(
      ElectronicDrawingBomProperties properties,
      ElectronicDrawingExcelRequestAuthenticator authenticator,
      HttpClient client,
      Clock clock,
      Supplier<String> requestIdSupplier) {
    this.properties = properties;
    this.authenticator = authenticator;
    this.client = client;
    this.clock = clock;
    this.requestIdSupplier = requestIdSupplier;
  }

  @Override
  public AcquiredExcel acquire(Query query) {
    String drawingNo = requiredQueryDrawingNo(query);
    String requestId = requestId(query);
    HttpRequest request = request(drawingNo, requestId);
    try {
      HttpResponse<InputStream> response = client.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        return switch (response.statusCode()) {
          case 200 -> success(response, body, drawingNo, requestId);
          case 401, 403 -> throw failure(
              ElectronicDrawingExcelAcquisitionException.ACCESS_DENIED,
              false,
              "电子图库拒绝当前接口凭证");
          case 404 -> throw failure(
              ElectronicDrawingExcelAcquisitionException.BOM_NOT_FOUND,
              false,
              "电子图库没有该图号的 BOM 明细");
          default -> {
            if (response.statusCode() >= 500) {
              throw failure(
                  ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
                  true,
                  "电子图库服务暂时不可用");
            }
            throw failure(
                ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
                false,
                "电子图库返回了不支持的 HTTP 状态");
          }
        };
      }
    } catch (HttpTimeoutException exception) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
          true,
          "电子图库查询超时",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure(
          ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
          true,
          "电子图库查询被中断",
          exception);
    } catch (IOException exception) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.QUERY_RETRY,
          true,
          "电子图库网络连接失败",
          exception);
    }
  }

  private AcquiredExcel success(
      HttpResponse<InputStream> response,
      InputStream body,
      String requestedDrawingNo,
      String requestedRequestId) throws IOException {
    String contentType = contentType(response);
    String fileName = fileName(response);
    String responseDrawingNo = requiredHeader(response, HEADER_DRAWING_NO);
    if (!requestedDrawingNo.equals(responseDrawingNo)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应图号与请求图号不一致");
    }
    String responseRequestId = requiredHeader(response, HEADER_REQUEST_ID);
    if (!requestedRequestId.equals(responseRequestId)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应 requestId 与本次请求不一致");
    }

    long maxSize = properties.getMaxExcelFileSizeBytes();
    long declaredSize = contentLength(response);
    if (declaredSize > maxSize) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.FILE_TOO_LARGE,
          false,
          "电子图库 Excel 超过允许大小");
    }
    byte[] content = readLimited(body, maxSize);
    if (content.length == 0) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.FILE_INVALID,
          true,
          "电子图库返回了空 Excel");
    }
    if (declaredSize >= 0 && declaredSize != content.length) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          true,
          "电子图库响应文件大小与 Content-Length 不一致");
    }
    String sha256 = sha256(content);
    String declaredSha256 = requiredHeader(response, HEADER_FILE_SHA256);
    if (!isSha256(declaredSha256) || !sha256.equalsIgnoreCase(declaredSha256)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          true,
          "电子图库 Excel 的 SHA-256 校验失败");
    }
    validateXlsxContainer(content);
    String source = optionalHeader(response, HEADER_SOURCE);
    return new AcquiredExcel(
        content,
        fileName,
        contentType,
        content.length,
        sha256,
        responseDrawingNo,
        responseRequestId,
        source,
        LocalDateTime.now(clock));
  }

  private HttpRequest request(String drawingNo, String requestId) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(drawingNo))
        .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
        .header("Accept", XLSX_CONTENT_TYPE)
        .header(HEADER_REQUEST_ID, requestId)
        .GET();
    authenticator.authenticate(builder);
    return builder.build();
  }

  private URI endpoint(String drawingNo) {
    String base = requiredConfig(properties.getBaseUrl(), "电子图库 base-url 不能为空");
    String path = requiredConfig(properties.getExcelBomPath(), "电子图库 excel-bom-path 不能为空");
    String endpoint = base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    String separator = endpoint.contains("?") ? "&" : "?";
    try {
      return URI.create(endpoint + separator + "drawingNo="
          + URLEncoder.encode(drawingNo, StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.CONFIG_INVALID,
          false,
          "电子图库接口地址配置无效",
          exception);
    }
  }

  private String contentType(HttpResponse<?> response) {
    String raw = requiredHeader(response, "Content-Type");
    String normalized = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!XLSX_CONTENT_TYPE.equals(normalized)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应 Content-Type 不是 xlsx");
    }
    return normalized;
  }

  private String fileName(HttpResponse<?> response) {
    String disposition = requiredHeader(response, "Content-Disposition");
    if (!disposition.toLowerCase(Locale.ROOT).startsWith("attachment")) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应不是附件下载");
    }
    String fileName = null;
    Matcher utf8 = UTF8_FILE_NAME.matcher(disposition);
    if (utf8.find()) {
      try {
        fileName = URLDecoder.decode(utf8.group(1).trim(), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException exception) {
        throw failure(
            ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
            false,
            "电子图库响应文件名编码无效",
            exception);
      }
    }
    if (!StringUtils.hasText(fileName)) {
      Matcher basic = BASIC_FILE_NAME.matcher(disposition);
      if (basic.find()) fileName = basic.group(1) == null ? basic.group(2) : basic.group(1);
    }
    if (!StringUtils.hasText(fileName)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应缺少文件名");
    }
    fileName = fileName.trim();
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")
        || fileName.contains("/")
        || fileName.contains("\\")
        || fileName.chars().anyMatch(Character::isISOControl)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应文件名无效");
    }
    return fileName;
  }

  private static byte[] readLimited(InputStream input, long maxSize) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      total += read;
      if (total > maxSize) {
        throw failure(
            ElectronicDrawingExcelAcquisitionException.FILE_TOO_LARGE,
            false,
            "电子图库 Excel 超过允许大小");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static void validateXlsxContainer(byte[] content) {
    boolean contentTypes = false;
    boolean workbook = false;
    try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(content))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
        if ("xl/workbook.xml".equals(entry.getName())) workbook = true;
        if (contentTypes && workbook) return;
      }
    } catch (IOException exception) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.FILE_INVALID,
          true,
          "电子图库返回的文件不是有效 xlsx",
          exception);
    }
    throw failure(
        ElectronicDrawingExcelAcquisitionException.FILE_INVALID,
        true,
        "电子图库返回的文件不是有效 xlsx");
  }

  private static long contentLength(HttpResponse<?> response) {
    String raw = optionalHeader(response, "Content-Length");
    if (raw == null) return -1;
    try {
      long value = Long.parseLong(raw);
      if (value < 0) throw new NumberFormatException("negative");
      return value;
    } catch (NumberFormatException exception) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应 Content-Length 无效",
          exception);
    }
  }

  private static String requiredQueryDrawingNo(Query query) {
    if (query == null || !StringUtils.hasText(query.drawingNo())) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.REQUEST_INVALID,
          false,
          "电子图库查询图号不能为空");
    }
    return query.drawingNo().trim();
  }

  private String requestId(Query query) {
    String requestId = query == null ? null : query.requestId();
    if (!StringUtils.hasText(requestId)) requestId = requestIdSupplier.get();
    if (!StringUtils.hasText(requestId)
        || requestId.length() > 128
        || requestId.chars().anyMatch(Character::isISOControl)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.REQUEST_INVALID,
          false,
          "电子图库请求标识无效");
    }
    return requestId.trim();
  }

  private static String requiredConfig(String value, String message) {
    if (!StringUtils.hasText(value)) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.CONFIG_INVALID,
          false,
          message);
    }
    return value.trim();
  }

  private static String requiredHeader(HttpResponse<?> response, String name) {
    String value = optionalHeader(response, name);
    if (value == null) {
      throw failure(
          ElectronicDrawingExcelAcquisitionException.RESPONSE_INVALID,
          false,
          "电子图库响应缺少 " + name);
    }
    return value;
  }

  private static String optionalHeader(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .orElse(null);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception exception) {
      throw new IllegalStateException("JVM 不支持 SHA-256", exception);
    }
  }

  private static boolean isSha256(String value) {
    return value != null && value.matches("(?i)[0-9a-f]{64}");
  }

  private static ElectronicDrawingExcelAcquisitionException failure(
      String code, boolean retryable, String message) {
    return new ElectronicDrawingExcelAcquisitionException(code, retryable, message);
  }

  private static ElectronicDrawingExcelAcquisitionException failure(
      String code, boolean retryable, String message, Throwable cause) {
    return new ElectronicDrawingExcelAcquisitionException(code, retryable, message, cause);
  }
}
