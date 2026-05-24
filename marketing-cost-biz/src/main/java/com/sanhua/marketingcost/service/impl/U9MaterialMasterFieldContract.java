package com.sanhua.marketingcost.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class U9MaterialMasterFieldContract {
  public static final String MAPPING_VERSION = "U9_ITEM_MASTER_20260519";
  public static final String DATASET_CODE = "MATERIAL_MASTER";
  public static final String SHEET_NAME = "物料主档";
  public static final int HEAD_ROW_NUMBER = 2;

  private static final List<FieldMapping> FIELD_MAPPINGS = buildFieldMappings();
  private static final Map<String, String> HEADER_TO_FIELD = buildHeaderToField();
  private static final Map<String, String> HEADER_ALIASES = Map.of(
      "料品采购相关信息.收货原则", "收货原则",
      "料品MRP相关信息.采购预处理提前期(天)", "采购预处理提前期(天)");

  private U9MaterialMasterFieldContract() {}

  public record FieldMapping(String field, String header, int excelColumn) {}

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
    String header = value.toString().trim();
    return HEADER_ALIASES.getOrDefault(header, header);
  }

  private static List<FieldMapping> buildFieldMappings() {
    List<FieldMapping> m = new ArrayList<>();
    m.add(new FieldMapping("finance_category", "财务分类", 1));
    m.add(new FieldMapping("purchase_category", "采购分类", 2));
    m.add(new FieldMapping("production_category", "生产分类", 3));
    m.add(new FieldMapping("sales_category", "销售分类", 4));
    m.add(new FieldMapping("bare_code", "裸品编码", 5));
    m.add(new FieldMapping("material_code", "物料代码*", 6));
    m.add(new FieldMapping("material_name", "物料名称*", 7));
    m.add(new FieldMapping("material_spec", "物料规格", 8));
    m.add(new FieldMapping("material_model", "物料型号", 9));
    m.add(new FieldMapping("drawing_no", "物料图号", 10));
    m.add(new FieldMapping("main_category_code", "主分类代码", 11));
    m.add(new FieldMapping("main_category_name", "主分类名称", 12));
    m.add(new FieldMapping("unit", "计量单位", 13));
    m.add(new FieldMapping("shape_attr", "U9物料形态属性", 14));
    m.add(new FieldMapping("min_eco_batch", "最小经济批量", 15));
    m.add(new FieldMapping("department_code", "部门代码", 16));
    m.add(new FieldMapping("department_name", "部门名称", 17));
    m.add(new FieldMapping("production_division", "生产事业部名称", 18));
    m.add(new FieldMapping("default_supplier", "默认主供应商", 19));
    m.add(new FieldMapping("purchase_lead_time", "采购处理提前期", 20));
    m.add(new FieldMapping("purchase_post_lead_time", "采购后处理提前期", 21));
    m.add(new FieldMapping("legacy_u9_code", "老U9物料代码", 22));
    m.add(new FieldMapping("global_seg_14_customs_unit", "全局段14(海关单位)", 23));
    m.add(new FieldMapping("global_seg_15_package_size", "全局段15(包装尺寸)", 24));
    m.add(new FieldMapping("global_seg_17_replace_strategy", "全局段17(替代策略)", 25));
    m.add(new FieldMapping("global_seg_18_purchase_type", "全局段18(采购类型)", 26));
    m.add(new FieldMapping("global_seg_19_in_out_ratio", "全局段19(内外采比例)", 27));
    m.add(new FieldMapping("global_seg_2_logistics_type", "全局段2(物流采购类型)", 28));
    m.add(new FieldMapping("global_seg_20_internal_threshold", "全局段20(内部采购阈值)", 29));
    m.add(new FieldMapping("private_seg_21_customs_name", "私有段21(海关名称)", 30));
    m.add(new FieldMapping("private_seg_22_customs_code", "私有段22(海关编码)", 31));
    m.add(new FieldMapping("private_seg_23_customs_desc", "私有段23(海关描述)", 32));
    m.add(new FieldMapping("private_seg_24_product_property", "私有段24(产品属性)", 33));
    m.add(new FieldMapping("private_seg_25_daily_capacity", "私有段25(日产能)", 34));
    m.add(new FieldMapping("private_seg_26_lead_time", "私有段26(加工周期)", 35));
    m.add(new FieldMapping("global_seg_3_status", "全局段3(验证/正式)", 36));
    m.add(new FieldMapping("global_seg_4_material", "全局段4(材质)", 37));
    m.add(new FieldMapping("global_seg_5_net_weight", "全局段5(净重)", 38));
    m.add(new FieldMapping("global_seg_6_valid_period", "全局段6(有效期)", 39));
    m.add(new FieldMapping("global_seg_7_product_property_class", "全局段7(产品属性分类)", 40));
    m.add(new FieldMapping("global_seg_8_loss_rate", "全局段8(净损失率)", 41));
    m.add(new FieldMapping("global_seg_9_gross_weight", "全局段9(单品毛重)", 42));
    m.add(new FieldMapping("purchase_multiple", "采购倍量", 43));
    m.add(new FieldMapping("min_order_qty", "最小叫货量", 44));
    m.add(new FieldMapping("default_buyer", "默认采购员", 45));
    m.add(new FieldMapping("plan_method", "计划方法", 46));
    m.add(new FieldMapping("forecast_control_type", "预测控制类型", 47));
    m.add(new FieldMapping("demand_trace", "是否需求追溯", 48));
    m.add(new FieldMapping("demand_category_control", "按照需求分类控制", 49));
    m.add(new FieldMapping("demand_category_compare_rule", "需求分类对比规则", 50));
    m.add(new FieldMapping("default_planner", "默认计划员", 51));
    m.add(new FieldMapping("engineering_change_control", "工程变更控制", 52));
    m.add(new FieldMapping("allow_over_pick", "允许超额领料", 53));
    m.add(new FieldMapping("prepare_over_type", "备料超额类型", 54));
    m.add(new FieldMapping("over_complete_type", "可超量完工类型", 55));
    m.add(new FieldMapping("over_complete_ratio", "完工超额比例", 56));
    m.add(new FieldMapping("inventory_planning_method", "库存规划方法", 57));
    m.add(new FieldMapping("code_inventory_account", "番号存货核算", 58));
    m.add(new FieldMapping("cost_element", "成本要素", 59));
    m.add(new FieldMapping("producible", "可生产", 60));
    m.add(new FieldMapping("purchase_receive_principle", "收货原则", 61));
    m.add(new FieldMapping("mrp_purchase_pre_lead_time", "采购预处理提前期(天)", 62));
    m.add(new FieldMapping("global_seg_3_theoretical_net_weight", "全局段3(理论净重)", 63));
    return List.copyOf(m);
  }

  private static Map<String, String> buildHeaderToField() {
    Map<String, String> m = new LinkedHashMap<>();
    for (FieldMapping mapping : FIELD_MAPPINGS) {
      m.put(mapping.header(), mapping.field());
    }
    return Map.copyOf(m);
  }
}
