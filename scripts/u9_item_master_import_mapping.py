#!/usr/bin/env python3
"""U9 料品主档 Excel 导入映射工具。"""
from u9_item_master_contract import (
    DATA_START_ROW,
    FIELD_MAPPINGS,
    FIELD_TO_HEADER,
    HEADER_ROW,
    MAPPING_VERSION,
    SHEET_NAME,
    canonical_header,
    resolve_field_by_header,
)


FIELD_NAMES = tuple(mapping.field for mapping in FIELD_MAPPINGS)
IMPORT_METADATA_FIELDS = ("import_batch_id", "source_type", "source_batch_no", "mapping_version", "active_flag")
IMPORT_FIELD_NAMES = FIELD_NAMES + IMPORT_METADATA_FIELDS
REQUIRED_FIELDS = ("material_code", "material_name")
MATERIAL_CODE_INDEX = FIELD_NAMES.index("material_code")
SOURCE_TYPE_EXCEL = "EXCEL"
ACTIVE_FLAG_ACTIVE = 1

MAX_LENGTH_BY_FIELD = {field: 255 for field in FIELD_NAMES}
MAX_LENGTH_BY_FIELD["material_code"] = 64
MAX_LENGTH_BY_FIELD.update(
    {
        "import_batch_id": 64,
        "source_type": 32,
        "source_batch_no": 128,
        "mapping_version": 64,
    }
)


class HeaderMappingError(ValueError):
    """Excel 表头不满足导入要求。"""


class MaterialCodeDeduplicator:
    """按物料代码做单批次去重，raw 表跨批次仍保留历史版本。"""

    def __init__(self):
        self.first_row_by_code = {}

    def accept(self, material_code, excel_row):
        first_row = self.first_row_by_code.get(material_code)
        if first_row is not None:
            return first_row
        self.first_row_by_code[material_code] = excel_row
        return None


def normalize_cell(value):
    """统一把 None / 空白 / 浮点整数化等转换好。"""
    if value is None:
        return None
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    text = str(value).strip()
    return text or None


def trim_value(field, value):
    if value is None:
        return None
    max_len = MAX_LENGTH_BY_FIELD.get(field)
    if max_len and len(value) > max_len:
        return value[:max_len]
    return value


def build_header_field_index(header_values):
    """按表头生成 field -> 0-based Excel 下标映射。

    字段映射必须按表头匹配，不能按固定列号读取；这样 U9 导出列顺序调整时，
    例如“默认主供应商”从旧列移动到第 19 列，也不会写入错误字段。
    """
    field_index = {}
    duplicate_fields = []
    for index, header in enumerate(header_values):
        field = resolve_field_by_header(header)
        if not field:
            continue
        if field in field_index:
            duplicate_fields.append(f"{FIELD_TO_HEADER[field]}({field})")
            continue
        field_index[field] = index

    if duplicate_fields:
        raise HeaderMappingError("表头重复: " + ", ".join(sorted(duplicate_fields)))

    missing_required = [field for field in REQUIRED_FIELDS if field not in field_index]
    if missing_required:
        names = [f"{FIELD_TO_HEADER[field]}({field})" for field in missing_required]
        raise HeaderMappingError("缺少必填表头: " + ", ".join(names))

    return field_index


def missing_optional_fields(field_index):
    return tuple(field for field in FIELD_NAMES if field not in field_index and field not in REQUIRED_FIELDS)


def row_to_field_values(row_values, field_index):
    values = {}
    for field in FIELD_NAMES:
        index = field_index.get(field)
        raw_value = row_values[index] if index is not None and index < len(row_values) else None
        values[field] = trim_value(field, normalize_cell(raw_value))
    return values


def build_import_row(row_values, field_index, batch_id, source_batch_no=None):
    values_by_field = row_to_field_values(row_values, field_index)
    material_code = values_by_field.get("material_code")
    if not material_code:
        return None

    source_batch_no = source_batch_no or batch_id
    ordered_values = [values_by_field[field] for field in FIELD_NAMES]
    ordered_values.extend(
        [
            trim_value("import_batch_id", batch_id),
            SOURCE_TYPE_EXCEL,
            trim_value("source_batch_no", source_batch_no),
            MAPPING_VERSION,
            ACTIVE_FLAG_ACTIVE,
        ]
    )
    return tuple(ordered_values)


def material_code_from_import_row(import_row):
    return import_row[MATERIAL_CODE_INDEX]


def read_header_values(sheet):
    return next(sheet.iter_rows(min_row=HEADER_ROW, max_row=HEADER_ROW, values_only=True))


def describe_headers(field_index):
    mapped = len(field_index)
    missing_optional = missing_optional_fields(field_index)
    return {
        "mapped": mapped,
        "missing_optional": missing_optional,
        "mapping_version": MAPPING_VERSION,
        "sheet_name": SHEET_NAME,
        "header_row": HEADER_ROW,
        "data_start_row": DATA_START_ROW,
    }


def canonical_headers(header_values):
    return tuple(canonical_header(value) for value in header_values if normalize_cell(value))
