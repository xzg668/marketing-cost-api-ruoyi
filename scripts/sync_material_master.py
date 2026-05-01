#!/usr/bin/env python3
"""
按 OA 单号同步物料主档：从 staging (lp_material_master_raw) 把该 OA BOM 涉及的
料号 UPSERT 到 lp_material_master 主表。

用法：
    python3 scripts/sync_material_master.py <oa_no> [<batch_id>]
        oa_no    : OA 单号，如 OA-GOLDEN-001
        batch_id : 可选；不传则用 staging 表里"最新批次"

特点：
- 只装业务真用到的料号（按 BOM 涉及的去重 material_code）
- ON DUPLICATE KEY UPDATE 已有行字段刷新
- 数值字段做类型转换（VARCHAR → DECIMAL/INT），失败置 NULL 不报错
- BU 自动推断：production_division 含"商用"→COMMERCIAL，"家用"→HOUSEHOLD，其他默认 COMMERCIAL
"""
import sys, re
import pymysql

DB_HOST = "localhost"
DB_PORT = 3306
DB_USER = "root"
DB_PASS = "root123"
DB_NAME = "marketing_cost"


def to_decimal(v):
    """字符串 → DECIMAL or NULL；空/非数字一律 NULL"""
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    # 去掉百分号、逗号
    s = s.replace(",", "").rstrip("%")
    try:
        return float(s)
    except ValueError:
        return None


def to_int(v):
    d = to_decimal(v)
    return int(d) if d is not None else None


def infer_bu(production_division):
    """根据生产事业部名推断 BU"""
    if not production_division:
        return "COMMERCIAL"
    s = str(production_division)
    if "家用" in s:
        return "HOUSEHOLD"
    return "COMMERCIAL"


def main(oa_no, batch_id=None):
    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS,
        database=DB_NAME, charset="utf8mb4", autocommit=False,
    )
    cursor = conn.cursor(pymysql.cursors.DictCursor)

    # 1. 决定 batch_id
    if not batch_id:
        cursor.execute(
            "SELECT MAX(import_batch_id) AS max_id FROM lp_material_master_raw"
        )
        row = cursor.fetchone()
        batch_id = row["max_id"]
        if not batch_id:
            print("❌ staging 表里没有数据")
            sys.exit(1)
    print(f"使用批次: {batch_id}")

    # 2. 取该 OA 涉及的去重料号
    cursor.execute(
        "SELECT DISTINCT material_code FROM lp_bom_costing_row WHERE oa_no=%s",
        (oa_no,),
    )
    codes = [r["material_code"] for r in cursor.fetchall()]
    print(f"OA {oa_no} 涉及去重料号: {len(codes)}")
    if not codes:
        print("⚠️ 该 OA 在 lp_bom_costing_row 无 BOM 行")
        return

    # 3. 从 staging 拉这些料号
    placeholders = ",".join(["%s"] * len(codes))
    cursor.execute(
        f"SELECT * FROM lp_material_master_raw "
        f"WHERE material_code IN ({placeholders}) AND import_batch_id=%s",
        codes + [batch_id],
    )
    raws = cursor.fetchall()
    print(f"staging 命中: {len(raws)} / {len(codes)}")

    # 4. UPSERT 到主表
    sql = """
INSERT INTO lp_material_master (
  material_code, material_name, item_spec, item_model, drawing_no,
  shape_attr, material, net_weight_kg, gross_weight_g,
  business_unit_type, biz_unit, production_dept, production_workshop,
  cost_element, finance_category, purchase_category, production_category, sales_category,
  main_category_code, main_category_name,
  product_property_class, product_property, loss_rate, daily_capacity, lead_time_days, package_size,
  default_supplier, default_buyer, default_planner,
  legacy_u9_code, import_batch_id, source, created_at, updated_at
) VALUES (
  %s, %s, %s, %s, %s,
  %s, %s, %s, %s,
  %s, %s, %s, %s,
  %s, %s, %s, %s, %s,
  %s, %s,
  %s, %s, %s, %s, %s, %s,
  %s, %s, %s,
  %s, %s, %s, NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
  material_name=VALUES(material_name),
  item_spec=VALUES(item_spec),
  item_model=VALUES(item_model),
  drawing_no=VALUES(drawing_no),
  shape_attr=VALUES(shape_attr),
  material=VALUES(material),
  net_weight_kg=VALUES(net_weight_kg),
  gross_weight_g=VALUES(gross_weight_g),
  business_unit_type=VALUES(business_unit_type),
  biz_unit=VALUES(biz_unit),
  production_dept=VALUES(production_dept),
  production_workshop=VALUES(production_workshop),
  cost_element=VALUES(cost_element),
  finance_category=VALUES(finance_category),
  purchase_category=VALUES(purchase_category),
  production_category=VALUES(production_category),
  sales_category=VALUES(sales_category),
  main_category_code=VALUES(main_category_code),
  main_category_name=VALUES(main_category_name),
  product_property_class=VALUES(product_property_class),
  product_property=VALUES(product_property),
  loss_rate=VALUES(loss_rate),
  daily_capacity=VALUES(daily_capacity),
  lead_time_days=VALUES(lead_time_days),
  package_size=VALUES(package_size),
  default_supplier=VALUES(default_supplier),
  default_buyer=VALUES(default_buyer),
  default_planner=VALUES(default_planner),
  legacy_u9_code=VALUES(legacy_u9_code),
  import_batch_id=VALUES(import_batch_id),
  source=VALUES(source),
  updated_at=NOW()
"""
    rows = []
    for r in raws:
        # 净重 / 毛重 是 raw 表的 VARCHAR，原值可能是"克"或"千克"，按 U9 规范都按字面值
        net_weight = to_decimal(r["global_seg_5_net_weight"])  # 克
        gross_weight = to_decimal(r["global_seg_9_gross_weight"])  # 克
        net_weight_kg = net_weight / 1000.0 if net_weight is not None else None
        bu = infer_bu(r["production_division"])
        rows.append((
            r["material_code"], r["material_name"], r["material_spec"],
            r["material_model"], r["drawing_no"],
            r["shape_attr"], r["global_seg_4_material"], net_weight_kg, gross_weight,
            bu, r["production_division"], r["department_name"], r["department_name"],
            r["cost_element"], r["finance_category"], r["purchase_category"],
            r["production_category"], r["sales_category"],
            r["main_category_code"], r["main_category_name"],
            r["global_seg_7_product_property_class"],
            to_decimal(r["private_seg_24_product_property"]),
            to_decimal(r["global_seg_8_loss_rate"]),
            to_decimal(r["private_seg_25_daily_capacity"]),
            to_int(r["private_seg_26_lead_time"]),
            r["global_seg_15_package_size"],
            r["default_supplier"], r["default_buyer"], r["default_planner"],
            r["legacy_u9_code"], r["import_batch_id"], "u9_master",
        ))

    cursor.executemany(sql, rows)
    affected = cursor.rowcount
    conn.commit()

    cursor.execute("SELECT COUNT(*) AS cnt FROM lp_material_master WHERE import_batch_id=%s", (batch_id,))
    final_count = cursor.fetchone()["cnt"]

    cursor.close()
    conn.close()

    print(f"\n✅ 同步完成")
    print(f"  受影响行数 (INSERT/UPDATE): {affected}")
    print(f"  主表当前批次行数: {final_count}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python3 sync_material_master.py <oa_no> [<batch_id>]")
        sys.exit(1)
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
