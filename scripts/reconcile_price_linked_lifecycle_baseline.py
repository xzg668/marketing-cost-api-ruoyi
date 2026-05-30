#!/usr/bin/env python3
"""
Compare two price-linked lifecycle baseline exports.

Usage:
  python3 scripts/reconcile_price_linked_lifecycle_baseline.py before_dir after_dir
  python3 scripts/reconcile_price_linked_lifecycle_baseline.py --self-check before_dir
  python3 scripts/reconcile_price_linked_lifecycle_baseline.py --self-test

The script returns exit code 0 when no diff is found and 1 when business data
diffs are detected. It never writes business data.
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


DETAIL_LIMIT = 50


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Reconcile price-linked lifecycle baselines.")
    parser.add_argument("before", nargs="?", help="Before snapshot directory.")
    parser.add_argument("after", nargs="?", help="After snapshot directory.")
    parser.add_argument("--self-check", metavar="DIR", help="Compare a snapshot directory with itself.")
    parser.add_argument("--self-test", action="store_true", help="Run an in-memory smoke test.")
    parser.add_argument("--output", help="Write diff report JSON to this path.")
    return parser.parse_args()


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def normalize_scalar(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, str):
        return value.strip()
    return value


def normalize_record(record: Dict[str, Any], fields: Sequence[str]) -> Dict[str, Any]:
    return {field: normalize_scalar(record.get(field)) for field in fields}


def stable_key(record: Dict[str, Any], fields: Sequence[str]) -> str:
    payload = normalize_record(record, fields)
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def index_by(rows: Sequence[Dict[str, Any]], key_fields: Sequence[str]) -> Dict[str, Dict[str, Any]]:
    indexed: Dict[str, Dict[str, Any]] = {}
    for row in rows:
        key = stable_key(row, key_fields)
        if key in indexed:
            duplicate = dict(row)
            duplicate["_duplicate_key"] = True
            indexed[key + f"#dup#{len(indexed)}"] = duplicate
        else:
            indexed[key] = dict(row)
    return indexed


def comparable(row: Dict[str, Any], fields: Sequence[str]) -> Dict[str, Any]:
    return normalize_record(row, fields)


def compact_key(key: str) -> Any:
    try:
        return json.loads(key)
    except json.JSONDecodeError:
        return key


def diff_rows(
    name: str,
    before_rows: Sequence[Dict[str, Any]],
    after_rows: Sequence[Dict[str, Any]],
    key_fields: Sequence[str],
    compare_fields: Sequence[str],
) -> Dict[str, Any]:
    before_index = index_by(before_rows, key_fields)
    after_index = index_by(after_rows, key_fields)
    before_keys = set(before_index)
    after_keys = set(after_index)

    missing_after = sorted(before_keys - after_keys)
    missing_before = sorted(after_keys - before_keys)
    changed = []
    for key in sorted(before_keys & after_keys):
        before_cmp = comparable(before_index[key], compare_fields)
        after_cmp = comparable(after_index[key], compare_fields)
        if before_cmp != after_cmp:
            changed.append(
                {
                    "key": compact_key(key),
                    "before": before_cmp,
                    "after": after_cmp,
                }
            )

    return {
        "name": name,
        "before_count": len(before_rows),
        "after_count": len(after_rows),
        "missing_after_count": len(missing_after),
        "missing_before_count": len(missing_before),
        "changed_count": len(changed),
        "missing_after": [compact_key(key) for key in missing_after[:DETAIL_LIMIT]],
        "missing_before": [compact_key(key) for key in missing_before[:DETAIL_LIMIT]],
        "changed": changed[:DETAIL_LIMIT],
    }


def load_snapshot(path: Path) -> Dict[str, Any]:
    return {
        "path": str(path),
        "metadata": load_json(path / "metadata.json", {}),
        "summary": load_json(path / "summary.json", {}),
        "factor_identities": load_json(path / "lp_factor_identity.json", []),
        "monthly_prices": load_json(path / "factor_monthly_prices_with_identity.json", []),
        "current_formulas": load_json(path / "current_linked_formulas.json", []),
        "current_bindings": load_json(path / "current_price_variable_bindings.json", []),
        "calc_results": load_json(path / "linked_calc_results.json", []),
    }


def compare_snapshots(before: Dict[str, Any], after: Dict[str, Any]) -> Dict[str, Any]:
    checks = []
    checks.append(
        diff_rows(
            "factor_identity",
            before["factor_identities"],
            after["factor_identities"],
            ["business_unit_type", "factor_seq_no", "factor_name", "short_name", "price_source"],
            ["status"],
        )
    )
    checks.append(
        diff_rows(
            "factor_monthly_price",
            before["monthly_prices"],
            after["monthly_prices"],
            [
                "business_unit_type",
                "factor_seq_no",
                "factor_name",
                "short_name",
                "price_source",
                "price_month",
            ],
            ["price", "tax_included", "status"],
        )
    )
    checks.append(
        diff_rows(
            "current_linked_formula",
            before["current_formulas"],
            after["current_formulas"],
            ["business_unit_type", "pricing_month", "material_code", "supplier_code", "spec_model"],
            [
                "formula_expr",
                "formula_expr_cn",
                "blank_weight",
                "net_weight",
                "process_fee",
                "agent_fee",
                "manual_price",
                "tax_included",
                "effective_from",
                "effective_to",
            ],
        )
    )
    checks.append(
        diff_rows(
            "current_price_variable_binding",
            before["current_bindings"],
            after["current_bindings"],
            [
                "business_unit_type",
                "pricing_month",
                "material_code",
                "supplier_code",
                "spec_model",
                "token_name",
            ],
            [
                "factor_code",
                "price_source",
                "factor_identity_id",
                "factor_monthly_price_id",
                "factor_seq_no",
                "factor_name",
                "short_name",
                "factor_identity_price_source",
                "effective_date",
                "expiry_date",
                "source",
            ],
        )
    )
    checks.append(
        diff_rows(
            "linked_calc_result",
            before["calc_results"],
            after["calc_results"],
            ["business_unit_type", "oa_no", "item_code", "scene_type", "quote_request_id", "quote_item_id", "source_line_id"],
            ["bom_qty", "part_unit_price", "part_amount", "trace_json_sha256"],
        )
    )

    summary_checks = []
    for field in [
        "factor_identity_count",
        "factor_monthly_price_count",
        "factor_monthly_price_value_sha256",
        "current_linked_formula_count",
        "current_linked_formula_sha256",
        "current_binding_count",
        "current_binding_sha256",
        "linked_calc_result_count",
        "linked_calc_result_sha256",
    ]:
        before_value = before["summary"].get(field)
        after_value = after["summary"].get(field)
        if before_value != after_value:
            summary_checks.append({"field": field, "before": before_value, "after": after_value})

    diff_count = len(summary_checks)
    for check in checks:
        diff_count += check["missing_after_count"] + check["missing_before_count"] + check["changed_count"]

    return {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "before": before["path"],
        "after": after["path"],
        "diff_count": diff_count,
        "summary_diffs": summary_checks,
        "checks": checks,
    }


def print_report(report: Dict[str, Any]) -> None:
    print(f"Compared: {report['before']} -> {report['after']}")
    print(f"Diff count: {report['diff_count']}")
    if report["summary_diffs"]:
        print("\nSummary diffs:")
        for diff in report["summary_diffs"][:DETAIL_LIMIT]:
            print(f"  {diff['field']}: {diff['before']} -> {diff['after']}")
    print("\nDetail checks:")
    for check in report["checks"]:
        print(
            f"  {check['name']}: before={check['before_count']} after={check['after_count']} "
            f"missing_after={check['missing_after_count']} "
            f"missing_before={check['missing_before_count']} changed={check['changed_count']}"
        )
        for item in check["changed"][:5]:
            print(f"    changed key={item['key']}")


def write_report(path: Optional[str], report: Dict[str, Any]) -> None:
    if not path:
        return
    output = Path(path).expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Report written to: {output}")


def write_sample_snapshot(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    data = {
        "metadata.json": {"filters": {"business_unit": "COMMERCIAL", "pricing_month": "2026-05"}},
        "lp_factor_identity.json": [
            {
                "id": 1,
                "business_unit_type": "COMMERCIAL",
                "factor_seq_no": "1",
                "factor_name": "Copper",
                "short_name": "Cu",
                "price_source": "Market",
                "status": "ACTIVE",
            }
        ],
        "factor_monthly_prices_with_identity.json": [
            {
                "factor_monthly_price_id": 10,
                "factor_identity_id": 1,
                "business_unit_type": "COMMERCIAL",
                "factor_seq_no": "1",
                "factor_name": "Copper",
                "short_name": "Cu",
                "price_source": "Market",
                "price_month": "2026-05",
                "price": "80.000000",
                "tax_included": 1,
                "status": "ACTIVE",
            }
        ],
        "current_linked_formulas.json": [
            {
                "linked_item_id": 20,
                "business_unit_type": "COMMERCIAL",
                "pricing_month": "2026-05",
                "material_code": "M-001",
                "supplier_code": "S-001",
                "spec_model": "SPEC",
                "formula_expr": "[Cu]+[process_fee]",
                "formula_expr_cn": "Cu+process_fee",
                "blank_weight": None,
                "net_weight": None,
                "process_fee": "5.000000",
                "agent_fee": None,
                "manual_price": "85.000000",
                "tax_included": 1,
                "effective_from": "2026-05-01",
                "effective_to": None,
            }
        ],
        "current_price_variable_bindings.json": [
            {
                "binding_id": 30,
                "linked_item_id": 20,
                "business_unit_type": "COMMERCIAL",
                "pricing_month": "2026-05",
                "material_code": "M-001",
                "supplier_code": "S-001",
                "spec_model": "SPEC",
                "token_name": "Cu",
                "factor_code": "Cu",
                "price_source": "Market",
                "factor_identity_id": 1,
                "factor_monthly_price_id": 10,
                "factor_seq_no": "1",
                "factor_name": "Copper",
                "short_name": "Cu",
                "factor_identity_price_source": "Market",
                "effective_date": "2026-05-01",
                "expiry_date": None,
                "source": "EXCEL_INFERRED",
            }
        ],
        "linked_calc_results.json": [
            {
                "id": 40,
                "business_unit_type": "COMMERCIAL",
                "oa_no": "OA-001",
                "item_code": "M-001",
                "scene_type": "QUOTE",
                "quote_request_id": None,
                "quote_item_id": None,
                "source_line_id": None,
                "bom_qty": "1.000000",
                "part_unit_price": "85.000000",
                "part_amount": "85.000000",
                "trace_json_sha256": "abc",
            }
        ],
    }
    summary = {
        "factor_identity_count": 1,
        "factor_monthly_price_count": 1,
        "factor_monthly_price_value_sha256": "sample-monthly",
        "current_linked_formula_count": 1,
        "current_linked_formula_sha256": "sample-formula",
        "current_binding_count": 1,
        "current_binding_sha256": "sample-binding",
        "linked_calc_result_count": 1,
        "linked_calc_result_sha256": "sample-calc",
    }
    data["summary.json"] = summary
    for name, payload in data.items():
        (path / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run_self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="lp-baseline-self-test-") as tmp:
        root = Path(tmp)
        before = root / "before"
        after = root / "after"
        write_sample_snapshot(before)
        shutil.copytree(before, after)
        report = compare_snapshots(load_snapshot(before), load_snapshot(after))
        print_report(report)
        if report["diff_count"] != 0:
            return 1

        changed = load_json(after / "factor_monthly_prices_with_identity.json", [])
        changed[0]["price"] = "81.000000"
        (after / "factor_monthly_prices_with_identity.json").write_text(
            json.dumps(changed, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        changed_report = compare_snapshots(load_snapshot(before), load_snapshot(after))
        if changed_report["diff_count"] == 0:
            print("ERROR: self-test failed to detect a modified monthly price.", file=sys.stderr)
            return 1
    print("Self-test passed.")
    return 0


def main() -> int:
    args = parse_args()
    if args.self_test:
        return run_self_test()

    if args.self_check:
        before_path = Path(args.self_check).expanduser().resolve()
        after_path = before_path
    else:
        if not args.before or not args.after:
            print("ERROR: provide before and after directories, --self-check DIR, or --self-test.", file=sys.stderr)
            return 2
        before_path = Path(args.before).expanduser().resolve()
        after_path = Path(args.after).expanduser().resolve()

    if not before_path.exists():
        print(f"ERROR: before snapshot not found: {before_path}", file=sys.stderr)
        return 2
    if not after_path.exists():
        print(f"ERROR: after snapshot not found: {after_path}", file=sys.stderr)
        return 2

    report = compare_snapshots(load_snapshot(before_path), load_snapshot(after_path))
    print_report(report)
    write_report(args.output, report)
    return 0 if report["diff_count"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
