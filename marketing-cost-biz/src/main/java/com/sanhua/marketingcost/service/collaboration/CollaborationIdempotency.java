package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** payload摘要与重复请求判定；持久化位置由具体命令的应用服务负责。 */
@Component
public class CollaborationIdempotency {

  private final ObjectMapper objectMapper;

  public CollaborationIdempotency() {
    this.objectMapper = new ObjectMapper();
  }

  public String payloadHash(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      throw new IllegalArgumentException("幂等payload不能为空");
    }
    try {
      JsonNode node = objectMapper.readTree(payloadJson);
      return sha256(objectMapper.writeValueAsString(canonicalize(node)));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("幂等payload必须是合法JSON", exception);
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      ObjectNode canonical = objectMapper.createObjectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      names.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
      return canonical;
    }
    if (node.isArray()) {
      ArrayNode canonical = objectMapper.createArrayNode();
      node.forEach(item -> canonical.add(canonicalize(item)));
      return canonical;
    }
    return node;
  }

  public Decision check(String existingPayloadHash, String requestedPayloadHash) {
    if (existingPayloadHash == null || existingPayloadHash.isBlank()) {
      return Decision.FIRST_REQUEST;
    }
    if (existingPayloadHash.equals(requestedPayloadHash)) {
      return Decision.REPLAY;
    }
    throw new CollaborationDomainException(
        CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
        "同一幂等键的请求内容与首次请求不一致");
  }

  private static String sha256(String payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }

  public enum Decision {
    FIRST_REQUEST,
    REPLAY
  }
}
