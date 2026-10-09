package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioWorkbookParseResult;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioWorkbookParser;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioImportServiceImpl implements SupplierSupplyRatioImportService {
  private final SupplierSupplyRatioMapper mapper;
  private final SupplierSupplyRatioWorkbookParser parser;
  private final org.springframework.context.ApplicationEventPublisher events;

  public SupplierSupplyRatioImportServiceImpl(SupplierSupplyRatioMapper mapper,
      SupplierSupplyRatioWorkbookParser parser, org.springframework.context.ApplicationEventPublisher events) {
    this.mapper = mapper;
    this.parser = parser;
    this.events = events;
  }

  @Override
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
  public SupplierSupplyRatioImportResponse importExcel(InputStream input, String sourceFileName,
      String businessUnitType, String operator, String sheetName) {
    SupplierSupplyRatioWorkbookParseResult parsed =
        parser.parse(input, sourceFileName, sheetName);
    if (parsed.hasErrors()) {
      throw new IllegalArgumentException(parsed.getErrors().stream()
          .limit(10).map(this::errorMessage).reduce((a, b) -> a + "；" + b).orElse("导入校验失败"));
    }
    return importRows(parsed.getRows(), sourceFileName, businessUnitType, operator);
  }

  @Override
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
  public SupplierSupplyRatioImportResponse importRows(List<SupplierSupplyRatioExcelRow> rows,
      String sourceFileName, String businessUnitType, String operator) {
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("没有可导入的有效数据，原数据未变更");
    }
    String bu = required(businessUnitType, "业务单元");
    String username = StringUtils.hasText(operator) ? operator.trim() : "system";
    SupplierSupplyRatioImportResponse response = new SupplierSupplyRatioImportResponse();
    response.setBatchNo("SSR-EXCEL-" + UUID.randomUUID());
    response.setTotalRows(rows.size());

    // 先校验整份文件；同一供应商的冲突值不能靠文件顺序覆盖。
    Map<String, Map<String, SupplierSupplyRatioExcelRow>> groups = new TreeMap<>();
    for (SupplierSupplyRatioExcelRow row : rows) {
      validate(row);
      String material = normalized(row.getMaterialCode());
      String supplier = normalized(row.getSupplierCode());
      Map<String, SupplierSupplyRatioExcelRow> suppliers =
          groups.computeIfAbsent(material, ignored -> new LinkedHashMap<>());
      SupplierSupplyRatioExcelRow previous = suppliers.putIfAbsent(supplier, row);
      if (previous != null) {
        if (!sameValues(previous, row)) {
          throw new IllegalArgumentException("同一物料和供应商存在冲突记录：" + material + "/" + supplier
              + "，Excel第" + previous.getRowNo() + "、" + row.getRowNo() + "行");
        }
        response.setDuplicateRows(response.getDuplicateRows() + 1);
      }
    }

    // 按料号固定顺序锁定，避免不同批次同时导入时混合供应商组。
    groups.keySet().forEach(material -> mapper.lockMaterial(bu, material));
    LocalDateTime now = LocalDateTime.now();
    for (var group : groups.entrySet()) {
      response.setDeactivatedRows(response.getDeactivatedRows()
          + mapper.deactivateMaterial(bu, group.getKey(), username, now));
      for (SupplierSupplyRatioExcelRow row : group.getValue().values()) {
        mapper.insert(newRecord(row, bu, sourceFileName, response.getBatchNo(), username, now));
        response.setInsertedRows(response.getInsertedRows() + 1);
        if (row.getSupplyRatio() == null) {
          response.setUnfilledRatioRows(response.getUnfilledRatioRows() + 1);
        }
      }
    }
    events.publishEvent(new com.sanhua.marketingcost.service.pricing.SupplierRatiosImported(bu, java.util.Set.copyOf(groups.keySet())));
    return response;
  }

  private SupplierSupplyRatio newRecord(SupplierSupplyRatioExcelRow row, String bu,
      String file, String batch, String operator, LocalDateTime now) {
    SupplierSupplyRatio entity = new SupplierSupplyRatio();
    entity.setBusinessUnitType(bu);
    entity.setMaterialCode(normalized(row.getMaterialCode()));
    entity.setMaterialName(row.getMaterialName().trim());
    entity.setSpecModel(trim(row.getSpecModel()));
    entity.setUnit(trimToNull(row.getUnit()));
    entity.setMaterialShape(trimToNull(row.getMaterialShape()));
    entity.setSupplierName(row.getSupplierName().trim());
    entity.setSupplierCode(normalized(row.getSupplierCode()));
    entity.setSupplyRatio(row.getSupplyRatio());
    entity.setSourceType("EXCEL");
    entity.setSourceBatchNo(batch);
    entity.setIsActive(1);
    entity.setImportFileName(file);
    entity.setImportedBy(operator);
    entity.setImportedAt(now);
    entity.setCreatedBy(operator);
    entity.setCreatedAt(now);
    entity.setUpdatedBy(operator);
    entity.setUpdatedAt(now);
    entity.setDeleted(0);
    return entity;
  }

  private void validate(SupplierSupplyRatioExcelRow row) {
    if (row == null) throw new IllegalArgumentException("导入行不能为空");
    required(row.getMaterialCode(), "物料代码");
    required(row.getMaterialName(), "物料名称");
    required(row.getSupplierCode(), "供应商代码");
    required(row.getSupplierName(), "供应商名称");
    BigDecimal ratio = row.getSupplyRatio();
    if (ratio != null && (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("第" + row.getRowNo() + "行比例必须是0到100%之间的数字");
    }
  }

  private boolean sameValues(SupplierSupplyRatioExcelRow a, SupplierSupplyRatioExcelRow b) {
    boolean sameRatio = a.getSupplyRatio() == null || b.getSupplyRatio() == null
        ? a.getSupplyRatio() == b.getSupplyRatio()
        : a.getSupplyRatio().compareTo(b.getSupplyRatio()) == 0;
    return sameRatio
        && Objects.equals(trim(a.getMaterialName()), trim(b.getMaterialName()))
        && Objects.equals(trim(a.getSupplierName()), trim(b.getSupplierName()))
        && Objects.equals(trim(a.getSpecModel()), trim(b.getSpecModel()))
        && Objects.equals(trim(a.getUnit()), trim(b.getUnit()))
        && Objects.equals(trim(a.getMaterialShape()), trim(b.getMaterialShape()));
  }

  private String errorMessage(SupplierSupplyRatioWorkbookParseResult.ParseError row) {
    return (row.getRowNo() == null ? "" : "第" + row.getRowNo() + "行：") + row.getMessage();
  }

  private String required(String value, String name) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(name + "不能为空");
    return value.trim();
  }

  private String normalized(String value) {
    return SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(value);
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
