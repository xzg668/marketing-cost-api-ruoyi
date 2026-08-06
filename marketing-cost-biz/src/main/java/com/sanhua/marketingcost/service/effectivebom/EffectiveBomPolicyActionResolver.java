package com.sanhua.marketingcost.service.effectivebom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将规则动作 JSON 转成纯树构建器可执行的通用结构动作。 */
@Component
public final class EffectiveBomPolicyActionResolver {

  private static final String EXCLUDED_DIRECT_CHILD_CODES =
      "excludedDirectChildMaterialCodes";

  private final ObjectMapper objectMapper;

  public EffectiveBomPolicyActionResolver(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public EffectiveBomPolicyAction resolve(EffectiveBomShapeDecision decision) {
    if (decision == null || !StringUtils.hasText(decision.actionConfigJson())) {
      return EffectiveBomPolicyAction.none();
    }
    JsonNode root = readObject(decision.actionConfigJson());
    JsonNode excludedCodes = root.get(EXCLUDED_DIRECT_CHILD_CODES);
    if (excludedCodes == null || excludedCodes.isNull()) {
      return EffectiveBomPolicyAction.none();
    }
    if (!excludedCodes.isArray()) {
      throw new IllegalArgumentException(
          "动作JSON的" + EXCLUDED_DIRECT_CHILD_CODES + "必须是数组");
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (JsonNode item : excludedCodes) {
      String materialCode =
          item.isTextual() && StringUtils.hasText(item.asText())
              ? item.asText().trim()
              : null;
      if (materialCode == null) {
        throw new IllegalArgumentException(
            "动作JSON的" + EXCLUDED_DIRECT_CHILD_CODES + "不能包含空料号");
      }
      normalized.add(materialCode);
    }
    return new EffectiveBomPolicyAction(normalized);
  }

  private JsonNode readObject(String json) {
    try {
      JsonNode root = objectMapper.readTree(json);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("动作JSON必须是JSON对象");
      }
      return root;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("动作JSON格式非法", ex);
    }
  }
}
