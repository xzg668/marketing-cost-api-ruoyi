#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
import_item_master.py
把 ItemMaster20260427.xlsx (163K 行) 导入到 lp_material_master_raw 表。

特性：
  - Excel 列按索引映射到 DB 列（与 SQL 表注释 C0~C61 完全对应）
  - 批量 executemany（默认每批 1000 行），16 万行约 1-3 分钟
  - import_batch_id 同一文件可多次导入（用文件名+时间戳区分）
  - 跳过空白行、material_code 为空的行
  - 失败行写到 failed_rows.csv 不阻塞整体导入
  - 进度条 + 最终行数校验

依赖：
  pip install openpyxl pymysql tqdm

用法：
  python3 import_item_master.py \
      --excel /path/to/ItemMaster20260427.xlsx \
      --host 127.0.0.1 --port 3306 \
      --user root --db marketing_cost
"""
import argparse
import csv
import getpass
import os
import sys
from datetime import datetime
from pathlib import Path

try:
    from openpyxl import load_workbook
except ImportError:
    sys.exit("缺少依赖：pip install openpyxl")
try:
    import pymysql
except ImportError:
    sys.exit("缺少依赖：pip install pymysql")
try:
    from tqdm import tqdm
except ImportError:
    # 没装 tqdm 也能跑，只是没进度条
    def tqdm(it, **kw): return it


# ============ Excel 列索引 → DB 列名 映射（共 62 列）============
COL_MAP = [
    "finance_category",                  # C0  财务分类
    "purchase_category",                 # C1  采购分类
    "production_category",               # C2  生产分类
    "sales_category",                    # C3  销售分类
    "bare_code",                         # C4  裸品编码
    "material_code",                     # C5  物料代码*  (NOT NULL)
    "material_name",                     # C6  物料名称*
    "material_spec",                     # C7
    "material_model",                    # C8
    "drawing_no",                        # C9
    "main_category_code",                # C10
    "main_category_name",                # C11
    "unit",                              # C12
    "shape_attr",                        # C13 U9物料形态属性
    "min_eco_batch",                     # C14
    "department_code",                   # C15
    "department_name",                   # C16
    "production_division",               # C17
    "purchase_lead_time",                # C18
    "purchase_post_lead_time",           # C19
    "legacy_u9_code",                    # C20
    "global_seg_14_customs_unit",        # C21
    "global_seg_15_package_size",        # C22
    "global_seg_17_replace_strategy",    # C23
    "global_seg_18_purchase_type",       # C24
    "global_seg_19_in_out_ratio",        # C25
    "global_seg_2_logistics_type",       # C26
    "global_seg_20_internal_threshold",  # C27
    "private_seg_21_customs_name",       # C28
    "private_seg_22_customs_code",       # C29
    "private_seg_23_customs_desc",       # C30
    "private_seg_24_product_property",   # C31
    "private_seg_25_daily_capacity",     # C32
    "private_seg_26_lead_time",          # C33
    "global_seg_3_status",               # C34
    "global_seg_4_material",             # C35
    "global_seg_5_net_weight",           # C36
    "global_seg_6_valid_period",         # C37
    "global_seg_7_product_property_class",  # C38
    "global_seg_8_loss_rate",            # C39
    "global_seg_9_gross_weight",         # C40
    "purchase_multiple",                 # C41
    "min_order_qty",                     # C42
    "default_supplier",                  # C43
    "default_buyer",                     # C44
    "plan_method",                       # C45
    "forecast_control_type",             # C46
    "demand_trace",                      # C47
    "demand_category_control",           # C48
    "demand_category_compare_rule",      # C49
    "default_planner",                   # C50
    "engineering_change_control",        # C51
    "allow_over_pick",                   # C52
    "prepare_over_type",                 # C53
    "over_complete_type",                # C54
    "over_complete_ratio",               # C55
    "inventory_planning_method",         # C56
    "code_inventory_account",            # C57
    "cost_element",                      # C58
    "producible",                        # C59
    "purchase_receive_principle",        # C60
    "mrp_purchase_pre_lead_time",        # C61
]
assert len(COL_MAP) == 62

MATERIAL_CODE_IDX = COL_MAP.index("material_code")  # = 5

# ============ 列裁剪：超过字段长度（255）就截断 ============
MAX_LEN = 255
MAT_CODE_MAX = 64  # material_code varchar(64)


def normalize_cell(v):
    """统一把 None / 空白 / 浮点整数化等转换好"""
    if v is None:
        return None
    if isinstance(v, float):
        # Excel 中"0"经常被存成 0.0，整数化
        if v.is_integer():
            return str(int(v))
        return str(v)
    s = str(v).strip()
    return s if s != "" else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--excel", default="/Users/xiexicheng/Desktop/ItemMaster20260427.xlsx")
    ap.add_argument("--sheet", default="物料主档")
    ap.add_argument("--host",  default="127.0.0.1")
    ap.add_argument("--port",  type=int, default=3306)
    ap.add_argument("--user",  default="root")
    ap.add_argument("--password", default=None, help="留空会交互询问")
    ap.add_argument("--db",    default="marketing_cost")
    ap.add_argument("--batch-size", type=int, default=1000, help="每批 INSERT 行数")
    ap.add_argument("--batch-id", default=None,
                    help="导入批次号（默认：文件名_时间戳）")
    ap.add_argument("--truncate", action="store_true",
                    help="导入前清空整张目标表（慎用）")
    ap.add_argument("--clear-batch", action="store_true",
                    help="导入前清掉同 batch-id 的旧记录")
    args = ap.parse_args()

    excel_path = Path(args.excel)
    if not excel_path.exists():
        sys.exit(f"❌ Excel 不存在：{excel_path}")

    pwd = args.password
    if pwd is None:
        pwd = getpass.getpass(f"请输入 MySQL 密码（{args.user}@{args.host}）: ")

    batch_id = args.batch_id or f"{excel_path.stem}_{datetime.now():%Y%m%d_%H%M%S}"

    print(f"\n=========== 导入任务 ===========")
    print(f"  Excel       : {excel_path} ({excel_path.stat().st_size/1024/1024:.1f} MB)")
    print(f"  Sheet       : {args.sheet}")
    print(f"  目标库       : {args.db}@{args.host}:{args.port}")
    print(f"  目标表       : lp_material_master_raw")
    print(f"  batch_id    : {batch_id}")
    print(f"  batch-size  : {args.batch_size}")
    if args.truncate:    print(f"  ⚠️  TRUNCATE  : 整表清空")
    if args.clear_batch: print(f"  ⚠️  CLEAR     : 清掉同 batch-id 旧记录")
    print(f"================================\n")

    # 连接
    conn = pymysql.connect(
        host=args.host, port=args.port,
        user=args.user, password=pwd, database=args.db,
        charset="utf8mb4",
        local_infile=False,
        autocommit=False,
        connect_timeout=30,
    )
    cur = conn.cursor()
    print("✅ MySQL 连接 OK")

    # 临时关掉外键/唯一性检查 + 加大 max_allowed_packet
    cur.execute("SET autocommit = 0")
    cur.execute("SET unique_checks = 0")
    cur.execute("SET foreign_key_checks = 0")

    if args.truncate:
        cur.execute("TRUNCATE TABLE lp_material_master_raw")
        print("已清空目标表")
    elif args.clear_batch:
        cur.execute("DELETE FROM lp_material_master_raw WHERE import_batch_id=%s", (batch_id,))
        print(f"已清掉 batch_id={batch_id} 的旧记录：{cur.rowcount} 行")
    conn.commit()

    # 打开 Excel（read_only 模式，省内存）
    print("打开 Excel ...")
    wb = load_workbook(excel_path, read_only=True, data_only=True)
    if args.sheet not in wb.sheetnames:
        sys.exit(f"❌ Sheet '{args.sheet}' 不存在，可用：{wb.sheetnames}")
    ws = wb[args.sheet]

    # 拼 SQL
    cols_sql = ",".join(f"`{c}`" for c in COL_MAP) + ",`import_batch_id`"
    placeholders = ",".join(["%s"] * (len(COL_MAP) + 1))
    insert_sql = (
        f"INSERT INTO lp_material_master_raw ({cols_sql}) VALUES ({placeholders}) "
        f"ON DUPLICATE KEY UPDATE imported_at = CURRENT_TIMESTAMP"
    )

    # 准备失败行日志
    failed_csv = excel_path.with_suffix(".failed.csv")
    failed_f = open(failed_csv, "w", newline="", encoding="utf-8")
    failed_writer = csv.writer(failed_f)
    failed_writer.writerow(["row_number", "material_code", "error"] + COL_MAP)

    # 流式读取数据：跳过前 2 行（标题 + 表头）
    inserted = 0
    skipped = 0
    failed = 0
    batch = []
    row_iter = ws.iter_rows(min_row=3, values_only=True)

    pbar = tqdm(row_iter, total=max(0, ws.max_row - 2), unit="行", desc="导入中")
    for excel_row_idx, row in enumerate(pbar, start=3):
        if row is None:
            skipped += 1
            continue
        # 取前 62 列；如果表更短，补 None
        cells = list(row[:62])
        while len(cells) < 62:
            cells.append(None)

        cells = [normalize_cell(c) for c in cells]
        mat_code = cells[MATERIAL_CODE_IDX]
        if not mat_code:
            skipped += 1
            continue

        # 字段长度兜底
        if len(mat_code) > MAT_CODE_MAX:
            cells[MATERIAL_CODE_IDX] = mat_code[:MAT_CODE_MAX]
        for i, v in enumerate(cells):
            if v is not None and i != MATERIAL_CODE_IDX and len(v) > MAX_LEN:
                cells[i] = v[:MAX_LEN]

        cells.append(batch_id)
        batch.append((excel_row_idx, tuple(cells)))

        if len(batch) >= args.batch_size:
            inserted_now, failed_now = flush_batch(cur, insert_sql, batch, failed_writer, COL_MAP)
            inserted += inserted_now
            failed += failed_now
            conn.commit()
            batch.clear()
            pbar.set_postfix(inserted=inserted, failed=failed, skipped=skipped)

    if batch:
        inserted_now, failed_now = flush_batch(cur, insert_sql, batch, failed_writer, COL_MAP)
        inserted += inserted_now
        failed += failed_now
        conn.commit()
        batch.clear()

    pbar.close()

    # 还原检查
    cur.execute("SET unique_checks = 1")
    cur.execute("SET foreign_key_checks = 1")
    conn.commit()

    failed_f.close()

    # 校验
    cur.execute("SELECT COUNT(*) FROM lp_material_master_raw WHERE import_batch_id=%s", (batch_id,))
    db_count = cur.fetchone()[0]

    print("\n========== 导入完成 ==========")
    print(f"  尝试插入 : {inserted + failed} 行")
    print(f"  成功插入 : {inserted} 行")
    print(f"  失败行   : {failed} 行（详见 {failed_csv}）")
    print(f"  跳过空行 : {skipped} 行")
    print(f"  当前批次 DB 行数 : {db_count}")
    print(f"  batch_id : {batch_id}")
    print(f"==============================")

    cur.close()
    conn.close()


def flush_batch(cur, sql, batch, failed_writer, col_map):
    """先尝试整批插入；失败再逐行重试，把失败的写入 failed.csv"""
    rows = [b[1] for b in batch]
    try:
        cur.executemany(sql, rows)
        return len(rows), 0
    except Exception:
        # 整批失败 → 逐行重试
        ok = 0
        bad = 0
        for excel_row_idx, row in batch:
            try:
                cur.execute(sql, row)
                ok += 1
            except Exception as e:
                bad += 1
                failed_writer.writerow([excel_row_idx, row[5], str(e)[:200], *row[:-1]])
        return ok, bad


if __name__ == "__main__":
    main()
