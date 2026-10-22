package com.sanhua.marketingcost.integration.drawing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** 仅验证协议结构；零差异采用规则及报价归属由业务层处理。 */
@Component
public class DrawingVerificationCodec {
  private static final Set<String> SUMMARY_FIELDS = Set.of(
      "taskId", "status", "differenceCount", "data", "plm_data", "verificationStatus", "editUrl",
      "reviewReasons", "expiresAt", "revision", "confirmedAt", "deliveryStatus", "deliveryAttempts",
      "engine", "drafts", "draftEngine", "schemaVersion");
  private static final List<String> ITEM_FIELDS =
      List.of("no", "code", "name", "qty", "mat", "grade", "hsf", "weight", "note");
  private final ObjectMapper json;

  public DrawingVerificationCodec(ObjectMapper json) {
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }

  public DrawingVerificationPayload parse(String rawJson) {
    JsonNode root;
    try {
      root = json.readTree(rawJson);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      // 不把可能包含签名 URL 的非法正文拼入异常或日志。
      throw invalid("响应不是有效 JSON");
    }
    if (root == null || !root.isObject()) throw invalid("摘要必须是对象");
    for (String field : SUMMARY_FIELDS) require(root.has(field), "缺少字段 " + field);
    String taskId = text(root, "taskId");
    require(taskId != null && !taskId.isBlank(), "taskId 不能为空");
    int differenceCount = nonNegativeInt(root, "differenceCount");
    int revision = nonNegativeInt(root, "revision");
    int schemaVersion = nonNegativeInt(root, "schemaVersion");
    require(schemaVersion == 2, "schemaVersion 必须为 2");
    require(root.get("data").isObject(), "data 必须是对象");
    require(root.get("plm_data").isArray(), "plm_data 必须是数组");
    require(root.get("drafts").isObject(), "drafts 必须是对象");
    require(root.get("reviewReasons").isArray(), "reviewReasons 必须是数组");
    var reasons = new ArrayList<String>();
    for (JsonNode reason : root.get("reviewReasons")) {
      require(reason.isTextual(), "reviewReasons 元素必须是字符串");
      reasons.add(reason.textValue());
    }
    var drawings = new LinkedHashMap<String, DrawingVerificationSummary.Drawing>();
    root.get("data").fields().forEachRemaining(entry -> {
      JsonNode drawing = entry.getValue();
      require(!entry.getKey().isBlank() && drawing.isObject(), "图号分组无效");
      require(drawing.has("company"), "图号分组缺少 company");
      JsonNode params = drawing.path("params");
      require(params.isObject() && params.path("items").isArray(), "params.items 必须是数组");
      var items = new ArrayList<DrawingVerificationSummary.Item>();
      for (JsonNode item : params.get("items")) {
        require(item.isObject() && item.size() == ITEM_FIELDS.size(), "明细必须包含固定九字段");
        for (String field : ITEM_FIELDS) require(item.has(field), "明细缺少字段 " + field);
        items.add(new DrawingVerificationSummary.Item(text(item, "no"), text(item, "code"),
            text(item, "name"), text(item, "qty"), text(item, "mat"), text(item, "grade"),
            text(item, "hsf"), text(item, "weight"), text(item, "note")));
      }
      drawings.put(entry.getKey(), new DrawingVerificationSummary.Drawing(text(drawing, "company"),
          new DrawingVerificationSummary.Parameters(items)));
    });
    var summary = new DrawingVerificationSummary(taskId, text(root, "status"), differenceCount,
        drawings, root.get("plm_data"), text(root, "verificationStatus"), text(root, "editUrl"),
        reasons, nullableLong(root, "expiresAt"), revision, nullableLong(root, "confirmedAt"),
        text(root, "deliveryStatus"), nonNegativeInt(root, "deliveryAttempts"), text(root, "engine"),
        root.get("drafts"), text(root, "draftEngine"), schemaVersion);
    ObjectNode fingerprintInput = root.deepCopy();
    fingerprintInput.remove("deliveryAttempts");
    return new DrawingVerificationPayload(summary, rawJson, root.get("data").toString(),
        root.get("plm_data").toString(), sha256(canonical(fingerprintInput).toString()));
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode ordered = json.createObjectNode();
      var keys = new TreeSet<String>();
      node.fieldNames().forEachRemaining(keys::add);
      keys.forEach(key -> ordered.set(key, canonical(node.get(key))));
      return ordered;
    }
    if (node.isArray()) {
      ArrayNode ordered = json.createArrayNode();
      node.forEach(value -> ordered.add(canonical(value)));
      return ordered;
    }
    return node;
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    require(value != null && (value.isNull() || value.isTextual()), field + " 必须是字符串或 null");
    return value.isNull() ? null : value.textValue();
  }

  private static int nonNegativeInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    require(value != null && value.isIntegralNumber() && value.canConvertToInt()
        && value.intValue() >= 0, field + " 必须是非负整数");
    return value.intValue();
  }

  private static Long nullableLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) return null;
    require(value.isIntegralNumber() && value.canConvertToLong(), field + " 必须是毫秒整数或 null");
    return value.longValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw invalid(message);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("电子图库协议错误：" + message);
  }
}
