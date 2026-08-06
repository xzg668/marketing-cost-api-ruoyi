package com.sanhua.marketingcost.enums;

import java.util.Locale;
import org.springframework.util.StringUtils;

/** 报价有效 BOM 使用的稳定形态编码。 */
public enum QuoteMaterialShape {
  MANUFACTURE("制造件"),
  PURCHASE("采购件"),
  OUTSOURCE("委外加工件"),
  VIRTUAL("虚拟");

  private final String label;

  QuoteMaterialShape(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  /** 配置入口接受稳定编码及常见中文显示值，落库统一保存稳定编码。 */
  public static String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("目标形态不能为空");
    }
    String normalized = value.trim();
    for (QuoteMaterialShape shape : values()) {
      if (shape.name().equalsIgnoreCase(normalized)
          || shape.label.equals(normalized)) {
        return shape.name();
      }
    }
    if ("自制件".equals(normalized)) {
      return MANUFACTURE.name();
    }
    if ("委外件".equals(normalized)) {
      return OUTSOURCE.name();
    }
    if ("虚拟件".equals(normalized)) {
      return VIRTUAL.name();
    }
    throw new IllegalArgumentException(
        "非法形态: "
            + normalized.toUpperCase(Locale.ROOT)
            + "，仅支持 MANUFACTURE/PURCHASE/OUTSOURCE/VIRTUAL");
  }

  /** 将月度原始 BOM 中常见的 U9 形态原值标准化为报价形态枚举。 */
  public static QuoteMaterialShape fromU9(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("U9料品形态不能为空");
    }
    String normalized = value.trim();
    return switch (normalized) {
      case "制造", "自制", "自制件" -> MANUFACTURE;
      case "采购" -> PURCHASE;
      case "委外", "委外加工", "委外件" -> OUTSOURCE;
      case "虚拟件" -> VIRTUAL;
      default -> valueOf(normalize(normalized));
    };
  }
}
