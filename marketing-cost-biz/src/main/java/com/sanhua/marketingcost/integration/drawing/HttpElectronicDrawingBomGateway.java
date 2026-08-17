package com.sanhua.marketingcost.integration.drawing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 电子图库 HTTP 适配器；只做协议转换，不相信上游的 valid/complete 布尔值。 */
@Component
@ConditionalOnProperty(
    prefix = "quote.collaboration.electronic-drawing",
    name = "mode",
    havingValue = "HTTP")
public class HttpElectronicDrawingBomGateway implements ElectronicDrawingBomGateway {

  private final ElectronicDrawingBomProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  @Autowired
  public HttpElectronicDrawingBomGateway(
      ElectronicDrawingBomProperties properties,
      ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());
  }

  HttpElectronicDrawingBomGateway(
      ElectronicDrawingBomProperties properties,
      ObjectMapper objectMapper,
      HttpClient client) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.client = client;
  }

  @Override
  public ElectronicBomFetchResult fetchCurrentBom(ElectronicBomQuery query) {
    try {
      requireQuery(query);
      URI uri = endpoint();
      ObjectNode body = objectMapper.createObjectNode();
      body.put("productCode", query.productCode());
      body.put("materialOrganizationCode", query.materialOrganizationCode());
      put(body, "priceOrganizationCode", query.priceOrganizationCode());
      put(body, "bomPurpose", query.bomPurpose());
      if (query.asOfDate() != null) body.put("asOfDate", query.asOfDate().toString());
      put(body, "requestId", query.requestId());
      HttpRequest.Builder request = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
      if (StringUtils.hasText(properties.getAuthorization())) {
        request.header("Authorization", properties.getAuthorization().trim());
      }
      HttpResponse<String> response = client.send(
          request.build(), HttpResponse.BodyHandlers.ofString());
      return switch (response.statusCode()) {
        case 200 -> parse(response.body());
        case 401, 403 -> ElectronicBomFetchResult.failure(
            ElectronicBomFetchResult.Status.FORBIDDEN, "电子图库拒绝当前查询权限");
        case 404 -> ElectronicBomFetchResult.failure(
            ElectronicBomFetchResult.Status.NOT_FOUND, "电子图库未找到当前产品的有效BOM");
        default -> ElectronicBomFetchResult.failure(
            ElectronicBomFetchResult.Status.UPSTREAM_ERROR,
            "电子图库查询失败，HTTP " + response.statusCode());
      };
    } catch (HttpTimeoutException exception) {
      return ElectronicBomFetchResult.failure(
          ElectronicBomFetchResult.Status.TIMEOUT, "电子图库查询超时");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return ElectronicBomFetchResult.failure(
          ElectronicBomFetchResult.Status.UPSTREAM_ERROR, "电子图库查询被中断");
    } catch (Exception exception) {
      return ElectronicBomFetchResult.failure(
          ElectronicBomFetchResult.Status.UPSTREAM_ERROR,
          "电子图库响应无法读取：" + safeMessage(exception));
    }
  }

  private ElectronicBomFetchResult parse(String body) throws Exception {
    JsonNode root = objectMapper.readTree(body);
    String versionStatus = text(root, "versionStatus");
    if ("VOID".equalsIgnoreCase(versionStatus) || "VOIDED".equalsIgnoreCase(versionStatus)) {
      return ElectronicBomFetchResult.failure(
          ElectronicBomFetchResult.Status.VOID, "电子图库返回的BOM版本已作废");
    }
    List<ElectronicBomNode> nodes = new ArrayList<>();
    JsonNode rows = root.path("nodes");
    if (rows.isArray()) {
      for (JsonNode row : rows) {
        nodes.add(new ElectronicBomNode(
            text(row, "nodeKey"), text(row, "parentNodeKey"), integer(row, "level"),
            text(row, "materialCode"), text(row, "materialName"),
            text(row, "materialSpec"), text(row, "materialModel"),
            text(row, "drawingNo"), text(row, "materialNature"),
            decimal(row, "quantityPerParent"), text(row, "unit"),
            integer(row, "sortSeq"), bool(row, "active")));
      }
    }
    return new ElectronicBomFetchResult(ElectronicBomFetchResult.Status.FOUND,
        text(root, "message"), text(root, "sourceSystem"), text(root, "productCode"),
        text(root, "materialOrganizationCode"), text(root, "bomPurpose"),
        text(root, "sourceVersion"), versionStatus,
        date(root, "effectiveFrom"), date(root, "effectiveTo"),
        offsetDateTime(root, "queriedAt"), nodes);
  }

  private URI endpoint() {
    String base = requireText(properties.getBaseUrl(), "电子图库base-url不能为空");
    String path = requireText(properties.getCurrentBomPath(), "电子图库current-bom-path不能为空");
    return URI.create(base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", ""));
  }

  private static void requireQuery(ElectronicBomQuery query) {
    if (query == null) throw new IllegalArgumentException("电子图库查询不能为空");
    requireText(query.productCode(), "电子图库查询产品料号不能为空");
    requireText(query.materialOrganizationCode(), "电子图库查询物料组织不能为空");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() || !StringUtils.hasText(value.asText())
        ? null : value.asText().trim();
  }

  private static Integer integer(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() || !value.canConvertToInt() ? null : value.asInt();
  }

  private static Boolean bool(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asBoolean();
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    String value = text(node, field);
    return value == null ? null : new BigDecimal(value);
  }

  private static LocalDate date(JsonNode node, String field) {
    String value = text(node, field);
    return value == null ? null : LocalDate.parse(value);
  }

  private static OffsetDateTime offsetDateTime(JsonNode node, String field) {
    String value = text(node, field);
    return value == null ? null : OffsetDateTime.parse(value);
  }

  private static String requireText(String value, String message) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
    return value.trim();
  }

  private static void put(ObjectNode node, String field, String value) {
    if (StringUtils.hasText(value)) node.put(field, value.trim());
  }

  private static String safeMessage(Exception exception) {
    return StringUtils.hasText(exception.getMessage())
        ? exception.getMessage().trim() : exception.getClass().getSimpleName();
  }
}
