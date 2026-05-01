#!/usr/bin/env python3
"""
物料主档 (U9 ItemMaster Excel) 批量导入到 lp_material_master_raw

用法：
    python3 scripts/import_item_master.py /path/to/ItemMaster20260427.xlsx

特点：
- 流式读 Excel（16 万行不爆内存）
- 1000 行一批 INSERT IGNORE + commit（断电不丢前面）
- 每批失败只丢该批，不影响其他
- 报告：成功/跳过/失败 计数
"""
import sys, os, time, datetime, warnings
warnings.filterwarnings("ignore")

import openpyxl
import pymysql

# ───────── 配置 ─────────
DB_HOST = "localhost"
DB_PORT = 3306
DB_USER = "root"
DB_PASS = "root123"
DB_NAME = "marketing_cost"
BATCH_SIZE = 1000

# Excel 列号 → 表字段名（按 V53 定义对齐 62 列）
COL_TO_FIELD = [
    "finance_category",                   # C0
    "purchase_category",                  # C1
    "production_category",                # C2
    "sales_category",                     # C3
    "bare_code",                          # C4
    "material_code",                      # C5 ← 必填
    "material_name",                      # C6
    "material_spec",                      # C7
    "material_model",                     # C8
    "drawing_no",                         # C9
    "main_category_code",                 # C10
    "main_category_name",                 # C11
    "unit",                               # C12
    "shape_attr",                         # C13
    "min_eco_batch",                      # C14
    "department_code",                    # C15
    "department_name",                    # C16
    "production_division",                # C17
    "purchase_lead_time",                 # C18
    "purchase_post_lead_time",            # C19
    "legacy_u9_code",                     # C20
    "global_seg_14_customs_unit",         # C21
    "global_seg_15_package_size",         # C22
    "global_seg_17_replace_strategy",     # C23
    "global_seg_18_purchase_type",        # C24
    "global_seg_19_in_out_ratio",         # C25
    "global_seg_2_logistics_type",        # C26
    "global_seg_20_internal_threshold",   # C27
    "private_seg_21_customs_name",        # C28
    "private_seg_22_customs_code",        # C29
    "private_seg_23_customs_desc",        # C30
    "private_seg_24_product_property",    # C31
    "private_seg_25_daily_capacity",      # C32
    "private_seg_26_lead_time",           # C33
    "global_seg_3_status",                # C34
    "global_seg_4_material",              # C35
    "global_seg_5_net_weight",            # C36
    "global_seg_6_valid_period",          # C37
    "global_seg_7_product_property_class",# C38
    "global_seg_8_loss_rate",             # C39
    "global_seg_9_gross_weight",          # C40
    "purchase_multiple",                  # C41
    "min_order_qty",                      # C42
    "default_supplier",                   # C43
    "default_buyer",                      # C44
    "plan_method",                        # C45
    "forecast_control_type",              # C46
    "demand_trace",                       # C47
    "demand_category_control",            # C48
    "demand_category_compare_rule",       # C49
    "default_planner",                    # C50
    "engineering_change_control",         # C51
    "allow_over_pick",                    # C52
    "prepare_over_type",                  # C53
    "over_complete_type",                 # C54
    "over_complete_ratio",                # C55
    "inventory_planning_method",          # C56
    "code_inventory_account",             # C57
    "cost_element",                       # C58
    "producible",                         # C59
    "purchase_receive_principle",         # C60
    "mrp_purchase_pre_lead_time",         # C61
]
MATERIAL_CODE_IDX = 5  # C5 是 material_code，必填


def to_str_or_none(v):
    """单元格值 → 字符串或 None；空字符串视作 None"""
    if v is None:
        return None
    s = str(v).strip()
    return s if s else None


def main(xlsx_path):
    if not os.path.exists(xlsx_path):
        print(f"❌ 文件不存在: {xlsx_path}")
        sys.exit(1)

    batch_id = f"u9-{datetime.datetime.now():%Y%m%d-%H%M%S}"
    print(f"导入批次: {batch_id}")

    print("打开 Excel ...")
    t0 = time.time()
    wb = openpyxl.load_workbook(xlsx_path, read_only=True, data_only=True)
    ws = wb["物料主档"]
    print(f"  sheet: {ws.title}, 总行数: {ws.max_row}")

    # 第 1 行是 "物料主档" 标题占位，第 2 行是表头，从第 3 行开始是数据
    rows_iter = ws.iter_rows(min_row=3, values_only=True)

    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS,
        database=DB_NAME, charset="utf8mb4", autocommit=False,
    )
    cursor = conn.cursor()

    # 构造 INSERT IGNORE SQL
    cols = COL_TO_FIELD + ["import_batch_id"]
    placeholders = ",".join(["%s"] * len(cols))
    cols_sql = ",".join(f"`{c}`" for c in cols)
    sql = f"INSERT IGNORE INTO lp_material_master_raw ({cols_sql}) VALUES ({placeholders})"

    total_read = 0
    total_inserted = 0
    total_skipped = 0  # material_code 空 / UK 冲突 / 异常
    total_failed = 0
    batch = []

    def flush(batch_data):
        nonlocal total_inserted, total_failed
        if not batch_data:
            return
        try:
            n = cursor.executemany(sql, batch_data)
            conn.commit()
            total_inserted += n
        except Exception as e:
            conn.rollback()
            total_failed += len(batch_data)
            print(f"  ⚠️ 批次提交失败: {e}")

    print("开始流式读取 + 批量入库 ...")
    for row in rows_iter:
        total_read += 1
        # 取前 62 列
        cells = list(row[:len(COL_TO_FIELD)])
        material_code = to_str_or_none(cells[MATERIAL_CODE_IDX])
        if not material_code:
            total_skipped += 1
            continue
        # 全列转 str/None
        values = tuple(to_str_or_none(c) for c in cells) + (batch_id,)
        batch.append(values)
        if len(batch) >= BATCH_SIZE:
            flush(batch)
            batch = []
            if total_read % 10000 == 0:
                print(f"  进度: 已读 {total_read} / 已入库 {total_inserted} / 跳过 {total_skipped}")

    flush(batch)

    cursor.close()
    conn.close()
    elapsed = time.time() - t0
    print(f"\n✅ 导入完成 (耗时 {elapsed:.1f}s)")
    print(f"  总读取行数:   {total_read}")
    print(f"  成功入库:     {total_inserted}")
    print(f"  跳过(空料号): {total_skipped}")
    print(f"  失败行数:     {total_failed}")
    print(f"  批次 ID:      {batch_id}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("用法: python3 import_item_master.py <ItemMaster.xlsx>")
        sys.exit(1)
    main(sys.argv[1])
