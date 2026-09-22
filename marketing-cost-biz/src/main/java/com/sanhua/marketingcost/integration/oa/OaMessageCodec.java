package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OaMessageCodec {
  public enum InterfaceType { QUOTE_REQUEST, WORKFLOW_EVENT, TASK_DISPATCH, TECH_SUBMISSION, TECH_RETURN, QUOTE_STATUS, QUOTE_DATA_SUBMIT, QUOTE_RESULT_SAVE, QUOTE_COST_SUBMIT }
  public record Envelope(int schemaVersion, String requestId, String occurredAt,
      JsonNode payload, String rawJson, String hash) {}

  private static final Set<String> CREDENTIAL_FIELDS = Set.of(
      "authorization", "password", "passwd", "token", "accesstoken", "refreshtoken",
      "apikey", "clientsecret", "secret", "cookie");
  private final ObjectMapper json;

  public OaMessageCodec(ObjectMapper objectMapper) {
    json = objectMapper.copy();
    json.getFactory().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(50).build());
  }

  public Envelope decode(String raw, OaPeer peer, InterfaceType type) {
    JsonNode root = read(raw);
    if (root == null || !root.isObject()) throw invalid("INVALID_ENVELOPE", "请求正文必须是 JSON 对象");
    rejectCredentials(root);
    String source = text(root, "sourceSystem", 64);
    String environment = text(root, "environment", 32);
    if (!peer.sourceSystem().equals(source) || !peer.environment().equals(environment)) {
      throw new OaIntegrationException(HttpStatus.FORBIDDEN, "RECEIVE", "PEER_SCOPE_MISMATCH",
          "报文来源或环境与调用方授权不一致");
    }
    JsonNode schema = root.path("schemaVersion");
    if (!schema.isIntegralNumber() || !schema.canConvertToInt()
        || (schema.intValue() != 1 && schema.intValue() != 2)) {
      throw invalid("UNSUPPORTED_SCHEMA", "schemaVersion 仅支持整数 1、2");
    }
    String requestId = text(root, "requestId", 128);
    if (!requestId.matches("[A-Za-z0-9._:-]+")) {
      throw invalid("INVALID_REQUEST_ID", "requestId 只允许字母、数字、点、下划线、冒号及连字符");
    }
    String occurredAt = text(root, "occurredAt", 40);
    try { OffsetDateTime.parse(occurredAt); }
    catch (RuntimeException ex) { throw invalid("INVALID_EVENT_TIME", "occurredAt 必须是带时区的 ISO 时间"); }
    if (!root.path("payload").isObject()) throw invalid("PAYLOAD_REQUIRED", "payload 必须是业务对象");
    return new Envelope(schema.intValue(), requestId, occurredAt, root.get("payload"), raw,
        hash(type.name() + ":" + canonical(root)));
  }

  public JsonNode read(String raw) {
    try { return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw); }
    catch (JsonProcessingException ex) { throw invalid("INVALID_JSON", "JSON 格式不合法、键重复或嵌套过深"); }
  }

  public JsonNode readQuotation(String raw) {
    try {
      return json.reader()
          .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readTree(raw);
    } catch (JsonProcessingException ex) {
      throw invalid("INVALID_JSON", "JSON 格式不合法、键重复或嵌套过深");
    }
  }

  public String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (JsonProcessingException ex) { throw new IllegalStateException("OA result serialization failed", ex); }
  }

  public String canonicalHash(Object value) {
    return hash(canonical(value instanceof JsonNode node ? node : json.valueToTree(value)));
  }

  /** 技术依赖按数值比较；10、10.0、1E+1 不应因 JSON 往返而成为不同依据。 */
  public String dataFingerprint(Object value) {
    return hash(canonical(normalizeNumbers(json.valueToTree(value))));
  }

  /** 已批准依赖保留旧指纹，验证两种已使用的 JSON 数字表示，不修改冻结记录。 */
  public boolean matchesDataFingerprint(String expected, Object value) {
    JsonNode tree = json.valueToTree(value);
    return Objects.equals(expected, dataFingerprint(value))
        || Objects.equals(expected, canonicalHash(tree))
        || Objects.equals(expected, canonicalHash(read(write(tree))));
  }

  private JsonNode normalizeNumbers(JsonNode node) {
    if (node.isNumber()) {
      var number = node.decimalValue().stripTrailingZeros();
      return number.scale() <= 0
          ? json.getNodeFactory().numberNode(number.toBigIntegerExact())
          : json.getNodeFactory().numberNode(number);
    }
    if (node.isObject()) {
      ObjectNode result = json.createObjectNode();
      node.fields().forEachRemaining(field -> result.set(field.getKey(), normalizeNumbers(field.getValue())));
      return result;
    }
    if (node.isArray()) {
      var result = json.createArrayNode();
      node.forEach(value -> result.add(normalizeNumbers(value)));
      return result;
    }
    return node;
  }

  private String canonical(JsonNode node) {
    return canonicalNode(node).toString();
  }

  private JsonNode canonicalNode(JsonNode node) {
    if (node.isObject()) {
      var fields = new TreeMap<String, JsonNode>();
      node.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue()));
      ObjectNode ordered = json.createObjectNode();
      fields.forEach((key, value) -> ordered.set(key, canonicalNode(value)));
      return ordered;
    }
    if (node.isArray()) {
      var array = json.createArrayNode();
      node.forEach(value -> array.add(canonicalNode(value)));
      return array;
    }
    return node;
  }

  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }

  private void rejectCredentials(JsonNode node) {
    if (node.isObject()) node.fields().forEachRemaining(field -> {
      String key = field.getKey().replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
      if (CREDENTIAL_FIELDS.contains(key)) throw invalid("CREDENTIAL_IN_BODY", "业务正文不能包含凭据字段，请通过认证请求头传递");
      rejectCredentials(field.getValue());
    });
    else if (node.isArray()) node.forEach(this::rejectCredentials);
  }

  public static String text(JsonNode node, String key, int maxLength) {
    JsonNode value = node.path(key);
    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength
        || !value.textValue().equals(value.textValue().trim())
        || value.textValue().chars().anyMatch(Character::isISOControl)) {
      throw invalid("INVALID_FIELD", key + " 必须是非空文本，长度不超过 " + maxLength + "，不能有首尾空白或控制字符");
    }
    return value.textValue();
  }

  private static OaIntegrationException invalid(String code, String message) {
    return new OaIntegrationException(HttpStatus.BAD_REQUEST, "RECEIVE", code, message);
  }
}
