package com.sanhua.marketingcost.service.materialshape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 对会影响形态判定的规则内容生成稳定 SHA-256 指纹。 */
@Component
public class ShapePolicyFingerprint {

  private static final String FINGERPRINT_VERSION = "shape-policy-v1";

  private final ObjectMapper objectMapper;

  public ShapePolicyFingerprint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String calculate(MaterialQuoteShapePolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("形态规则不能为空");
    }
    ObjectNode content = objectMapper.createObjectNode();
    content.put("fingerprintVersion", FINGERPRINT_VERSION);
    putNullable(content, "materialOrgCode", upper(policy.getMaterialOrgCode()));
    putNullable(content, "materialCode", trimToNull(policy.getMaterialCode()));
    putNullable(content, "policyMode", upper(policy.getPolicyMode()));
    putNullable(
        content,
        "fixedTargetShape",
        normalizeFixedShape(policy.getFixedTargetShape()));
    content.set(
        "conditionConfig", canonicalJson(policy.getConditionConfigJson()));
    content.set("actionConfig", canonicalJson(policy.getActionConfigJson()));
    putNullable(
        content,
        "effectiveFromMonth",
        trimToNull(policy.getEffectiveFromMonth()));
    putNullable(
        content,
        "effectiveToMonth",
        trimToNull(policy.getEffectiveToMonth()));
    if (policy.getEnabled() == null) {
      content.putNull("enabled");
    } else {
      content.put("enabled", policy.getEnabled());
    }
    try {
      byte[] canonical =
          objectMapper.writeValueAsString(content).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("形态规则无法生成规范内容", ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
    }
  }

  private JsonNode canonicalJson(String json) {
    if (!StringUtils.hasText(json)) {
      return objectMapper.nullNode();
    }
    try {
      return canonicalize(objectMapper.readTree(json));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("形态规则包含非法 JSON，无法计算指纹", ex);
    }
  }

  private JsonNode canonicalize(JsonNode source) {
    if (source == null || source.isNull()) {
      return objectMapper.nullNode();
    }
    if (source.isObject()) {
      ObjectNode result = objectMapper.createObjectNode();
      StreamSupport.stream(
              ((Iterable<String>) () -> source.fieldNames()).spliterator(), false)
          .sorted()
          .forEach(name -> result.set(name, canonicalize(source.get(name))));
      return result;
    }
    if (source.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      source.forEach(item -> result.add(canonicalize(item)));
      return result;
    }
    return source.deepCopy();
  }

  private static String normalizeFixedShape(String value) {
    return StringUtils.hasText(value)
        ? QuoteMaterialShape.normalize(value)
        : null;
  }

  private static String upper(String value) {
    String normalized = trimToNull(value);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static void putNullable(
      ObjectNode target, String fieldName, String value) {
    if (value == null) {
      target.putNull(fieldName);
    } else {
      target.put(fieldName, value);
    }
  }
}
