package com.sanhua.marketingcost.service.bomalternative;

import java.text.Normalizer;
import java.util.Locale;

/** U9 BOM 子项类型的内部统一语义。 */
public enum BomChildType {
  STANDARD,
  ALTERNATIVE,
  NORMAL,
  UNKNOWN;

  /**
   * 标准化 U9 子项类型。
   *
   * @param sourceValue U9 原始值
   * @param alternativeGroupContext 当前位置是否已经确认包含替代成员
   * @return 内部统一类型；替代组中的空值不会被猜成标准件
   */
  public static BomChildType fromSource(
      String sourceValue, boolean alternativeGroupContext) {
    String normalized = normalize(sourceValue);
    if (normalized.isEmpty()) {
      return alternativeGroupContext ? UNKNOWN : NORMAL;
    }
    return switch (normalized) {
      case "标准", "STANDARD" -> STANDARD;
      case "替代", "ALTERNATIVE" -> ALTERNATIVE;
      case "普通", "NORMAL" -> NORMAL;
      default -> UNKNOWN;
    };
  }

  static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace("\uFEFF", "")
        .strip()
        .replaceAll("\\s+", " ")
        .toUpperCase(Locale.ROOT);
  }
}
