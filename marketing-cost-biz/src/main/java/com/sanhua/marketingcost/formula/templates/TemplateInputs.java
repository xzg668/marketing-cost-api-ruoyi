package com.sanhua.marketingcost.formula.templates;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 模板入参解析工具 —— 统一 BigDecimal 转换 / 必填校验 / 默认值兜底。
 *
 * <p>5 个模板共用，避免重复 boilerplate 与精度坑（绝不允许 double）。
 */
public final class TemplateInputs {

  private TemplateInputs() {}

  /** 必填字段；缺失或类型错误抛 IllegalArgumentException */
  public static BigDecimal requiredDecimal(Map<String, Object> inputs, String key) {
    Object raw = inputs.get(key);
    if (raw == null) {
      throw new IllegalArgumentException("公式缺必填字段: " + key);
    }
    return toDecimal(raw, key);
  }

  /** 选填字段；缺失返回默认值 */
  public static BigDecimal optionalDecimal(
      Map<String, Object> inputs, String key, BigDecimal fallback) {
    Object raw = inputs.get(key);
    return raw == null ? fallback : toDecimal(raw, key);
  }

  /** 解 array 字段（焊料配比用） */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> requiredArray(Map<String, Object> inputs, String key) {
    Object raw = inputs.get(key);
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("公式缺必填数组字段或为空: " + key);
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("数组元素必须是 Map: " + key);
      }
    }
    return (List<Map<String, Object>>) raw;
  }

  private static BigDecimal toDecimal(Object raw, String key) {
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof Number n) {
      // Number → string → BigDecimal，避免 double 直接转引入精度损失
      return new BigDecimal(n.toString());
    }
    if (raw instanceof String s) {
      try {
        return new BigDecimal(s);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("字段非数字: " + key + "=" + s, e);
      }
    }
    throw new IllegalArgumentException("字段类型不支持: " + key + "=" + raw.getClass());
  }
}
