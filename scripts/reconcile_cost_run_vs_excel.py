#!/usr/bin/env python3
"""
T20 对账脚本：Excel 见机表3 vs 系统 cost-run/detail API。

用法：
  python3 scripts/reconcile_cost_run_vs_excel.py [oa_no] [product_code]
  默认：OA-GOLDEN-001 / 1079900000536

依赖：msoffcrypto-tool / pandas / xlrd
  pip install msoffcrypto-tool pandas xlrd

环境变量（可选）：
  EXCEL_PATH          Excel 路径（默认指向项目本地 .xls）
  EXCEL_PASSWORD      Excel 密码（默认 20260329）
  API_BASE            后端 base URL（默认 http://localhost:8081）
  API_USER/API_PASS   登录账号（默认 admin/123456）
  API_BU              业务单元（默认 COMMERCIAL）

输出三类对账：
  部品：[一致] / [Excel 缺]（系统多）/ [系统缺]（Excel 多）/ [差异 ≥0.01]
  费用：14 项一对一比对，差异类别同上
  总成本：Excel TOTAL vs 系统 TOTAL

备注：脚本不调整双方任何数据。差异行如实报告，业务后续逐条分析。
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import msoffcrypto
import pandas as pd

# ============== 配置 ==============
EXCEL_PATH = os.environ.get(
    "EXCEL_PATH",
    "/Users/xiexicheng/documents/sales_cost/产品成本计算表（3.29- 提供）.xls",
)
EXCEL_PASSWORD = os.environ.get("EXCEL_PASSWORD", "20260329")
API_BASE = os.environ.get("API_BASE", "http://localhost:8081")
API_USER = os.environ.get("API_USER", "admin")
API_PASS = os.environ.get("API_PASS", "123456")
API_BU = os.environ.get("API_BU", "COMMERCIAL")

DEFAULT_OA = "OA-GOLDEN-001"
DEFAULT_PRODUCT = "1079900000536"
PRECISION = 0.01  # 部品/费用差异容忍精度（元）

# 见机表3 表内位置（0-based 行号）
EXCEL_PARTS_HEADER_ROW = 6
EXCEL_PARTS_FIRST_ROW = 7
EXCEL_PARTS_LAST_ROW = 35   # r36+ 是 0 占位
EXCEL_COL = {
    "name": 0,
    "drawing_no": 1,
    "unit_price": 2,
    "qty": 3,
    "amount": 4,
    "material": 5,
    "shape": 6,
    "price_source": 7,
}

# 见机表3 费用项行号 → 内部 cost_code（系统侧），便于一对一比对
# 注：Excel 命名跟系统 cost_code 不完全一一对，下面是经过对照的映射
EXCEL_COSTS = [
    # (excel_row, excel_label, system_cost_code, value_col)
    (42, "酸洗",        None,                 4),  # 系统无对应，仅展示
    (43, "辅料",        None,                 4),  # 系统按 AUX_xxx 多行；本脚本聚合算一项
    (44, "焊料",        None,                 4),  # 系统无对应，仅展示
    (45, "包装",        "OTHER_EXP_PACKAGE",  4),
    (46, "零星工装费用","TOOLING_REPAIR",     4),
    (47, "大修费用",    "OVERHAUL",           4),
    (48, "水电",        "WATER_POWER",        4),
    (49, "材料费",      "MATERIAL",           4),
    (50, "直接人工工资","DIRECT_LABOR",       4),
    (51, "净损失率",    "LOSS",               4),
    (52, "制造费用",    "MANUFACTURE",        4),
    (53, "制造成本",    "MANUFACTURE_COST",   4),
    (54, "调整后制造成本","ADJUSTED_MANUFACTURE_COST", 4),
    (55, "管理费用",    "MGMT_EXP",           4),
    (56, "营业费用",    "SALES_EXP",          4),
    (57, "财务费用",    "FIN_EXP",            4),
    (58, "运费",        "OTHER_EXP_FREIGHT",  4),
    (61, "不含税总成本","TOTAL",              4),
]


# ============== Excel 解析 ==============

def load_excel(path: str, password: str) -> pd.DataFrame:
    """解密 + 读 sheet 见机表3 → DataFrame（无 header）"""
    with open(path, "rb") as f:
        of = msoffcrypto.OfficeFile(f)
        of.load_key(password=password)
        buf = io.BytesIO()
        of.decrypt(buf)
    buf.seek(0)
    return pd.read_excel(buf, sheet_name="见机表3", header=None, engine="xlrd")


def parse_excel_parts(df: pd.DataFrame):
    """抽 r7-r35 部品行，跳过空行 / 占位 0 行。返回 list of dict."""
    parts = []
    for r in range(EXCEL_PARTS_FIRST_ROW, EXCEL_PARTS_LAST_ROW + 1):
        if r >= len(df):
            break
        row = df.iloc[r]
        name = str(row[EXCEL_COL["name"]]) if pd.notna(row[EXCEL_COL["name"]]) else ""
        # 占位行 (col0=='0' 或纯空) 跳过
        if name in ("", "0", "nan"):
            continue
        try:
            unit_price = float(row[EXCEL_COL["unit_price"]]) if pd.notna(row[EXCEL_COL["unit_price"]]) else None
        except (TypeError, ValueError):
            unit_price = None
        try:
            qty = float(row[EXCEL_COL["qty"]]) if pd.notna(row[EXCEL_COL["qty"]]) else None
        except (TypeError, ValueError):
            qty = None
        try:
            amount = float(row[EXCEL_COL["amount"]]) if pd.notna(row[EXCEL_COL["amount"]]) else None
        except (TypeError, ValueError):
            amount = None
        parts.append({
            "name": name.strip(),
            "drawing_no": str(row[EXCEL_COL["drawing_no"]]).strip() if pd.notna(row[EXCEL_COL["drawing_no"]]) else "",
            "unit_price": unit_price,
            "qty": qty,
            "amount": amount,
            "price_source": str(row[EXCEL_COL["price_source"]]).strip() if pd.notna(row[EXCEL_COL["price_source"]]) else "",
        })
    return parts


def parse_excel_costs(df: pd.DataFrame):
    """抽费用项 + TOTAL，返回 list of dict (含未映射到 system 的 Excel-only 项)"""
    costs = []
    for r, label, code, col in EXCEL_COSTS:
        if r >= len(df):
            continue
        row = df.iloc[r]
        try:
            v = float(row[col]) if pd.notna(row[col]) else None
        except (TypeError, ValueError):
            v = None
        costs.append({
            "excel_label": label,
            "cost_code": code,
            "amount": v,
        })
    return costs


# ============== 系统 API ==============

def login() -> str:
    body = json.dumps({"username": API_USER, "password": API_PASS, "businessUnitType": API_BU}).encode()
    req = urllib.request.Request(
        f"{API_BASE}/api/v1/auth/login",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    return json.load(urllib.request.urlopen(req))["data"]["token"]


def fetch_detail(token: str, oa_no: str, product_code: str) -> dict:
    url = (
        f"{API_BASE}/api/v1/cost-run/detail"
        f"?oaNo={urllib.parse.quote(oa_no)}&productCode={urllib.parse.quote(product_code)}"
    )
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    return json.load(urllib.request.urlopen(req))["data"]


def trigger_trial(token: str, oa_no: str):
    body = json.dumps({"oaNo": oa_no}).encode()
    req = urllib.request.Request(
        f"{API_BASE}/api/v1/cost-run/trial",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        urllib.request.urlopen(req)
    except urllib.error.HTTPError:
        pass


def wait_trial(token: str, oa_no: str, timeout_s: int = 30):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        url = f"{API_BASE}/api/v1/cost-run/progress?oaNo={urllib.parse.quote(oa_no)}"
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
        d = json.load(urllib.request.urlopen(req))["data"]
        if d.get("status") in ("DONE", "ERROR"):
            return d
        time.sleep(0.2)
    return {"status": "TIMEOUT"}


# ============== 比对逻辑 ==============

def diff_value(a, b) -> str:
    """两个金额比对，返回 ✓ / ⚠ / ❌"""
    if a is None and b is None:
        return "✓ 双方空"
    if a is None:
        return "❌ Excel 缺"
    if b is None:
        return "❌ 系统缺"
    if abs(a - b) <= PRECISION:
        return "✓"
    return f"⚠ 差 {b - a:+.4f}"


def reconcile_parts(excel_parts, sys_parts):
    """按部品名 fuzzy 匹配（Excel 名 ≈ 系统 partName）+ amount 比对。"""
    print("\n" + "=" * 70)
    print(f" 部品对账：Excel {len(excel_parts)} 行 vs 系统 {len(sys_parts)} 行")
    print("=" * 70)

    # 系统侧 partName → list（同名可多行）
    sys_by_name = {}
    for p in sys_parts:
        name = (p.get("partName") or "").strip()
        sys_by_name.setdefault(name, []).append(p)

    matched_count = 0
    excel_only = []
    diff_rows = []

    for ep in excel_parts:
        name = ep["name"]
        sys_candidates = sys_by_name.get(name, [])
        if not sys_candidates:
            # 名不一致试试去掉空白/特殊字符
            for k in sys_by_name:
                if name.replace(" ", "") == k.replace(" ", ""):
                    sys_candidates = sys_by_name[k]
                    break
        if not sys_candidates:
            excel_only.append(ep)
            continue
        # 多个同名 → 按 unit_price 最近的一条
        sp = min(sys_candidates, key=lambda x: abs((x.get("unitPrice") or 0) - (ep["unit_price"] or 0)))
        sys_amount = float(sp["amount"]) if sp.get("amount") is not None else None
        verdict = diff_value(ep["amount"], sys_amount)
        if verdict.startswith("✓"):
            matched_count += 1
        else:
            diff_rows.append((ep, sp, sys_amount, verdict))

    # 系统多出的（Excel 没有）
    excel_names = {p["name"] for p in excel_parts}
    sys_only = [p for p in sys_parts if (p.get("partName") or "").strip() not in excel_names]

    print(f"  [一致 ✓] {matched_count} 行")
    print(f"  [Excel 缺] {len(sys_only)} 行（系统多出）")
    print(f"  [系统缺] {len(excel_only)} 行（Excel 多出）")
    print(f"  [差异 ⚠/❌] {len(diff_rows)} 行")

    if sys_only:
        print("\n  -- 系统多出（Excel 缺）--")
        for sp in sys_only[:20]:
            print(f"    {sp.get('partName',''):<22}  系统 amount={sp.get('amount')}  source={sp.get('priceSource')}")
        if len(sys_only) > 20:
            print(f"    ... 余 {len(sys_only)-20} 行省略")

    if excel_only:
        print("\n  -- Excel 多出（系统缺）--")
        for ep in excel_only:
            print(f"    {ep['name']:<22}  Excel amount={ep['amount']}  drawing={ep['drawing_no']}")

    if diff_rows:
        print("\n  -- 双方都有但金额差 ≥ 0.01 --")
        for ep, sp, sys_amount, verdict in diff_rows:
            print(f"    {ep['name']:<22}  Excel={ep['amount']!r:>14}  系统={sys_amount!r:>14}  {verdict}")
            if sp.get("priceSource") in ("NO_ROUTE", "ERROR"):
                print(f"        系统 priceSource={sp.get('priceSource')}  remark={sp.get('remark','')}")


def reconcile_costs(excel_costs, sys_costs):
    """费用项一对一比对（按 cost_code 映射）"""
    print("\n" + "=" * 70)
    print(f" 费用对账：Excel {len(excel_costs)} 项 vs 系统 {len(sys_costs)} 项")
    print("=" * 70)

    sys_by_code = {c.get("costCode"): c for c in sys_costs}
    matched = 0
    diffs = []
    excel_only_no_map = []

    for ec in excel_costs:
        code = ec["cost_code"]
        if code is None:
            excel_only_no_map.append(ec)
            continue
        sc = sys_by_code.get(code)
        sys_amount = float(sc["amount"]) if sc and sc.get("amount") is not None else None
        verdict = diff_value(ec["amount"], sys_amount)
        if verdict.startswith("✓"):
            matched += 1
        else:
            diffs.append((ec, sc, sys_amount, verdict))

    print(f"  [一致 ✓] {matched} 项")
    print(f"  [差异 ⚠/❌] {len(diffs)} 项")
    print(f"  [Excel-only 未映射] {len(excel_only_no_map)} 项")

    if diffs:
        print("\n  -- 差异明细 --")
        for ec, sc, sys_amount, verdict in diffs:
            print(f"    {ec['excel_label']:<14} ({ec['cost_code']:<28})  "
                  f"Excel={ec['amount']!r:>10}  系统={sys_amount!r:>10}  {verdict}")
            if sc and sc.get("remark"):
                print(f"        系统 remark={sc.get('remark')}")

    if excel_only_no_map:
        print("\n  -- Excel 项无对应系统 cost_code --")
        for ec in excel_only_no_map:
            print(f"    {ec['excel_label']:<14}  Excel={ec['amount']}")


def reconcile_total(excel_costs, sys_total):
    print("\n" + "=" * 70)
    print(" 总成本")
    print("=" * 70)
    excel_total = next((c["amount"] for c in excel_costs if c["cost_code"] == "TOTAL"), None)
    print(f"  Excel: {excel_total}")
    print(f"  系统:  {sys_total}")
    print(f"  对账:  {diff_value(excel_total, sys_total)}")


# ============== 主流程 ==============

def main():
    oa_no = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OA
    product_code = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_PRODUCT

    print(f"对账目标：OA={oa_no}  产品={product_code}")
    print(f"Excel:   {EXCEL_PATH}")
    print(f"API:     {API_BASE}")

    # 1) 解密 + 解析 Excel
    print("\n[1/4] 读 Excel 见机表3...")
    df = load_excel(EXCEL_PATH, EXCEL_PASSWORD)
    excel_parts = parse_excel_parts(df)
    excel_costs = parse_excel_costs(df)
    print(f"  Excel 部品 {len(excel_parts)} 行 / 费用 {len(excel_costs)} 项")

    # 2) 登录 + 触发试算（如已是 DONE 状态，重跑也只刷新 progress）
    print("\n[2/4] 登录 + 触发试算...")
    token = login()
    trigger_trial(token, oa_no)
    final = wait_trial(token, oa_no)
    print(f"  试算 status={final.get('status')}  message={final.get('message')}")

    # 3) 拉系统 detail
    print("\n[3/4] 拉系统 cost-run/detail...")
    detail = fetch_detail(token, oa_no, product_code)
    sys_parts = detail.get("partItems") or []
    sys_costs = detail.get("costItems") or []
    sys_total = detail.get("total")
    print(f"  系统 部品 {len(sys_parts)} 行 / 费用 {len(sys_costs)} 项 / TOTAL={sys_total}")

    # 4) 三类对账
    print("\n[4/4] 对账...")
    reconcile_parts(excel_parts, sys_parts)
    reconcile_costs(excel_costs, sys_costs)
    reconcile_total(excel_costs, sys_total)

    print("\n" + "=" * 70)
    print(" 报告完成。差异行需逐条人工分析（系统 bug / 业务待补 / 口径差异）。")
    print("=" * 70)


if __name__ == "__main__":
    main()
