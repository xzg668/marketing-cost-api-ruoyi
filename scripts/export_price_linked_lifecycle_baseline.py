#!/usr/bin/env python3
"""
Export a read-only baseline for the price-linked monthly import lifecycle refactor.

The snapshot is intentionally file based so it can be checked before and after
the refactor without coupling to Java test fixtures.

Usage:
  python3 scripts/export_price_linked_lifecycle_baseline.py --output-dir /tmp/lp-baseline-before

Optional filters:
  --business-unit COMMERCIAL
  --pricing-month 2026-05
  --as-of-date 2026-05-01
  --material-codes S001139,301010012

Database connection defaults match application.yml and can be overridden with
DB_HOST, DB_PORT, DB_USER, DB_PASS, DB_NAME.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import decimal
import hashlib
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

try:
    import pymysql
    from pymysql.cursors import DictCursor
except ImportError:  # pragma: no cover - depends on local tool env
    pymysql = None
    DictCursor = None

try:
    import mysql.connector
except ImportError:  # pragma: no cover - depends on local tool env
    mysql = None


BASE_TABLES = [
    "lp_factor_identity",
    "lp_factor_monthly_price",
    "lp_price_linked_item",
    "lp_price_variable_binding",
    "lp_material_factor_binding_std",
    "lp_excel_auto_binding_import_log",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export price-linked lifecycle baseline.")
    parser.add_argument("--output-dir", required=True, help="Directory to write JSON/CSV files.")
    parser.add_argument("--business-unit", default=os.environ.get("BUSINESS_UNIT_TYPE"))
    parser.add_argument("--pricing-month", default=os.environ.get("PRICING_MONTH"))
    parser.add_argument(
        "--as-of-date",
        default=os.environ.get("AS_OF_DATE") or dt.date.today().isoformat(),
        help="Date used for current-effective formula/binding views. Default: today.",
    )
    parser.add_argument(
        "--material-codes",
        default=os.environ.get("MATERIAL_CODES"),
        help="Comma-separated material/item codes for focused export and calc result checks.",
    )
    parser.add_argument("--db-host", default=os.environ.get("DB_HOST", "localhost"))
    parser.add_argument("--db-port", type=int, default=int(os.environ.get("DB_PORT", "3306")))
    parser.add_argument("--db-user", default=os.environ.get("DB_USER", "root"))
    parser.add_argument("--db-pass", default=os.environ.get("DB_PASS", "root123"))
    parser.add_argument("--db-name", default=os.environ.get("DB_NAME", "marketing_cost"))
    return parser.parse_args()


def material_codes(raw: Optional[str]) -> List[str]:
    if not raw:
        return []
    return [item.strip() for item in raw.split(",") if item.strip()]


def json_default(value: Any) -> Any:
    if isinstance(value, (dt.datetime, dt.date, dt.time)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return str(value)
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def normalize_value(value: Any) -> Any:
    if isinstance(value, decimal.Decimal):
        return str(value.normalize())
    if isinstance(value, (dt.datetime, dt.date, dt.time)):
        return value.isoformat()
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def normalize_row(row: Dict[str, Any]) -> Dict[str, Any]:
    return {key: normalize_value(value) for key, value in row.items()}


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, default=json_default, separators=(",", ":"))


def stable_hash(value: Any) -> str:
    return hashlib.sha256(stable_json(value).encode("utf-8")).hexdigest()


def connect(args: argparse.Namespace):
    if pymysql is not None:
        return pymysql.connect(
            host=args.db_host,
            port=args.db_port,
            user=args.db_user,
            password=args.db_pass,
            database=args.db_name,
            charset="utf8mb4",
            cursorclass=DictCursor,
            autocommit=False,
            read_timeout=120,
            write_timeout=120,
        )
    if mysql is not None:
        return mysql.connector.connect(
            host=args.db_host,
            port=args.db_port,
            user=args.db_user,
            password=args.db_pass,
            database=args.db_name,
            charset="utf8mb4",
            use_unicode=True,
            autocommit=False,
        )
    print(
        "ERROR: a MySQL Python driver is required. Install one of: "
        "pip install pymysql OR pip install mysql-connector-python",
        file=sys.stderr,
    )
    raise SystemExit(2)


def open_cursor(conn):
    if pymysql is not None and isinstance(conn, pymysql.connections.Connection):
        return conn.cursor()
    return conn.cursor(dictionary=True)


def fetch_all(cursor, sql: str, params: Sequence[Any] = ()) -> List[Dict[str, Any]]:
    cursor.execute(sql, params)
    return [normalize_row(row) for row in cursor.fetchall()]


def table_exists(cursor, table_name: str) -> bool:
    cursor.execute(
        """
        SELECT 1
          FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name = %s
         LIMIT 1
        """,
        (table_name,),
    )
    return cursor.fetchone() is not None


def table_columns(cursor, table_name: str) -> List[str]:
    cursor.execute(
        """
        SELECT column_name
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = %s
         ORDER BY ordinal_position
        """,
        (table_name,),
    )
    columns = []
    for row in cursor.fetchall():
        column_name = row.get("column_name") or row.get("COLUMN_NAME")
        if column_name:
            columns.append(column_name)
    return columns


def order_by_clause(columns: Sequence[str]) -> str:
    preferred = [col for col in ("business_unit_type", "pricing_month", "price_month", "material_code", "supplier_code", "spec_model", "linked_item_id", "token_name", "id") if col in columns]
    if not preferred and columns:
        preferred = [columns[0]]
    return ", ".join(f"`{col}`" for col in preferred)


def build_base_where(columns: Sequence[str], args: argparse.Namespace, codes: Sequence[str]) -> Tuple[str, List[Any]]:
    conditions: List[str] = []
    params: List[Any] = []
    if args.business_unit and "business_unit_type" in columns:
        conditions.append("`business_unit_type` = %s")
        params.append(args.business_unit)
    if args.pricing_month and "pricing_month" in columns:
        conditions.append("`pricing_month` = %s")
        params.append(args.pricing_month)
    if args.pricing_month and "price_month" in columns:
        conditions.append("`price_month` = %s")
        params.append(args.pricing_month)
    if codes and "material_code" in columns:
        conditions.append("`material_code` IN (" + ",".join(["%s"] * len(codes)) + ")")
        params.extend(codes)
    if codes and "item_code" in columns:
        conditions.append("`item_code` IN (" + ",".join(["%s"] * len(codes)) + ")")
        params.extend(codes)
    if not conditions:
        return "", params
    return " WHERE " + " AND ".join(conditions), params


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=json_default) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: Sequence[Dict[str, Any]]) -> None:
    fieldnames: List[str] = []
    seen = set()
    for row in rows:
        for key in row.keys():
            if key not in seen:
                seen.add(key)
                fieldnames.append(key)
    with path.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: "" if row.get(key) is None else row.get(key) for key in fieldnames})


def write_dataset(output_dir: Path, name: str, rows: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    write_json(output_dir / f"{name}.json", list(rows))
    write_csv(output_dir / f"{name}.csv", list(rows))
    return {"name": name, "row_count": len(rows), "sha256": stable_hash(rows)}


def export_base_tables(cursor, output_dir: Path, args: argparse.Namespace, codes: Sequence[str]) -> Tuple[List[Dict[str, Any]], Dict[str, List[str]]]:
    datasets = []
    schema: Dict[str, List[str]] = {}
    for table in BASE_TABLES:
        if not table_exists(cursor, table):
            datasets.append({"name": table, "row_count": 0, "missing": True})
            schema[table] = []
            continue
        columns = table_columns(cursor, table)
        schema[table] = columns
        where_sql, params = build_base_where(columns, args, codes)
        sql = f"SELECT * FROM `{table}`{where_sql} ORDER BY {order_by_clause(columns)}"
        rows = fetch_all(cursor, sql, params)
        datasets.append(write_dataset(output_dir, table, rows))
    return datasets, schema


def export_factor_monthly_prices_with_identity(cursor, output_dir: Path, args: argparse.Namespace) -> Dict[str, Any]:
    if not (table_exists(cursor, "lp_factor_monthly_price") and table_exists(cursor, "lp_factor_identity")):
        return write_dataset(output_dir, "factor_monthly_prices_with_identity", [])

    conditions = []
    params: List[Any] = []
    if args.business_unit:
        conditions.append("fi.business_unit_type = %s")
        params.append(args.business_unit)
    if args.pricing_month:
        conditions.append("fmp.price_month = %s")
        params.append(args.pricing_month)
    where_sql = (" WHERE " + " AND ".join(conditions)) if conditions else ""
    rows = fetch_all(
        cursor,
        f"""
        SELECT
            fmp.id AS factor_monthly_price_id,
            fmp.factor_identity_id,
            fi.business_unit_type,
            fi.factor_seq_no,
            fi.factor_name,
            fi.short_name,
            fi.price_source,
            fmp.price_month,
            fmp.price,
            fmp.tax_included,
            fmp.status,
            fmp.source_upload_batch_id,
            fmp.created_at,
            fmp.updated_at
          FROM lp_factor_monthly_price fmp
          JOIN lp_factor_identity fi ON fi.id = fmp.factor_identity_id
        {where_sql}
         ORDER BY fi.business_unit_type, fmp.price_month, fi.factor_seq_no, fi.factor_name,
                  fi.short_name, fi.price_source, fmp.id
        """,
        params,
    )
    return write_dataset(output_dir, "factor_monthly_prices_with_identity", rows)


def export_current_linked_formulas(cursor, output_dir: Path, args: argparse.Namespace, codes: Sequence[str]) -> Dict[str, Any]:
    if not table_exists(cursor, "lp_price_linked_item"):
        return write_dataset(output_dir, "current_linked_formulas", [])
    columns = set(table_columns(cursor, "lp_price_linked_item"))
    conditions = []
    params: List[Any] = []
    if "deleted" in columns:
        conditions.append("COALESCE(li.deleted, 0) = 0")
    if args.business_unit and "business_unit_type" in columns:
        conditions.append("li.business_unit_type = %s")
        params.append(args.business_unit)
    if args.pricing_month and "pricing_month" in columns:
        conditions.append("li.pricing_month = %s")
        params.append(args.pricing_month)
    if codes and "material_code" in columns:
        conditions.append("li.material_code IN (" + ",".join(["%s"] * len(codes)) + ")")
        params.extend(codes)
    if "effective_from" in columns:
        conditions.append("(li.effective_from IS NULL OR li.effective_from <= %s)")
        params.append(args.as_of_date)
    if "effective_to" in columns:
        conditions.append("(li.effective_to IS NULL OR li.effective_to >= %s)")
        params.append(args.as_of_date)
    where_sql = (" WHERE " + " AND ".join(conditions)) if conditions else ""
    rows = fetch_all(
        cursor,
        f"""
        SELECT
            li.id AS linked_item_id,
            li.business_unit_type,
            li.pricing_month,
            li.material_code,
            li.material_name,
            li.supplier_code,
            li.supplier_name,
            li.spec_model,
            li.unit,
            li.formula_expr,
            li.formula_expr_cn,
            li.blank_weight,
            li.net_weight,
            li.process_fee,
            li.agent_fee,
            li.manual_price,
            li.tax_included,
            li.effective_from,
            li.effective_to,
            li.order_type,
            li.quota,
            li.created_at,
            li.updated_at
          FROM lp_price_linked_item li
        {where_sql}
         ORDER BY li.business_unit_type, li.pricing_month, li.material_code,
                  li.supplier_code, li.spec_model, li.id
        """,
        params,
    )
    return write_dataset(output_dir, "current_linked_formulas", rows)


def export_current_bindings(cursor, output_dir: Path, args: argparse.Namespace, codes: Sequence[str]) -> Dict[str, Any]:
    required = ["lp_price_variable_binding", "lp_price_linked_item"]
    if not all(table_exists(cursor, table) for table in required):
        return write_dataset(output_dir, "current_price_variable_bindings", [])

    binding_columns = set(table_columns(cursor, "lp_price_variable_binding"))
    linked_columns = set(table_columns(cursor, "lp_price_linked_item"))
    has_factor_identity = table_exists(cursor, "lp_factor_identity")

    conditions = []
    params: List[Any] = []
    if "deleted" in binding_columns:
        conditions.append("COALESCE(b.deleted, 0) = 0")
    if "deleted" in linked_columns:
        conditions.append("COALESCE(li.deleted, 0) = 0")
    if args.business_unit and "business_unit_type" in linked_columns:
        conditions.append("li.business_unit_type = %s")
        params.append(args.business_unit)
    if args.pricing_month and "pricing_month" in linked_columns:
        conditions.append("li.pricing_month = %s")
        params.append(args.pricing_month)
    if codes and "material_code" in linked_columns:
        conditions.append("li.material_code IN (" + ",".join(["%s"] * len(codes)) + ")")
        params.extend(codes)
    if "effective_date" in binding_columns:
        conditions.append("(b.effective_date IS NULL OR b.effective_date <= %s)")
        params.append(args.as_of_date)
    if "expiry_date" in binding_columns:
        conditions.append("(b.expiry_date IS NULL OR b.expiry_date >= %s)")
        params.append(args.as_of_date)
    where_sql = (" WHERE " + " AND ".join(conditions)) if conditions else ""

    factor_select = ""
    factor_join = ""
    if has_factor_identity and "factor_identity_id" in binding_columns:
        factor_select = """,
            fi.business_unit_type AS factor_business_unit_type,
            fi.factor_seq_no,
            fi.factor_name,
            fi.short_name,
            fi.price_source AS factor_identity_price_source"""
        factor_join = " LEFT JOIN lp_factor_identity fi ON fi.id = b.factor_identity_id"

    optional_binding_cols = [
        "factor_identity_id",
        "factor_monthly_price_id",
        "factor_upload_batch_id",
        "standard_binding_id",
        "excel_source_sheet_name",
        "excel_source_cell_ref",
        "excel_formula",
    ]
    optional_select = "".join(
        f",\n            b.{col}" for col in optional_binding_cols if col in binding_columns
    )

    rows = fetch_all(
        cursor,
        f"""
        SELECT
            b.id AS binding_id,
            b.linked_item_id,
            li.business_unit_type,
            li.pricing_month,
            li.material_code,
            li.supplier_code,
            li.spec_model,
            b.token_name,
            b.factor_code,
            b.price_source,
            b.bu_scoped,
            b.effective_date,
            b.expiry_date,
            b.source,
            b.remark{optional_select}{factor_select}
          FROM lp_price_variable_binding b
          JOIN lp_price_linked_item li ON li.id = b.linked_item_id
          {factor_join}
        {where_sql}
         ORDER BY li.business_unit_type, li.pricing_month, li.material_code,
                  li.supplier_code, li.spec_model, b.token_name, b.id
        """,
        params,
    )
    return write_dataset(output_dir, "current_price_variable_bindings", rows)


def export_calc_results(cursor, output_dir: Path, args: argparse.Namespace, codes: Sequence[str]) -> Dict[str, Any]:
    if not table_exists(cursor, "lp_price_linked_calc_item"):
        return write_dataset(output_dir, "linked_calc_results", [])
    columns = set(table_columns(cursor, "lp_price_linked_calc_item"))
    conditions = []
    params: List[Any] = []
    if args.business_unit and "business_unit_type" in columns:
        conditions.append("business_unit_type = %s")
        params.append(args.business_unit)
    if codes and "item_code" in columns:
        conditions.append("item_code IN (" + ",".join(["%s"] * len(codes)) + ")")
        params.extend(codes)
    where_sql = (" WHERE " + " AND ".join(conditions)) if conditions else ""
    select_cols = [
        col
        for col in [
            "id",
            "oa_no",
            "item_code",
            "business_unit_type",
            "scene_type",
            "quote_request_id",
            "quote_item_id",
            "source_line_id",
            "shape_attr",
            "bom_qty",
            "part_unit_price",
            "part_amount",
            "trace_json",
            "created_at",
            "updated_at",
        ]
        if col in columns
    ]
    rows = fetch_all(
        cursor,
        f"""
        SELECT {", ".join("`" + col + "`" for col in select_cols)}
          FROM lp_price_linked_calc_item
        {where_sql}
         ORDER BY {order_by_clause(list(columns))}
        """,
        params,
    )
    for row in rows:
        trace = row.get("trace_json")
        row["trace_json_sha256"] = hashlib.sha256((trace or "").encode("utf-8")).hexdigest()
    return write_dataset(output_dir, "linked_calc_results", rows)


def build_summary(output_dir: Path, datasets: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    def load(name: str) -> List[Dict[str, Any]]:
        path = output_dir / f"{name}.json"
        if not path.exists():
            return []
        return json.loads(path.read_text(encoding="utf-8"))

    factor_identity = load("lp_factor_identity")
    monthly_prices = load("factor_monthly_prices_with_identity")
    current_formulas = load("current_linked_formulas")
    current_bindings = load("current_price_variable_bindings")
    calc_results = load("linked_calc_results")

    monthly_price_keys = [
        {
            "business_unit_type": row.get("business_unit_type"),
            "factor_seq_no": row.get("factor_seq_no"),
            "factor_name": row.get("factor_name"),
            "short_name": row.get("short_name"),
            "price_source": row.get("price_source"),
            "price_month": row.get("price_month"),
            "price": row.get("price"),
        }
        for row in monthly_prices
    ]
    formula_keys = [
        {
            "business_unit_type": row.get("business_unit_type"),
            "pricing_month": row.get("pricing_month"),
            "material_code": row.get("material_code"),
            "supplier_code": row.get("supplier_code"),
            "spec_model": row.get("spec_model"),
            "formula_expr": row.get("formula_expr"),
            "manual_price": row.get("manual_price"),
        }
        for row in current_formulas
    ]
    binding_keys = [
        {
            "business_unit_type": row.get("business_unit_type"),
            "pricing_month": row.get("pricing_month"),
            "material_code": row.get("material_code"),
            "supplier_code": row.get("supplier_code"),
            "spec_model": row.get("spec_model"),
            "token_name": row.get("token_name"),
            "factor_identity_id": row.get("factor_identity_id"),
            "factor_code": row.get("factor_code"),
        }
        for row in current_bindings
    ]
    calc_keys = [
        {
            "business_unit_type": row.get("business_unit_type"),
            "oa_no": row.get("oa_no"),
            "item_code": row.get("item_code"),
            "scene_type": row.get("scene_type"),
            "part_unit_price": row.get("part_unit_price"),
            "part_amount": row.get("part_amount"),
            "trace_json_sha256": row.get("trace_json_sha256"),
        }
        for row in calc_results
    ]

    return {
        "dataset_count": len(datasets),
        "datasets": list(datasets),
        "factor_identity_count": len(factor_identity),
        "factor_monthly_price_count": len(monthly_prices),
        "factor_monthly_price_value_sha256": stable_hash(monthly_price_keys),
        "current_linked_formula_count": len(current_formulas),
        "current_linked_formula_sha256": stable_hash(formula_keys),
        "current_binding_count": len(current_bindings),
        "current_binding_sha256": stable_hash(binding_keys),
        "linked_calc_result_count": len(calc_results),
        "linked_calc_result_sha256": stable_hash(calc_keys),
    }


def main() -> int:
    args = parse_args()
    codes = material_codes(args.material_codes)
    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    conn = connect(args)
    try:
        with open_cursor(conn) as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION")
            datasets, schema = export_base_tables(cursor, output_dir, args, codes)
            datasets.append(export_factor_monthly_prices_with_identity(cursor, output_dir, args))
            datasets.append(export_current_linked_formulas(cursor, output_dir, args, codes))
            datasets.append(export_current_bindings(cursor, output_dir, args, codes))
            datasets.append(export_calc_results(cursor, output_dir, args, codes))
            metadata = {
                "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                "db_host": args.db_host,
                "db_port": args.db_port,
                "db_name": args.db_name,
                "filters": {
                    "business_unit": args.business_unit,
                    "pricing_month": args.pricing_month,
                    "as_of_date": args.as_of_date,
                    "material_codes": codes,
                },
                "schema": schema,
            }
            write_json(output_dir / "metadata.json", metadata)
            summary = build_summary(output_dir, datasets)
            write_json(output_dir / "summary.json", summary)
            conn.rollback()
    finally:
        conn.close()

    print(f"Baseline exported to: {output_dir}")
    print(f"  factor identities: {summary['factor_identity_count']}")
    print(f"  monthly prices: {summary['factor_monthly_price_count']}")
    print(f"  current formulas: {summary['current_linked_formula_count']}")
    print(f"  current bindings: {summary['current_binding_count']}")
    print(f"  calc results: {summary['linked_calc_result_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
