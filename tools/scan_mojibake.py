#!/usr/bin/env python3
"""
扫描 marketing_cost 库所有 text 列，找出真正含 mojibake 的列。
判定法（hex 字节 pattern）：
  正常 UTF-8 中文：E?-EF + 80-BF + 80-BF（如 "财"=E8B4A2）—— 不可能含连续 C2-C5。
  Mojibake（utf-8→latin1→utf-8 二次编码）：原本一个 E? 80-BF 80-BF 中文变成
  3 个 utf-8 序列：(C3 A?) + (C2 8?-B?) + (C2 8?-B?)，所以表里出现连续 ≥2 个 C2/C3/C5 起头的 2 字节序列。
判定特征：HEX 串包含 ≥2 段连续 (C2|C3|C5)+2 hex 字符，间隔为 0。
只读 SELECT，不动数据。
"""
import re
import subprocess

DB = "marketing_cost"
# 同时含 (C2/C3/C5)-?? 起头 2-byte UTF8 + 紧邻另一个 (C2/C3/C5)-?? → mojibake
# 例：C3A8C2B4C2A2 = 'è' + '´' + '¢' = "财" 的 mojibake
MOJI_RE = re.compile(r"((C2|C3|C5)[0-9A-F]{2}|E280[0-9A-F]{2}|CB[8-9A-F][0-9A-F]){3,}", re.IGNORECASE)


def mysql(sql):
    p = subprocess.run(
        [
            "docker", "exec", "new_mysql",
            "mysql", "--default-character-set=utf8mb4",
            "-uroot", "-proot123", "-N", "-B", DB, "-e", sql,
        ],
        capture_output=True, text=True,
    )
    return [l for l in p.stdout.split("\n") if l and "[Warning]" not in l and "Using a password" not in l]


cols = mysql("""
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.columns
WHERE table_schema='marketing_cost'
  AND DATA_TYPE IN ('varchar','char','text','tinytext','mediumtext','longtext');
""")
print(f"[INFO] 扫描候选列 = {len(cols)}")

contaminated = []
for line in cols:
    parts = line.split("\t")
    if len(parts) != 2:
        continue
    table, col = parts
    # 直接拉所有非空、含 ≥6 字节的值的 hex（剔除短/纯 ASCII 列）
    sql = (
        f"SELECT `{col}`, HEX(`{col}`) FROM `{table}` "
        f"WHERE `{col}` IS NOT NULL AND OCTET_LENGTH(`{col}`) >= 4 LIMIT 5000;"
    )
    out = mysql(sql)
    moji_rows = []
    for r in out:
        f = r.split("\t")
        if len(f) < 2:
            continue
        val, hex_ = f[0], f[1]
        if MOJI_RE.search(hex_):
            moji_rows.append((val, hex_))
    if moji_rows:
        # 总数另查
        total = len(moji_rows)
        sample_val, sample_hex = moji_rows[0]
        contaminated.append((table, col, total, sample_val, sample_hex))

print(f"[INFO] 真 mojibake 列 = {len(contaminated)}")
print()
print("=== 污染清单 ===")
print(f"{'TABLE':<35}  {'COLUMN':<25}  {'≥行':>5}  SAMPLE")
print("-" * 110)
for t, c, n, v, h in contaminated:
    v_short = v[:35] + ("..." if len(v) > 35 else "")
    h_short = h[:50] + ("..." if len(h) > 50 else "")
    print(f"{t:<35}  {c:<25}  {n:>5}  {v_short}  |  {h_short}")
