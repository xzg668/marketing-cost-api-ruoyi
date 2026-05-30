package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9BomByproductImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import com.sanhua.marketingcost.service.U9BomByproductMasterService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class U9BomByproductMasterServiceImpl implements U9BomByproductMasterService {
  private static final int ERROR_PREVIEW_LIMIT = 500;
  private static final int HEADER_SCAN_LIMIT = 5;
  private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
      DateTimeFormatter.ofPattern("yyyy-MM-dd"),
      DateTimeFormatter.ofPattern("yyyy/M/d"),
      DateTimeFormatter.ofPattern("yyyy.M.d"));
  private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
      DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
      DateTimeFormatter.ofPattern("yyyy.M.d HH:mm:ss"));

  private final U9BomByproductMasterMapper mapper;

  public U9BomByproductMasterServiceImpl(U9BomByproductMasterMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public U9BomByproductImportResponse importExcel(
      java.io.InputStream input, String sourceFileName, String importedBy) {
    if (input == null) {
      throw new IllegalArgumentException("Excel 文件流为空");
    }
    U9BomByproductImportResponse response = new U9BomByproductImportResponse();
    response.setDatasetCode(U9BomByproductFieldContract.DATASET_CODE);
    response.setSourceType(U9BomByproductFieldContract.SOURCE_TYPE_EXCEL);
    response.setStatus("PARSING");

    RowListener listener = new RowListener(sourceFileName, importedBy, response);
    EasyExcel.read(input, listener)
        .sheet(U9BomByproductFieldContract.SHEET_NAME)
        .headRowNumber(0)
        .doRead();
    response.setStatus(response.getFailCount() > 0 ? "PARTIAL_SUCCESS" : "PARSED");
    response.setMessage("导入完成: " + sourceFileName);
    return response;
  }

  @Override
  public Page<U9BomByproductMaster> page(
      String parentMaterialNo,
      String parentMaterialName,
      String byproductMaterialNo,
      String byproductMaterialName,
      String bomPurpose,
      String status,
      LocalDate asOfDate,
      int page,
      int pageSize) {
    QueryWrapper<U9BomByproductMaster> query = new QueryWrapper<>();
    like(query, "parent_material_no", parentMaterialNo);
    like(query, "parent_material_name", parentMaterialName);
    like(query, "byproduct_material_no", byproductMaterialNo);
    like(query, "byproduct_material_name", byproductMaterialName);
    like(query, "bom_purpose", bomPurpose);
    like(query, "status", status);
    if (asOfDate != null) {
      query.le("effective_from", asOfDate).ge("effective_to", asOfDate);
    }
    query.orderByDesc("effective_from")
        .orderByAsc("parent_material_no")
        .orderByAsc("byproduct_material_no");
    return mapper.selectPage(new Page<>(safePage(page), safeSize(pageSize)), query);
  }

  @Override
  public List<U9MaterialTemplateMappingItem> templateMapping() {
    return U9BomByproductFieldContract.fieldMappings().stream()
        .map(m -> new U9MaterialTemplateMappingItem(m.field(), m.header(), m.excelColumn()))
        .toList();
  }

  private static void like(QueryWrapper<U9BomByproductMaster> query, String column, String value) {
    if (StringUtils.hasText(value)) {
      query.like(column, value.trim());
    }
  }

  private static int safePage(int page) {
    return page < 1 ? 1 : page;
  }

  private static int safeSize(int pageSize) {
    if (pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 200);
  }

  private static String normalizeCell(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).toString();
    }
    String text = value.toString().trim();
    if (text.endsWith(".0")) {
      text = text.substring(0, text.length() - 2);
    }
    return text.isEmpty() ? null : text;
  }

  private static LocalDate parseDate(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDate localDate) {
      return localDate;
    }
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime.toLocalDate();
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).toLocalDate();
    }
    String text = value.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    if (text.length() >= 10) {
      String firstTen = text.substring(0, 10);
      for (DateTimeFormatter formatter : DATE_FORMATTERS) {
        try {
          return LocalDate.parse(firstTen, formatter);
        } catch (DateTimeParseException ignored) {
        }
      }
    }
    for (DateTimeFormatter formatter : DATE_FORMATTERS) {
      try {
        return LocalDate.parse(text, formatter);
      } catch (DateTimeParseException ignored) {
      }
    }
    throw new IllegalArgumentException("日期格式无法识别: " + text);
  }

  private static LocalDateTime parseDateTime(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof LocalDate localDate) {
      return localDate.atStartOfDay();
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
    String text = value.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
      try {
        return LocalDateTime.parse(text, formatter);
      } catch (DateTimeParseException ignored) {
      }
    }
    LocalDate localDate = parseDate(text);
    return localDate == null ? null : localDate.atStartOfDay();
  }

  private static BigDecimal parseDecimal(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return new BigDecimal(value.trim().replace(",", ""));
  }

  private static String trimMax(String value, int maxLength) {
    return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  private static String rowKey(U9BomByproductMaster row) {
    return String.join("|",
        row.getBomPurpose(),
        row.getParentMaterialNo(),
        row.getByproductMaterialNo(),
        row.getEffectiveFrom().toString(),
        row.getEffectiveTo().toString());
  }

  private final class RowListener extends AnalysisEventListener<Map<Integer, Object>> {
    private final String sourceFileName;
    private final String importedBy;
    private final U9BomByproductImportResponse response;
    private final Map<Integer, String> columnToField = new HashMap<>();
    private final Set<String> seenKeys = new HashSet<>();
    private boolean headerParsed;

    private RowListener(
        String sourceFileName, String importedBy, U9BomByproductImportResponse response) {
      this.sourceFileName = sourceFileName;
      this.importedBy = importedBy;
      this.response = response;
    }

    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
      int excelRow = context.readRowHolder().getRowIndex() + 1;
      if (!headerParsed) {
        if (tryParseHeader(data)) {
          return;
        }
        if (excelRow >= HEADER_SCAN_LIMIT) {
          throw new IllegalArgumentException(missingHeaderMessage());
        }
        return;
      }
      if (isBlankRow(data)) {
        return;
      }
      response.setTotalCount(response.getTotalCount() + 1);
      U9BomByproductMaster row = new U9BomByproductMaster();
      try {
        for (Map.Entry<Integer, Object> cell : data.entrySet()) {
          String field = columnToField.get(cell.getKey());
          if (field != null) {
            assign(row, field, cell.getValue());
          }
        }
        validate(row);
        String key = rowKey(row);
        if (!seenKeys.add(key)) {
          addError(excelRow, row, "文件内自然键重复，已按首次出现行导入");
          return;
        }
        row.setSourceType(U9BomByproductFieldContract.SOURCE_TYPE_EXCEL);
        row.setSourceFileName(trimMax(sourceFileName, 255));
        row.setImportedBy(trimMax(importedBy, 128));
        row.setImportedAt(LocalDateTime.now());
        mapper.upsert(row);
        response.setSuccessCount(response.getSuccessCount() + 1);
      } catch (RuntimeException e) {
        addError(excelRow, row, e.getMessage());
      }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {}

    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
      Integer excelRow = context == null || context.readRowHolder() == null
          ? null
          : context.readRowHolder().getRowIndex() + 1;
      response.setFailCount(response.getFailCount() + 1);
      if (response.getErrors().size() < ERROR_PREVIEW_LIMIT) {
        response.getErrors().add(new U9BomByproductImportResponse.RowError(
            excelRow, null, null, "Excel 解析异常: " + exception.getMessage()));
      }
      throw exception;
    }

    private boolean tryParseHeader(Map<Integer, Object> headMap) {
      Map<Integer, String> candidate = new HashMap<>();
      Set<String> mappedFields = new HashSet<>();
      Map<String, Integer> canonicalCounts = new HashMap<>();
      List<String> duplicates = new ArrayList<>();
      for (Map.Entry<Integer, Object> entry : headMap.entrySet()) {
        String canonical = U9BomByproductFieldContract.canonicalHeader(entry.getValue());
        if (!StringUtils.hasText(canonical)) {
          continue;
        }
        int occurrence = canonicalCounts.merge(canonical, 1, Integer::sum);
        String field = U9BomByproductFieldContract.headerToField().get(canonical);
        if (field == null) {
          field = U9BomByproductFieldContract.headerToField().get(canonical + "#" + occurrence);
        }
        if (field == null) {
          continue;
        }
        if (!mappedFields.add(field)) {
          duplicates.add(field);
          continue;
        }
        candidate.put(entry.getKey(), field);
      }
      if (!duplicates.isEmpty()) {
        throw new IllegalArgumentException("表头重复: " + duplicates);
      }
      if (!mappedFields.contains("parent_material_no")
          || !mappedFields.contains("bom_purpose")
          || !mappedFields.contains("byproduct_material_no")
          || !mappedFields.contains("effective_from")
          || !mappedFields.contains("effective_to")) {
        return false;
      }
      columnToField.clear();
      columnToField.putAll(candidate);
      headerParsed = true;
      return true;
    }

    private String missingHeaderMessage() {
      return "缺少必填表头: BOM生产目的, 母件料品_料号, 等级品产出比例.料品.料号, 生效日期, 失效日期";
    }

    private boolean isBlankRow(Map<Integer, Object> data) {
      return data == null || data.values().stream().allMatch(v -> !StringUtils.hasText(normalizeCell(v)));
    }

    private void validate(U9BomByproductMaster row) {
      if (!StringUtils.hasText(row.getBomPurpose())) {
        throw new IllegalArgumentException("BOM生产目的为空");
      }
      if (!StringUtils.hasText(row.getParentMaterialNo())) {
        throw new IllegalArgumentException("母件料号为空");
      }
      if (!StringUtils.hasText(row.getByproductMaterialNo())) {
        throw new IllegalArgumentException("副产品料号为空");
      }
      if (row.getEffectiveFrom() == null) {
        throw new IllegalArgumentException("生效日期为空或无法识别");
      }
      if (row.getEffectiveTo() == null) {
        throw new IllegalArgumentException("失效日期为空或无法识别");
      }
    }

    private void addError(Integer excelRow, U9BomByproductMaster row, String reason) {
      response.setFailCount(response.getFailCount() + 1);
      if (response.getErrors().size() < ERROR_PREVIEW_LIMIT) {
        response.getErrors().add(new U9BomByproductImportResponse.RowError(
            excelRow,
            row == null ? null : row.getParentMaterialNo(),
            row == null ? null : row.getByproductMaterialNo(),
            reason));
      }
    }

    private void assign(U9BomByproductMaster row, String field, Object rawValue) {
      String value = trimMax(normalizeCell(rawValue), 255);
      switch (field) {
        case "parent_material_no" -> row.setParentMaterialNo(trimMax(value, 64));
        case "parent_material_name" -> row.setParentMaterialName(value);
        case "parent_material_spec" -> row.setParentMaterialSpec(value);
        case "bom_purpose" -> row.setBomPurpose(trimMax(value, 64));
        case "version_no" -> row.setVersionNo(trimMax(value, 64));
        case "output_type" -> row.setOutputType(trimMax(value, 64));
        case "byproduct_material_no" -> row.setByproductMaterialNo(trimMax(value, 64));
        case "byproduct_material_name" -> row.setByproductMaterialName(value);
        case "operation_no" -> row.setOperationNo(trimMax(value, 64));
        case "output_qty" -> row.setOutputQty(parseDecimal(value));
        case "unit" -> row.setUnit(trimMax(value, 64));
        case "status" -> row.setStatus(trimMax(value, 64));
        case "production_dept_code" -> row.setProductionDeptCode(trimMax(value, 64));
        case "production_dept_name" -> row.setProductionDeptName(value);
        case "effective_from" -> row.setEffectiveFrom(parseDate(rawValue));
        case "effective_to" -> row.setEffectiveTo(parseDate(rawValue));
        case "u9_created_by" -> row.setU9CreatedBy(trimMax(value, 128));
        case "u9_created_time" -> row.setU9CreatedTime(parseDateTime(rawValue));
        default -> {
        }
      }
    }
  }
}
