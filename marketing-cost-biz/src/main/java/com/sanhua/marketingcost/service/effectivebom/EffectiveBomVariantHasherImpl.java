package com.sanhua.marketingcost.service.effectivebom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 使用规范 JSON 和 SHA-256 生成与集合顺序无关的最终树指纹。 */
@Component
public final class EffectiveBomVariantHasherImpl
    implements EffectiveBomVariantHasher {

  private static final String HASH_VERSION = "effective-bom-v1";

  private static final Comparator<EffectiveBomNodeDraft> NODE_ORDER =
      Comparator.comparing(
              EffectiveBomNodeDraft::nodePath,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(
              EffectiveBomNodeDraft::nodeKey,
              Comparator.nullsFirst(Comparator.naturalOrder()));

  private static final Comparator<EffectiveBomExclusion> EXCLUSION_ORDER =
      Comparator.comparing(
              EffectiveBomExclusion::triggerSourcePath,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(
              EffectiveBomExclusion::excludedRootSourcePath,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(
              EffectiveBomExclusion::reasonCode,
              Comparator.nullsFirst(Comparator.naturalOrder()));

  private final ObjectMapper objectMapper;

  public EffectiveBomVariantHasherImpl(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String hash(EffectiveBomVariantInput input) {
    validate(input);
    ObjectNode root = objectMapper.createObjectNode();
    root.put("hashVersion", HASH_VERSION);
    putText(root, "costPeriodMonth", input.costPeriodMonth());
    putText(root, "sourceBomBatchId", input.sourceBomBatchId());
    putText(root, "priceOrgCode", input.priceOrgCode());
    putText(root, "topProductCode", input.topProductCode());
    putText(root, "packageMethod", input.packageMethod());

    ObjectNode selections = objectMapper.createObjectNode();
    normalizedSelections(input.selectedMaterialCodeByGroupKey())
        .forEach((groupKey, materialCode) ->
            putText(selections, groupKey, materialCode));
    root.set("selections", selections);

    ArrayNode nodes = objectMapper.createArrayNode();
    input.buildResult().nodes().stream()
        .sorted(NODE_ORDER)
        .map(this::canonicalNode)
        .forEach(nodes::add);
    root.set("nodes", nodes);

    ArrayNode exclusions = objectMapper.createArrayNode();
    input.buildResult().exclusions().stream()
        .sorted(EXCLUSION_ORDER)
        .map(this::canonicalExclusion)
        .forEach(exclusions::add);
    root.set("exclusions", exclusions);

    try {
      byte[] content =
          objectMapper.writeValueAsBytes(root);
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(content));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("最终有效BOM无法生成规范内容", ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前JDK不支持SHA-256", ex);
    }
  }

  private ObjectNode canonicalNode(EffectiveBomNodeDraft node) {
    ObjectNode value = objectMapper.createObjectNode();
    putText(value, "nodeKey", node.nodeKey());
    putText(value, "parentNodeKey", node.parentNodeKey());
    putInteger(value, "nodeLevel", node.nodeLevel());
    putInteger(value, "sortSeq", node.sortSeq());
    putText(value, "nodePath", node.nodePath());
    putText(value, "materialCode", node.materialCode());
    putText(value, "materialName", node.materialName());
    putText(value, "materialSpec", node.materialSpec());
    putDecimal(value, "qtyPerParent", node.qtyPerParent());
    putDecimal(value, "qtyPerTop", node.qtyPerTop());
    putText(value, "sourceMaterialShape", node.sourceMaterialShape());
    putText(
        value,
        "effectiveMaterialShape",
        node.effectiveMaterialShape() == null
            ? null
            : node.effectiveMaterialShape().name());
    putText(
        value,
        "shapeResolutionSource",
        node.shapeResolutionSource() == null
            ? null
            : node.shapeResolutionSource().name());
    putLong(value, "shapePolicyId", node.shapePolicyId());
    putText(value, "shapePolicyFingerprint", node.shapePolicyFingerprint());
    putLong(value, "selectedSupplierRatioId", node.selectedSupplierRatioId());
    putText(value, "selectedSupplierCode", node.selectedSupplierCode());
    putText(value, "selectedSupplierName", node.selectedSupplierName());
    putDecimal(value, "selectedSupplyRatio", node.selectedSupplyRatio());
    putText(value, "alternativeGroupKey", node.alternativeGroupKey());
    putText(value, "alternativeChildType", node.alternativeChildType());
    putText(
        value,
        "alternativeSelectionSource",
        node.alternativeSelectionSource());
    putText(value, "sourceBomType", node.sourceBomType());
    putText(value, "sourceBomBatchId", node.sourceBomBatchId());
    putLong(value, "sourceHierarchyId", node.sourceHierarchyId());
    putText(value, "sourceNodePath", node.sourceNodePath());
    return value;
  }

  private ObjectNode canonicalExclusion(EffectiveBomExclusion exclusion) {
    ObjectNode value = objectMapper.createObjectNode();
    putText(value, "reasonCode", exclusion.reasonCode());
    putText(value, "triggerMaterialCode", exclusion.triggerMaterialCode());
    putText(value, "triggerSourcePath", exclusion.triggerSourcePath());
    putText(
        value,
        "excludedRootMaterialCode",
        exclusion.excludedRootMaterialCode());
    putText(
        value,
        "excludedRootSourcePath",
        exclusion.excludedRootSourcePath());
    putInteger(value, "excludedNodeCount", exclusion.excludedNodeCount());
    putText(value, "message", exclusion.message());
    return value;
  }

  private static void validate(EffectiveBomVariantInput input) {
    if (input == null) {
      throw new IllegalArgumentException("最终有效BOM指纹输入不能为空");
    }
    requireText(input.costPeriodMonth(), "核算月份");
    if (!input.costPeriodMonth().trim().matches("\\d{4}-(0[1-9]|1[0-2])")) {
      throw new IllegalArgumentException("核算月份必须为yyyy-MM");
    }
    requireText(input.sourceBomBatchId(), "原始BOM批次");
    requireText(input.priceOrgCode(), "U9组织");
    requireText(input.topProductCode(), "顶层产品料号");
    if (input.buildResult() == null) {
      throw new IllegalArgumentException("最终有效BOM构建结果不能为空");
    }
    if (input.buildResult().blocked()) {
      throw new IllegalArgumentException("最终有效BOM仍有阻断项，不能确认或计算指纹");
    }
    if (input.buildResult().nodes().isEmpty()) {
      throw new IllegalArgumentException("最终有效BOM节点为空");
    }
  }

  private static void requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
  }

  private static Map<String, String> normalizedSelections(
      Map<String, String> source) {
    Map<String, String> result = new TreeMap<>();
    for (Map.Entry<String, String> entry : source.entrySet()) {
      String groupKey = normalize(entry.getKey());
      String materialCode = normalize(entry.getValue());
      if (groupKey == null || materialCode == null) {
        throw new IllegalArgumentException("标准/替代选择组键和选中料号不能为空");
      }
      if (result.putIfAbsent(groupKey, materialCode) != null) {
        throw new IllegalArgumentException(
            "标准/替代选择组键规范化后重复: " + groupKey);
      }
    }
    return result;
  }

  private static void putText(ObjectNode target, String field, String value) {
    String normalized = normalize(value);
    if (normalized == null) {
      target.putNull(field);
    } else {
      target.put(field, normalized);
    }
  }

  private static void putLong(ObjectNode target, String field, Long value) {
    if (value == null) {
      target.putNull(field);
    } else {
      target.put(field, value);
    }
  }

  private static void putInteger(
      ObjectNode target, String field, Integer value) {
    if (value == null) {
      target.putNull(field);
    } else {
      target.put(field, value);
    }
  }

  private static void putDecimal(
      ObjectNode target, String field, BigDecimal value) {
    if (value == null) {
      target.putNull(field);
    } else {
      target.put(field, value.stripTrailingZeros().toPlainString());
    }
  }

  private static String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
