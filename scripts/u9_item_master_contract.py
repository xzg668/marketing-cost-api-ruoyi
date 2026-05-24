#!/usr/bin/env python3
"""U9 料品主档字段契约。

本文件只维护表头到 raw 表字段的稳定映射。后续 Excel 导入、接口接入、
字段注释 migration 都应复用这里的映射，避免 U9 导出列顺序变化造成错位。
"""
from dataclasses import dataclass


MAPPING_VERSION = "U9_ITEM_MASTER_20260519"
SHEET_NAME = "物料主档"
HEADER_ROW = 2
DATA_START_ROW = 3
EXPECTED_HEADER_COUNT = 63
EXPECTED_NEW_UNIQUE_CODES = 166_931
EXPECTED_OLD_UNIQUE_CODES = 163_094


@dataclass(frozen=True)
class FieldMapping:
    field: str
    header: str
    excel_column: int


# 旧 U9 文件中少量表头带模块前缀，新文件已简化；解析时统一归并到新表头。
HEADER_ALIASES = {
    "料品采购相关信息.收货原则": "收货原则",
    "料品MRP相关信息.采购预处理提前期(天)": "采购预处理提前期(天)",
}


# Excel 列号为 1-based，和产品/数据库注释口径保持一致。
FIELD_MAPPINGS = (
    FieldMapping("finance_category", "财务分类", 1),
    FieldMapping("purchase_category", "采购分类", 2),
    FieldMapping("production_category", "生产分类", 3),
    FieldMapping("sales_category", "销售分类", 4),
    FieldMapping("bare_code", "裸品编码", 5),
    FieldMapping("material_code", "物料代码*", 6),
    FieldMapping("material_name", "物料名称*", 7),
    FieldMapping("material_spec", "物料规格", 8),
    FieldMapping("material_model", "物料型号", 9),
    FieldMapping("drawing_no", "物料图号", 10),
    FieldMapping("main_category_code", "主分类代码", 11),
    FieldMapping("main_category_name", "主分类名称", 12),
    FieldMapping("unit", "计量单位", 13),
    FieldMapping("shape_attr", "U9物料形态属性", 14),
    FieldMapping("min_eco_batch", "最小经济批量", 15),
    FieldMapping("department_code", "部门代码", 16),
    FieldMapping("department_name", "部门名称", 17),
    FieldMapping("production_division", "生产事业部名称", 18),
    FieldMapping("default_supplier", "默认主供应商", 19),
    FieldMapping("purchase_lead_time", "采购处理提前期", 20),
    FieldMapping("purchase_post_lead_time", "采购后处理提前期", 21),
    FieldMapping("legacy_u9_code", "老U9物料代码", 22),
    FieldMapping("global_seg_14_customs_unit", "全局段14(海关单位)", 23),
    FieldMapping("global_seg_15_package_size", "全局段15(包装尺寸)", 24),
    FieldMapping("global_seg_17_replace_strategy", "全局段17(替代策略)", 25),
    FieldMapping("global_seg_18_purchase_type", "全局段18(采购类型)", 26),
    FieldMapping("global_seg_19_in_out_ratio", "全局段19(内外采比例)", 27),
    FieldMapping("global_seg_2_logistics_type", "全局段2(物流采购类型)", 28),
    FieldMapping("global_seg_20_internal_threshold", "全局段20(内部采购阈值)", 29),
    FieldMapping("private_seg_21_customs_name", "私有段21(海关名称)", 30),
    FieldMapping("private_seg_22_customs_code", "私有段22(海关编码)", 31),
    FieldMapping("private_seg_23_customs_desc", "私有段23(海关描述)", 32),
    FieldMapping("private_seg_24_product_property", "私有段24(产品属性)", 33),
    FieldMapping("private_seg_25_daily_capacity", "私有段25(日产能)", 34),
    FieldMapping("private_seg_26_lead_time", "私有段26(加工周期)", 35),
    FieldMapping("global_seg_3_status", "全局段3(验证/正式)", 36),
    FieldMapping("global_seg_4_material", "全局段4(材质)", 37),
    FieldMapping("global_seg_5_net_weight", "全局段5(净重)", 38),
    FieldMapping("global_seg_6_valid_period", "全局段6(有效期)", 39),
    FieldMapping("global_seg_7_product_property_class", "全局段7(产品属性分类)", 40),
    FieldMapping("global_seg_8_loss_rate", "全局段8(净损失率)", 41),
    FieldMapping("global_seg_9_gross_weight", "全局段9(单品毛重)", 42),
    FieldMapping("purchase_multiple", "采购倍量", 43),
    FieldMapping("min_order_qty", "最小叫货量", 44),
    FieldMapping("default_buyer", "默认采购员", 45),
    FieldMapping("plan_method", "计划方法", 46),
    FieldMapping("forecast_control_type", "预测控制类型", 47),
    FieldMapping("demand_trace", "是否需求追溯", 48),
    FieldMapping("demand_category_control", "按照需求分类控制", 49),
    FieldMapping("demand_category_compare_rule", "需求分类对比规则", 50),
    FieldMapping("default_planner", "默认计划员", 51),
    FieldMapping("engineering_change_control", "工程变更控制", 52),
    FieldMapping("allow_over_pick", "允许超额领料", 53),
    FieldMapping("prepare_over_type", "备料超额类型", 54),
    FieldMapping("over_complete_type", "可超量完工类型", 55),
    FieldMapping("over_complete_ratio", "完工超额比例", 56),
    FieldMapping("inventory_planning_method", "库存规划方法", 57),
    FieldMapping("code_inventory_account", "番号存货核算", 58),
    FieldMapping("cost_element", "成本要素", 59),
    FieldMapping("producible", "可生产", 60),
    FieldMapping("purchase_receive_principle", "收货原则", 61),
    FieldMapping("mrp_purchase_pre_lead_time", "采购预处理提前期(天)", 62),
    FieldMapping("global_seg_3_theoretical_net_weight", "全局段3(理论净重)", 63),
)


HEADER_TO_FIELD = {mapping.header: mapping.field for mapping in FIELD_MAPPINGS}
FIELD_TO_HEADER = {mapping.field: mapping.header for mapping in FIELD_MAPPINGS}
FIELD_TO_EXCEL_COLUMN = {mapping.field: mapping.excel_column for mapping in FIELD_MAPPINGS}


def normalize_header(value):
    if value is None:
        return ""
    return str(value).strip()


def canonical_header(value):
    header = normalize_header(value)
    return HEADER_ALIASES.get(header, header)


def resolve_field_by_header(value):
    return HEADER_TO_FIELD.get(canonical_header(value))


def expected_headers():
    return tuple(mapping.header for mapping in FIELD_MAPPINGS)
