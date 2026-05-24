package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioWorkbookParseResult;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.SupplierSupplyRatioSourceType;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioWorkbookParser;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioImportServiceImpl implements SupplierSupplyRatioImportService {

  private static final String DEFAULT_BUSINESS_UNIT = "COMMERCIAL";

  private final SupplierSupplyRatioMapper mapper;
  private final SupplierSupplyRatioWorkbookParser parser;

  public SupplierSupplyRatioImportServiceImpl(
      SupplierSupplyRatioMapper mapper,
      SupplierSupplyRatioWorkbookParser parser) {
    this.mapper = mapper;
    this.parser = parser;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SupplierSupplyRatioImportResponse importExcel(
      InputStream input,
      String sourceFileName,
      String businessUnitType,
      String operator) {
    SupplierSupplyRatioWorkbookParseResult parseResult = parser.parse(input, sourceFileName);
    SupplierSupplyRatioImportResponse response = importRows(
        parseResult.getRows(), sourceFileName, businessUnitType, operator);
    appendParseErrors(response, parseResult.getErrors());
    response.setTotalRows(parseResult.getRows().size() + parseResult.getErrors().size());
    response.setErrorRows(response.getErrors().size());
    response.setSkippedRows(response.getSkippedRows() + parseResult.getErrors().size());
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SupplierSupplyRatioImportResponse importRows(
      List<SupplierSupplyRatioExcelRow> rows,
      String sourceFileName,
      String businessUnitType,
      String operator) {
    return upsertFromRows(
        convertExcelRows(rows),
        SupplierSupplyRatioSourceType.EXCEL,
        null,
        sourceFileName,
        businessUnitType,
        operator);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SupplierSupplyRatioImportResponse upsertFromRows(
      List<SupplierSupplyRatioImportRow> rows,
      SupplierSupplyRatioSourceType sourceType,
      String batchNo) {
    return upsertFromRows(rows, sourceType, batchNo, null, null, null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SupplierSupplyRatioImportResponse upsertFromRows(
      List<SupplierSupplyRatioImportRow> rows,
      SupplierSupplyRatioSourceType sourceType,
      String batchNo,
      String sourceFileName,
      String businessUnitType,
      String operator) {
    SupplierSupplyRatioImportResponse response = new SupplierSupplyRatioImportResponse();
    SupplierSupplyRatioSourceType actualSourceType = sourceType == null
        ? SupplierSupplyRatioSourceType.MANUAL
        : sourceType;
    response.setBatchNo(resolveBatchNo(batchNo, actualSourceType));
    if (rows == null || rows.isEmpty()) {
      return response;
    }
    String bu = normalizeBusinessUnit(businessUnitType);
    String username = currentOperator(operator);
    LocalDateTime now = LocalDateTime.now();
    response.setTotalRows(rows.size());

    for (SupplierSupplyRatioImportRow row : rows) {
      String error = validate(row);
      if (error != null) {
        response.getErrors().add(errorMessage(row, null, error));
        response.setSkippedRows(response.getSkippedRows() + 1);
        continue;
      }
      SupplierSupplyRatio existing = findExisting(row, bu);
      SupplierSupplyRatio entity = existing == null ? new SupplierSupplyRatio() : existing;
      applyFields(entity, row, bu, sourceFileName, actualSourceType, response.getBatchNo(), username, now);

      // Excel 与未来 SRM 同步共用这里的业务键 upsert；SRM 只替换数据入口，不改变主供取价规则。
      if (existing == null) {
        mapper.insert(entity);
        response.setInsertedRows(response.getInsertedRows() + 1);
      } else {
        mapper.updateById(entity);
        response.setUpdatedRows(response.getUpdatedRows() + 1);
      }
    }
    response.setErrorRows(response.getErrors().size());
    return response;
  }

  private SupplierSupplyRatio findExisting(SupplierSupplyRatioImportRow row, String businessUnitType) {
    return mapper.selectOne(
        new QueryWrapper<SupplierSupplyRatio>()
            .eq("business_unit_type", businessUnitType)
            .eq("material_code", normalized(row.getMaterialCode()))
            .eq("material_name", normalized(row.getMaterialName()))
            .eq("supplier_name", normalized(row.getSupplierName()))
            .eq("spec_model", normalized(row.getSpecModel()))
            .eq("deleted", 0)
            .last("LIMIT 1"));
  }

  private void applyFields(
      SupplierSupplyRatio entity,
      SupplierSupplyRatioImportRow row,
      String businessUnitType,
      String sourceFileName,
      SupplierSupplyRatioSourceType sourceType,
      String batchNo,
      String operator,
      LocalDateTime now) {
    entity.setBusinessUnitType(businessUnitType);
    entity.setMaterialCode(normalized(row.getMaterialCode()));
    entity.setMaterialName(normalized(row.getMaterialName()));
    entity.setSpecModel(normalized(row.getSpecModel()));
    entity.setUnit(trimToNull(row.getUnit()));
    entity.setMaterialShape(trimToNull(row.getMaterialShape()));
    entity.setSupplierName(normalized(row.getSupplierName()));
    entity.setSupplierCode(trimToNull(row.getSupplierCode()));
    entity.setSupplyRatio(row.getSupplyRatio());
    entity.setSourceType(sourceType.getCode());
    entity.setSourceBatchNo(batchNo);
    entity.setImportFileName(trimToNull(sourceFileName));
    entity.setImportedBy(operator);
    entity.setImportedAt(now);
    entity.setUpdatedBy(operator);
    entity.setUpdatedAt(now);
    entity.setDeleted(0);
    if (entity.getId() == null) {
      entity.setCreatedBy(operator);
      entity.setCreatedAt(now);
    }
  }

  private String validate(SupplierSupplyRatioImportRow row) {
    if (row == null) {
      return "行为空";
    }
    if (!StringUtils.hasText(normalized(row.getMaterialCode()))) {
      return "物料代码不能为空";
    }
    if (!StringUtils.hasText(normalized(row.getSupplierName()))) {
      return "供应商不能为空";
    }
    if (row.getSupplyRatio() == null) {
      return "供货比例不能为空或格式不正确";
    }
    if (row.getSupplyRatio().compareTo(BigDecimal.ZERO) < 0) {
      return "供货比例不能小于 0";
    }
    return null;
  }

  private List<SupplierSupplyRatioImportRow> convertExcelRows(List<SupplierSupplyRatioExcelRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream().map(this::convertExcelRow).toList();
  }

  private SupplierSupplyRatioImportRow convertExcelRow(SupplierSupplyRatioExcelRow row) {
    if (row == null) {
      return null;
    }
    SupplierSupplyRatioImportRow converted = new SupplierSupplyRatioImportRow();
    converted.setRowNo(row.getRowNo());
    converted.setMaterialCode(row.getMaterialCode());
    converted.setMaterialName(row.getMaterialName());
    converted.setSpecModel(row.getSpecModel());
    converted.setUnit(row.getUnit());
    converted.setMaterialShape(row.getMaterialShape());
    converted.setSupplierName(row.getSupplierName());
    converted.setSupplyRatio(row.getSupplyRatio());
    converted.setDedupeKey(row.getDedupeKey());
    return converted;
  }

  private void appendParseErrors(
      SupplierSupplyRatioImportResponse response,
      List<SupplierSupplyRatioWorkbookParseResult.ParseError> parseErrors) {
    if (parseErrors == null || parseErrors.isEmpty()) {
      return;
    }
    for (SupplierSupplyRatioWorkbookParseResult.ParseError error : parseErrors) {
      response.getErrors().add(errorMessage(error));
    }
  }

  private String errorMessage(SupplierSupplyRatioWorkbookParseResult.ParseError error) {
    StringBuilder message = new StringBuilder();
    if (error.getRowNo() != null) {
      message.append("第").append(error.getRowNo()).append("行");
    }
    if (StringUtils.hasText(error.getColumnName())) {
      message.append("[").append(error.getColumnName()).append("]");
    }
    message.append(error.getMessage());
    return message.toString();
  }

  private String errorMessage(SupplierSupplyRatioImportRow row, String columnName, String message) {
    StringBuilder builder = new StringBuilder();
    if (row != null && row.getRowNo() != null) {
      builder.append("第").append(row.getRowNo()).append("行");
    }
    if (StringUtils.hasText(columnName)) {
      builder.append("[").append(columnName).append("]");
    }
    builder.append(message);
    return builder.toString();
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : DEFAULT_BUSINESS_UNIT;
  }

  private String resolveBatchNo(String batchNo, SupplierSupplyRatioSourceType sourceType) {
    if (StringUtils.hasText(batchNo)) {
      return batchNo.trim();
    }
    return "SSR-" + sourceType.getCode() + "-" + UUID.randomUUID();
  }

  private String currentOperator(String operator) {
    return StringUtils.hasText(operator) ? operator.trim() : "system";
  }

  private String normalized(String value) {
    return SupplierSupplyRatioNormalizeUtils.normalizeToNull(value);
  }

  private String trimToNull(String value) {
    String trimmed = value == null ? null : value.trim();
    return StringUtils.hasText(trimmed) ? trimmed : null;
  }
}
