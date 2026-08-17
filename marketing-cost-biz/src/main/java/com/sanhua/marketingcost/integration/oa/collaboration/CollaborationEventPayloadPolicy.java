package com.sanhua.marketingcost.integration.oa.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** OA只接收状态摘要，禁止完整技术、价格和认证明细进入事件。 */
@Component
public class CollaborationEventPayloadPolicy {

  private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
  private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
      "formula", "expression", "ticket", "token", "password", "secret",
      "authorization", "credential", "productcodes", "productids", "productnos");
  private static final Set<String> FORBIDDEN_KEYS = Set.of(
      "amount", "unitprice", "purchaseprice", "settlementprice", "costamount",
      "bomtree", "bomnodes", "bomlines", "pricedraftfields", "rangerows",
      "products", "productlist", "productcodes", "productids", "productnos",
      "otherproduct", "otherproducts", "quotationlines");

  private final ObjectMapper objectMapper;

  public CollaborationEventPayloadPolicy(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void requireSafe(JsonNode data) {
    if (data == null || !data.isObject()) {
      throw new IllegalArgumentException("OA事件data必须是JSON对象");
    }
    inspect(data);
    try {
      if (objectMapper.writeValueAsBytes(data).length > MAX_PAYLOAD_BYTES) {
        throw new IllegalArgumentException("OA事件data不得超过256KB");
      }
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("OA事件data无法序列化", exception);
    }
  }

  private void inspect(JsonNode node) {
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String normalized = field.getKey().replaceAll("[^A-Za-z0-9]", "")
            .toLowerCase(Locale.ROOT);
        if (FORBIDDEN_KEYS.contains(normalized)
            || FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
          throw new IllegalArgumentException("OA事件禁止包含敏感或越权字段：" + field.getKey());
        }
        inspect(field.getValue());
      }
      return;
    }
    if (node.isArray()) {
      node.forEach(this::inspect);
    }
  }
}
