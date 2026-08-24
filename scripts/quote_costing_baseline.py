#!/usr/bin/env python3
"""导出并比较已核算报价产品的只读基线。

脚本通过 Docker 容器内的 mysql 客户端执行 SELECT，不包含任何写 SQL。

示例：
  python3 scripts/quote_costing_baseline.py export --version-id 27 --output ../outputs/costing-baseline-v27.json
  python3 scripts/quote_costing_baseline.py compare before.json after.json
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import subprocess
import sys
from copy import deepcopy
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable


DEFAULT_CONTAINER = "new_mysql"
DEFAULT_DATABASE = "marketing_cost"
DEFAULT_TOLERANCE = Decimal("0.000001")
ALLOWED_COST_CODES = {
    "MANUFACTURE",
    "MANUFACTURE_COST",
    "ADJUSTED_MANUFACTURE_COST",
    "MGMT_EXP",
    "SALES_EXP",
    "FIN_EXP",
}


def _mysql(container: str, database: str, query: str) -> list[dict[str, Any]]:
    normalized = query.strip().rstrip(";")
    if not normalized.upper().startswith(("SELECT ", "SHOW ", "WITH ")):
        raise ValueError("基线工具只允许执行只读 SELECT/SHOW/WITH SQL")
    command = [
        "docker",
        "exec",
        container,
        "sh",
        "-lc",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D "$1" '
        '--default-character-set=utf8mb4 --batch -e "$2"',
        "baseline",
        database,
        normalized,
    ]
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    text = completed.stdout
    if not text.strip():
        return []
    reader = csv.DictReader(io.StringIO(text), delimiter="\t")
    return [
        {key: _decode_mysql_value(value) for key, value in row.items()}
        for row in reader
    ]


def _decode_mysql_value(value: str | None) -> Any:
    if value is None or value == "NULL":
        return None
    return (
        value.replace("\\t", "\t")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\0", "\0")
        .replace("\\\\", "\\")
    )


def _sql_text(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def _one(rows: list[dict[str, Any]], label: str) -> dict[str, Any]:
    if len(rows) != 1:
        raise RuntimeError(f"{label} 期望1行，实际{len(rows)}行")
    return rows[0]


def _ids(rows: Iterable[dict[str, Any]], field: str) -> str:
    values = [str(row[field]) for row in rows if row.get(field) is not None]
    return ",".join(values) if values else "NULL"


def export_baseline(container: str, database: str, version_id: int) -> dict[str, Any]:
    version = _one(
        _mysql(
            container,
            database,
            f"SELECT * FROM lp_quote_cost_run_version WHERE id={int(version_id)}",
        ),
        "成本版本",
    )
    if version.get("status") not in {"CONFIRMED", "SUCCESS", "HISTORY", "VOIDED", "STALE"}:
        raise RuntimeError(f"版本 {version_id} 不是成功/历史成本版本: {version.get('status')}")

    oa_no = str(version["oa_no"])
    item_id = int(str(version["oa_form_item_id"]))
    product_code = str(version["product_code"])
    period_month = str(version["pricing_month"])
    prepare_no = version.get("price_prepare_no")

    form = _one(
        _mysql(container, database, f"SELECT * FROM oa_form WHERE oa_no={_sql_text(oa_no)}"),
        "OA表头",
    )
    item = _one(
        _mysql(container, database, f"SELECT * FROM oa_form_item WHERE id={item_id}"),
        "OA产品行",
    )
    bom_rows = _mysql(
        container,
        database,
        """
        SELECT * FROM lp_bom_costing_row
         WHERE oa_no={oa_no}
           AND oa_form_item_id={item_id}
           AND top_product_code={product_code}
           AND period_month={period_month}
         ORDER BY path, id
        """.format(
            oa_no=_sql_text(oa_no),
            item_id=item_id,
            product_code=_sql_text(product_code),
            period_month=_sql_text(period_month),
        ),
    )
    bom_ids = _ids(bom_rows, "id")
    bom_sub_refs = (
        []
        if bom_ids == "NULL"
        else _mysql(
            container,
            database,
            f"SELECT * FROM lp_bom_costing_row_sub_ref WHERE costing_row_id IN ({bom_ids}) "
            "ORDER BY costing_row_id, id",
        )
    )

    prepare_batch = []
    prepare_items = []
    if prepare_no:
        prepare_batch = _mysql(
            container,
            database,
            f"SELECT * FROM lp_price_prepare_batch WHERE prepare_no={_sql_text(str(prepare_no))}",
        )
        prepare_items = _mysql(
            container,
            database,
            f"SELECT * FROM lp_price_prepare_item WHERE prepare_no={_sql_text(str(prepare_no))} "
            "ORDER BY settlement_key, material_code, id",
        )

    part_items = _mysql(
        container,
        database,
        f"SELECT * FROM lp_cost_run_part_item WHERE cost_run_version_id={version_id} "
        "ORDER BY part_code, id",
    )
    cost_items = _mysql(
        container,
        database,
        f"SELECT * FROM lp_cost_run_cost_item WHERE cost_run_version_id={version_id} "
        "ORDER BY line_no, cost_code, id",
    )
    trace_rows = _mysql(
        container,
        database,
        f"SELECT * FROM lp_cost_run_trace_snapshot WHERE cost_run_version_id={version_id} "
        "ORDER BY trace_type, trace_key, id",
    )

    payload = {
        "schemaVersion": 2,
        "scope": {
            "database": database,
            "oaNo": oa_no,
            "oaFormItemId": str(item_id),
            "productCode": product_code,
            "pricingMonth": period_month,
            "costRunVersionId": str(version_id),
            "costRunNo": version.get("cost_run_no"),
            "pricePrepareNo": prepare_no,
        },
        "oaForm": form,
        "oaFormItem": item,
        "costRunVersion": version,
        "bomCostingRows": bom_rows,
        "bomCostingRowSubRefs": bom_sub_refs,
        "pricePrepareBatch": prepare_batch,
        "pricePrepareItems": prepare_items,
        "costRunPartItems": part_items,
        "costRunCostItems": cost_items,
        "costRunTraceSnapshots": trace_rows,
    }
    payload["counts"] = {
        "bomCostingRows": len(bom_rows),
        "bomCostingRowSubRefs": len(bom_sub_refs),
        "pricePrepareItems": len(prepare_items),
        "costRunPartItems": len(part_items),
        "costRunCostItems": len(cost_items),
        "costRunTraceSnapshots": len(trace_rows),
    }
    payload["businessFingerprint"] = business_fingerprint(payload)
    return payload


def _pick(row: dict[str, Any], fields: tuple[str, ...]) -> dict[str, Any]:
    return {field: row.get(field) for field in fields}


def _canonical_business_data(payload: dict[str, Any]) -> dict[str, Any]:
    bom_fields = (
        "parent_code",
        "material_code",
        "level",
        "path",
        "qty_per_parent",
        "qty_per_top",
        "is_costing_row",
        "subtree_cost_required",
        "settlement_row_type",
        "material_name",
        "material_spec",
        "unit",
        "material_attribute",
        "shape_attr",
        "price_org_code",
        "material_organization_code",
    )
    price_fields = (
        "settlement_key",
        "material_code",
        "material_name",
        "item_type",
        "quantity",
        "unit_price",
        "amount",
        "price_source",
        "status",
        "result_ref_type",
    )
    part_fields = (
        "part_code",
        "part_name",
        "part_drawing_no",
        "qty",
        "material",
        "shape_attr",
        "price_source",
        "unit_price",
        "amount",
        "price_org_code",
        "material_organization_code",
    )
    cost_fields = (
        "line_no",
        "cost_code",
        "cost_name",
        "base_amount",
        "rate",
        "amount",
        "source_table",
        "category",
    )
    return {
        "scope": {
            key: payload.get("scope", {}).get(key)
            for key in ("oaNo", "oaFormItemId", "productCode", "pricingMonth")
        },
        "bom": [_pick(row, bom_fields) for row in payload.get("bomCostingRows", [])],
        "prices": [_pick(row, price_fields) for row in payload.get("pricePrepareItems", [])],
        "parts": [_pick(row, part_fields) for row in payload.get("costRunPartItems", [])],
        "costs": [_pick(row, cost_fields) for row in payload.get("costRunCostItems", [])],
    }


def business_fingerprint(payload: dict[str, Any]) -> str:
    encoded = json.dumps(
        _canonical_business_data(payload),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _decimal(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except InvalidOperation:
        return None


def _numeric_equal(left: Any, right: Any, tolerance: Decimal) -> bool:
    left_decimal = _decimal(left)
    right_decimal = _decimal(right)
    if left_decimal is None or right_decimal is None:
        return left == right
    return abs(left_decimal - right_decimal) <= tolerance


def _row_key(section: str, row: dict[str, Any], index: int) -> str:
    candidates = {
        "bom": ("path", "material_code", "settlement_row_type"),
        "prices": ("settlement_key", "material_code", "item_type"),
        "parts": ("part_code", "part_drawing_no", "price_source"),
        "costs": ("cost_code", "line_no", "cost_name"),
    }[section]
    values = [str(row.get(field) or "") for field in candidates]
    return "|".join(values) + f"|#{index}"


def _indexed(section: str, rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    counts: dict[str, int] = {}
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        base = _row_key(section, row, 0).rsplit("|#", 1)[0]
        index = counts.get(base, 0)
        counts[base] = index + 1
        result[f"{base}|#{index}"] = row
    return result


def compare_baselines(
    before: dict[str, Any],
    after: dict[str, Any],
    tolerance: Decimal = DEFAULT_TOLERANCE,
) -> dict[str, Any]:
    left = _canonical_business_data(before)
    right = _canonical_business_data(after)
    blocking: list[dict[str, Any]] = []
    allowed: list[dict[str, Any]] = []

    if left["scope"] != right["scope"]:
        blocking.append({"section": "scope", "before": left["scope"], "after": right["scope"]})

    numeric_fields = {
        "bom": {"qty_per_parent", "qty_per_top"},
        "prices": {"quantity", "unit_price", "amount"},
        "parts": {"qty", "unit_price", "amount"},
        "costs": {"base_amount", "rate", "amount"},
    }
    for section in ("bom", "prices", "parts", "costs"):
        left_rows = _indexed(section, left[section])
        right_rows = _indexed(section, right[section])
        for key in sorted(set(left_rows) | set(right_rows)):
            before_row = left_rows.get(key)
            after_row = right_rows.get(key)
            if before_row is None or after_row is None:
                blocking.append(
                    {"section": section, "key": key, "before": before_row, "after": after_row}
                )
                continue
            fields = sorted(set(before_row) | set(after_row))
            for field in fields:
                before_value = before_row.get(field)
                after_value = after_row.get(field)
                equal = (
                    _numeric_equal(before_value, after_value, tolerance)
                    if field in numeric_fields[section]
                    else before_value == after_value
                )
                if equal:
                    continue
                difference = {
                    "section": section,
                    "key": key,
                    "field": field,
                    "before": before_value,
                    "after": after_value,
                }
                if section == "costs" and before_row.get("cost_code") in ALLOWED_COST_CODES:
                    allowed.append(difference)
                else:
                    blocking.append(difference)

    return {
        "passed": not blocking,
        "blockingDifferences": blocking,
        "allowedCostDifferences": allowed,
        "tolerance": str(tolerance),
    }


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _self_test() -> None:
    sample = {
        "scope": {
            "oaNo": "OA-1",
            "oaFormItemId": "1",
            "productCode": "P-1",
            "pricingMonth": "2026-08",
        },
        "bomCostingRows": [
            {"path": "/P-1/M-1/", "material_code": "M-1", "qty_per_top": "2"}
        ],
        "pricePrepareItems": [
            {
                "settlement_key": "S-1",
                "material_code": "M-1",
                "quantity": "2",
                "unit_price": "10",
                "amount": "20",
                "price_source": "FIXED_PRICE",
            }
        ],
        "costRunPartItems": [
            {
                "part_code": "M-1",
                "qty": "2",
                "unit_price": "10",
                "amount": "20",
                "price_source": "FIXED_PRICE",
            }
        ],
        "costRunCostItems": [
            {"line_no": "1", "cost_code": "MANUFACTURE", "rate": "0.1", "amount": "2"}
        ],
    }
    assert compare_baselines(sample, deepcopy(sample))["passed"]

    changed_part = deepcopy(sample)
    changed_part["costRunPartItems"][0]["unit_price"] = "10.1"
    assert not compare_baselines(sample, changed_part)["passed"]

    changed_allowed_cost = deepcopy(sample)
    changed_allowed_cost["costRunCostItems"][0]["rate"] = "0.2"
    result = compare_baselines(sample, changed_allowed_cost)
    assert result["passed"]
    assert len(result["allowedCostDifferences"]) == 1


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="报价核算真实基线导出/比较工具")
    subparsers = parser.add_subparsers(dest="command", required=True)

    export = subparsers.add_parser("export", help="从 MySQL 只读导出一个成功成本版本")
    export.add_argument("--version-id", type=int, required=True)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--container", default=DEFAULT_CONTAINER)
    export.add_argument("--database", default=DEFAULT_DATABASE)

    compare = subparsers.add_parser("compare", help="比较两个基线文件")
    compare.add_argument("before", type=Path)
    compare.add_argument("after", type=Path)
    compare.add_argument("--report", type=Path)
    compare.add_argument("--tolerance", type=Decimal, default=DEFAULT_TOLERANCE)

    subparsers.add_parser("self-test", help="运行比较规则自测试")
    return parser


def main() -> int:
    args = _parser().parse_args()
    if args.command == "self-test":
        _self_test()
        print("self-test passed")
        return 0
    if args.command == "export":
        payload = export_baseline(args.container, args.database, args.version_id)
        _write_json(args.output, payload)
        print(json.dumps({"output": str(args.output), "scope": payload["scope"], "counts": payload["counts"], "businessFingerprint": payload["businessFingerprint"]}, ensure_ascii=False, indent=2))
        return 0
    if args.command == "compare":
        report = compare_baselines(_read_json(args.before), _read_json(args.after), args.tolerance)
        if args.report:
            _write_json(args.report, report)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if report["passed"] else 1
    raise AssertionError(args.command)


if __name__ == "__main__":
    sys.exit(main())
