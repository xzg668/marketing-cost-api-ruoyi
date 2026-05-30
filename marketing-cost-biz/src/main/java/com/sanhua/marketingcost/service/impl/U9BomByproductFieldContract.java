package com.sanhua.marketingcost.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class U9BomByproductFieldContract {
  public static final String DATASET_CODE = "U9_BOM_BYPRODUCT_MASTER";
  public static final String SOURCE_TYPE_EXCEL = "EXCEL";
  public static final String SHEET_NAME = "BOM母项";

  private static final List<FieldMapping> FIELD_MAPPINGS = List.of(
      new FieldMapping("parent_material_no", "母件料品_料号", 1),
      new FieldMapping("parent_material_name", "母件料品_品名", 2),
      new FieldMapping("parent_material_spec", "母件料品.规格", 3),
      new FieldMapping("bom_purpose", "BOM生产目的", 4),
      new FieldMapping("version_no", "版本号", 5),
      new FieldMapping("output_type", "等级品产出比例.产出类型", 6),
      new FieldMapping("byproduct_material_no", "等级品产出比例.料品.料号", 7),
      new FieldMapping("byproduct_material_name", "等级品产出比例.料品.料品名称", 8),
      new FieldMapping("operation_no", "等级品产出比例.工序号", 9),
      new FieldMapping("output_qty", "等级品产出比例.产出数量", 10),
      new FieldMapping("unit", "等级品产出比例.单位", 11),
      new FieldMapping("status", "状态", 12),
      new FieldMapping("production_dept_code", "母件料品.生产部门", 13),
      new FieldMapping("production_dept_name", "母件料品.生产部门", 14),
      new FieldMapping("effective_from", "生效日期", 15),
      new FieldMapping("effective_to", "失效日期", 16),
      new FieldMapping("u9_created_by", "创建人", 17),
      new FieldMapping("u9_created_time", "创建时间", 18));

  private static final Map<String, String> HEADER_TO_FIELD = buildHeaderToField();

  private U9BomByproductFieldContract() {}

  public static List<FieldMapping> fieldMappings() {
    return FIELD_MAPPINGS;
  }

  public static Map<String, String> headerToField() {
    return HEADER_TO_FIELD;
  }

  public static String canonicalHeader(Object value) {
    if (value == null) {
      return "";
    }
    return value.toString()
        .trim()
        .replace(" ", "")
        .replace("　", "")
        .toLowerCase(Locale.ROOT);
  }

  private static Map<String, String> buildHeaderToField() {
    Map<String, String> map = new LinkedHashMap<>();
    int productionDeptOccurrence = 0;
    for (FieldMapping mapping : FIELD_MAPPINGS) {
      String canonical = canonicalHeader(mapping.header());
      if ("母件料品.生产部门".equals(mapping.header())) {
        productionDeptOccurrence++;
        map.put(canonical + "#" + productionDeptOccurrence, mapping.field());
      } else {
        map.put(canonical, mapping.field());
      }
    }
    return map;
  }

  public record FieldMapping(String field, String header, int excelColumn) {}
}
