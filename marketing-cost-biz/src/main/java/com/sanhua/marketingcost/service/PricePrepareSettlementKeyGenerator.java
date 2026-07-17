package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.util.StringUtils;

/** 跨价格场景保持不变的结算行/组件行稳定键生成器。 */
public final class PricePrepareSettlementKeyGenerator {

  private static final String SETTLEMENT_PREFIX = "SET:v1:";
  private static final String COMPONENT_PREFIX = "CMP:v1:";

  private PricePrepareSettlementKeyGenerator() {
  }

  public static String settlementKey(
      Long oaFormItemId, String topProductCode, PricePreparePlanItem planItem) {
    if (planItem == null) {
      throw new IllegalArgumentException("价格准备计划行不能为空");
    }
    BomCostingRow row = planItem.getBomRow();
    Long quoteItemId = oaFormItemId != null
        ? oaFormItemId
        : row == null ? null : row.getOaFormItemId();
    String top = firstText(
        topProductCode,
        planItem.getTopProductCode(),
        row == null ? null : row.getTopProductCode());
    String material = firstText(
        planItem.getMaterialCode(),
        row == null ? null : row.getMaterialCode());
    String settlementType = firstText(
        row == null ? null : row.getSettlementRowType(),
        planItem.getItemType(),
        "UNSPECIFIED");

    StringBuilder canonical = new StringBuilder();
    append(canonical, "oaFormItemId", quoteItemId == null ? "LEGACY" : quoteItemId.toString());
    append(canonical, "topProductCode", required(top, "topProductCode"));
    append(canonical, "position", stablePosition(row, planItem.getBomRowId()));
    append(canonical, "materialCode", required(material, "materialCode"));
    append(canonical, "settlementType", settlementType);
    return SETTLEMENT_PREFIX + sha256(canonical.toString());
  }

  /** 制造件子原材料解释行使用独立组件键，并由调用方保存 parentSettlementKey。 */
  public static String componentKey(
      String parentSettlementKey,
      String parentMaterialCode,
      String childMaterialCode,
      String componentPosition) {
    StringBuilder canonical = new StringBuilder();
    append(canonical, "parentSettlementKey",
        required(parentSettlementKey, "parentSettlementKey"));
    append(canonical, "parentMaterialCode",
        required(parentMaterialCode, "parentMaterialCode"));
    append(canonical, "childMaterialCode",
        required(childMaterialCode, "childMaterialCode"));
    append(canonical, "componentPosition",
        firstText(componentPosition, childMaterialCode));
    return COMPONENT_PREFIX + sha256(canonical.toString());
  }

  private static String stablePosition(BomCostingRow row, Long planBomRowId) {
    String path = row == null ? null : trimToNull(row.getPath());
    if (path != null) {
      return "PATH:" + path;
    }
    Long rawHierarchyNodeId = row == null ? null : row.getRawHierarchyNodeId();
    if (rawHierarchyNodeId != null) {
      return "RAW:" + rawHierarchyNodeId;
    }
    Long bomRowId = planBomRowId != null ? planBomRowId : row == null ? null : row.getId();
    if (bomRowId != null) {
      return "ROW:" + bomRowId;
    }
    String parentCode = row == null ? null : trimToNull(row.getParentCode());
    Integer level = row == null ? null : row.getLevel();
    return "FALLBACK:" + (parentCode == null ? "ROOT" : parentCode)
        + ":" + (level == null ? "?" : level);
  }

  private static void append(StringBuilder target, String name, String value) {
    String normalized = value == null ? "" : value;
    target.append(name)
        .append('=')
        .append(normalized.length())
        .append(':')
        .append(normalized)
        .append(';');
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前JVM不支持SHA-256", ex);
    }
  }

  private static String required(String value, String fieldName) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + "不能为空，无法生成稳定结算键");
    }
    return normalized;
  }

  private static String firstText(String... values) {
    if (values != null) {
      for (String value : values) {
        String normalized = trimToNull(value);
        if (normalized != null) {
          return normalized;
        }
      }
    }
    return null;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
