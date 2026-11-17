package com.sanhua.marketingcost.service.technicaldata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The 15-column input contract. Node identity is the textual hierarchy, never a material code. */
public final class TechnicalBomRows {
  private TechnicalBomRows() {}
  public static final int MAX_ROWS = 5000;
  public static final int MAX_DEPTH = 64;
  public static final List<String> HEADERS = List.of("产品料号", "产品名称", "产品型号", "产品规格",
      "层级编号", "子件料号", "子件名称", "子件型号", "子件规格", "子件用量", "子件单位",
      "子件属性", "子件材质", "子件单件净重", "子件净重单位");
  public record Product(String code, String name, String model, String specification) {}
  public record Row(int lineNo, String hierarchy, String materialCode, String name, String model,
      String specification, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal quantity,
      String unit, String attribute, String material,
      @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal netWeight, String netWeightUnit) {}
  public record Issue(Integer lineNo, String field, String message) {}
  public record Input(Product product, List<Row> rows, List<Issue> issues) {
    public Input { rows = List.copyOf(rows); issues = List.copyOf(issues); }
  }

  public static List<Issue> validate(Product product, List<Row> rows) {
    return validate(product, rows, false);
  }
  static List<Issue> validate(Product product, List<Row> rows, boolean reference) {
    var issues = new ArrayList<Issue>();
    if (product == null || blank(product.code()) && blank(product.model()))
      issues.add(new Issue(null, "产品型号", "请填写本次产品型号或料号；没有产品料号可留空"));
    if (rows == null || rows.isEmpty()) {
      issues.add(new Issue(null, "BOM填写", "填写表没有BOM子件明细"));
      return List.copyOf(issues);
    }
    if (rows.size() > MAX_ROWS) {
      issues.add(new Issue(null, "BOM填写", "BOM子件不能超过 " + MAX_ROWS + " 行"));
      return List.copyOf(issues);
    }
    Map<String, Row> byHierarchy = new LinkedHashMap<>();
    for (var row : rows) {
      String hierarchy = row.hierarchy();
      if (hierarchy == null || hierarchy.length() > 512 || !hierarchy.matches("[1-9][0-9]*(\\.[1-9][0-9]*)*")
          || hierarchy.split("\\.").length > MAX_DEPTH) {
        issues.add(new Issue(row.lineNo(), "层级编号", "层级须为文本正整数编号，如1、1.1、1.10，最多 " + MAX_DEPTH + " 层"));
      } else if (byHierarchy.putIfAbsent(hierarchy, row) != null) {
        issues.add(new Issue(row.lineNo(), "层级编号", "层级编号重复：" + hierarchy));
      }
      if (blank(row.materialCode()) && blank(row.model()) && blank(row.specification()))
        issues.add(new Issue(row.lineNo(), "子件料号", "请提供子件料号，或提供型号／规格供现有物料规则核实"));
      if (row.quantity() == null || row.quantity().signum() < 0 || !reference && row.quantity().signum() == 0)
        issues.add(new Issue(row.lineNo(), "子件用量", reference ? "参考BOM子件用量不能为负数" : "子件用量须大于零，按直接母件每1件填写"));
      if (row.quantity() != null && (row.quantity().precision() > 24 || row.quantity().scale() > 12 || row.quantity().scale() < -12))
        issues.add(new Issue(row.lineNo(), "子件用量", "子件用量最多24位有效数字、12位小数"));
      if (blank(row.unit())) issues.add(new Issue(row.lineNo(), "子件单位", "子件用量单位不能为空"));
      if (!Set.of("采购件", "制造件", "委外加工件").contains(row.attribute() == null ? "" : row.attribute())
          && !(reference && "虚拟件".equals(row.attribute())))
        issues.add(new Issue(row.lineNo(), "子件属性", "子件属性只能填写采购件、制造件或委外加工件"));
      if (row.netWeight() != null && row.netWeight().signum() < 0)
        issues.add(new Issue(row.lineNo(), "子件单件净重", "单件净重不能为负数"));
      if (row.netWeight() != null && !Set.of("g", "kg", "克", "千克", "公斤").contains(row.netWeightUnit() == null ? "" : row.netWeightUnit().toLowerCase(java.util.Locale.ROOT)))
        issues.add(new Issue(row.lineNo(), "子件净重单位", "已填写净重，请注明g或kg等明确重量单位"));
      if (row.netWeight() == null && !blank(row.netWeightUnit()))
        issues.add(new Issue(row.lineNo(), "子件单件净重", "已填写净重单位，请同时填写单件净重；缺失不等于零"));
    }
    for (var row : byHierarchy.values()) {
      String parent = parent(row.hierarchy());
      if (parent != null && !byHierarchy.containsKey(parent))
        issues.add(new Issue(row.lineNo(), "层级编号", "缺少直接父节点：" + parent));
    }
    return List.copyOf(issues);
  }

  /** Input order may differ from tree order; multiply each direct quantity exactly once. */
  public static Map<String, BigDecimal> cumulativeQuantities(List<Row> rows) {
    var byHierarchy = new LinkedHashMap<String, Row>();
    rows.forEach(row -> {
      if (row.hierarchy() == null || byHierarchy.putIfAbsent(row.hierarchy(), row) != null)
        throw new IllegalArgumentException("BOM层级缺失或重复");
    });
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    for (var row : rows) {
      BigDecimal value = BigDecimal.ONE;
      String key = row.hierarchy();
      int depth = 0;
      while (key != null) {
        var ancestor = byHierarchy.get(key);
        if (ancestor == null || ancestor.quantity() == null || ancestor.quantity().signum() < 0 || ++depth > MAX_DEPTH)
          throw new IllegalArgumentException("BOM层级或用量无效：" + key);
        value = value.multiply(ancestor.quantity());
        key = parent(key);
      }
      result.put(row.hierarchy(), value);
    }
    return Map.copyOf(result);
  }
  public static String parent(String hierarchy) {
    int index = hierarchy.lastIndexOf('.');
    return index < 0 ? null : hierarchy.substring(0, index);
  }
  public static boolean blank(String value) { return value == null || value.isBlank(); }
}
