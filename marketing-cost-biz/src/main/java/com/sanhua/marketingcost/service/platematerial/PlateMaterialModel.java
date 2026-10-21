package com.sanhua.marketingcost.service.platematerial;

/** 与 MySQL TRIM(material_model) 一致，仅移除首尾普通空格，不折叠型号差异。 */
public final class PlateMaterialModel {
  private PlateMaterialModel() {}

  public static String require(String value) {
    String model = normalize(value);
    if (model == null || model.codePointCount(0, model.length()) > 255) {
      throw new IllegalArgumentException("产品型号必填，且不能超过 255 字符");
    }
    return model;
  }

  public static String normalize(String value) {
    if (value == null) return null;
    int start = 0, end = value.length();
    while (start < end && value.charAt(start) == ' ') start++;
    while (end > start && value.charAt(end - 1) == ' ') end--;
    return start == end ? null : value.substring(start, end);
  }
}
