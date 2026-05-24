package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRow;
import com.sanhua.marketingcost.dto.ProductPropertyImportRequest;
import com.sanhua.marketingcost.dto.ProductPropertyRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.service.ProductPropertyAnnualSyncService;
import com.sanhua.marketingcost.service.ProductPropertyService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductPropertyServiceImpl implements ProductPropertyService {
  private final ProductPropertyMapper productPropertyMapper;
  private final ProductPropertyAnnualSyncService annualSyncService;

  public ProductPropertyServiceImpl(
      ProductPropertyMapper productPropertyMapper,
      ProductPropertyAnnualSyncService annualSyncService) {
    this.productPropertyMapper = productPropertyMapper;
    this.annualSyncService = annualSyncService;
  }

  @Override
  public List<ProductProperty> list(
      String level1Name,
      String parentCode,
      Integer propertyYear,
      String businessDivision,
      String productCode,
      String productName,
      String productAttr,
      String attrSourceType,
      String annualUsageSourceType) {
    var query = Wrappers.lambdaQuery(ProductProperty.class);
    if (StringUtils.hasText(level1Name)) {
      query.like(ProductProperty::getLevel1Name, level1Name.trim());
    }
    if (StringUtils.hasText(parentCode)) {
      query.like(ProductProperty::getParentCode, parentCode.trim());
    }
    if (propertyYear != null) {
      query.eq(ProductProperty::getPropertyYear, propertyYear);
    }
    if (StringUtils.hasText(businessDivision)) {
      String value = businessDivision.trim();
      query.and(
          q -> q.like(ProductProperty::getBusinessDivision, value)
              .or()
              .like(ProductProperty::getLevel1Name, value));
    }
    if (StringUtils.hasText(productCode)) {
      String value = productCode.trim();
      query.and(
          q -> q.like(ProductProperty::getProductCode, value)
              .or()
              .like(ProductProperty::getParentCode, value));
    }
    if (StringUtils.hasText(productName)) {
      String value = productName.trim();
      query.and(
          q -> q.like(ProductProperty::getProductName, value)
              .or()
              .like(ProductProperty::getParentName, value));
    }
    if (StringUtils.hasText(productAttr)) {
      query.like(ProductProperty::getProductAttr, productAttr.trim());
    }
    if (StringUtils.hasText(attrSourceType)) {
      query.eq(ProductProperty::getAttrSourceType, attrSourceType.trim());
    }
    if (StringUtils.hasText(annualUsageSourceType)) {
      query.eq(ProductProperty::getAnnualUsageSourceType, annualUsageSourceType.trim());
    }
    query.orderByDesc(ProductProperty::getPropertyYear)
        .orderByDesc(ProductProperty::getPeriod)
        .orderByDesc(ProductProperty::getId);
    return productPropertyMapper.selectList(query);
  }

  @Override
  public ProductProperty create(ProductPropertyRequest request) {
    if (request == null) {
      return null;
    }
    ProductPropertyAnnualSyncRequest syncRequest = new ProductPropertyAnnualSyncRequest();
    syncRequest.setAttrSourceType("MANUAL");
    syncRequest.setAnnualUsageSourceType("MANUAL");
    syncRequest.setRows(List.of(toSyncRow(request, null)));
    ProductPropertyAnnualSyncResult result = annualSyncService.sync(syncRequest);
    return result.getRecords().isEmpty() ? null : result.getRecords().get(0);
  }

  @Override
  public ProductProperty update(Long id, ProductPropertyRequest request) {
    if (id == null) {
      return null;
    }
    ProductPropertyAnnualSyncRequest syncRequest = new ProductPropertyAnnualSyncRequest();
    syncRequest.setAttrSourceType("MANUAL");
    syncRequest.setAnnualUsageSourceType("MANUAL");
    syncRequest.setRows(List.of(toSyncRow(request, id)));
    ProductPropertyAnnualSyncResult result = annualSyncService.sync(syncRequest);
    return result.getRecords().isEmpty() ? null : result.getRecords().get(0);
  }

  @Override
  public boolean delete(Long id) {
    return id != null && productPropertyMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ProductPropertyAnnualSyncResult importItems(ProductPropertyImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return new ProductPropertyAnnualSyncResult();
    }
    ProductPropertyAnnualSyncRequest syncRequest = new ProductPropertyAnnualSyncRequest();
    syncRequest.setPropertyYear(request.getPropertyYear());
    syncRequest.setBusinessUnitType(request.getBusinessUnitType());
    syncRequest.setAttrSourceType("TECH_IMPORT");
    syncRequest.setAnnualUsageSourceType("TECH_IMPORT");
    List<ProductPropertyAnnualSyncRow> rows = new ArrayList<>();
    int rowNo = 1;
    for (var row : request.getRows()) {
      if (row == null) {
        rowNo++;
        continue;
      }
      rows.add(toSyncRow(row, rowNo++));
    }
    syncRequest.setRows(rows);
    return annualSyncService.sync(syncRequest);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ProductPropertyAnnualSyncResult importExcel(
      InputStream input, Integer propertyYear, String businessUnitType) {
    ProductPropertyAnnualSyncResult parseResult = new ProductPropertyAnnualSyncResult();
    ProductPropertyImportRequest request = new ProductPropertyImportRequest();
    request.setPropertyYear(propertyYear);
    request.setBusinessUnitType(businessUnitType);
    List<ProductPropertyImportRequest.ProductPropertyRow> rows = new ArrayList<>();
    try (Workbook workbook = WorkbookFactory.create(input)) {
      Sheet sheet = workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter();
      HeaderMatch header = findHeader(sheet, formatter);
      if (header.index < 0) {
        parseResult.addError("未找到产品属性导入表头");
        return parseResult;
      }
      for (int i = header.index + 1; i <= sheet.getLastRowNum(); i++) {
        Row excelRow = sheet.getRow(i);
        if (excelRow == null || isBlankRow(excelRow, formatter)) {
          continue;
        }
        ProductPropertyImportRequest.ProductPropertyRow row = toImportRow(excelRow, formatter, header.fields);
        if (isEmptyImportRow(row)) {
          parseResult.incrementSkipped();
          continue;
        }
        rows.add(row);
      }
    } catch (Exception ex) {
      parseResult.addError("Excel 解析失败: " + ex.getMessage());
      return parseResult;
    }
    request.setRows(rows);
    if (rows.isEmpty()) {
      parseResult.addError("未解析到有效产品属性数据");
      return parseResult;
    }
    return importItems(request);
  }

  private HeaderMatch findHeader(Sheet sheet, DataFormatter formatter) {
    int maxScanRow = Math.min(sheet.getLastRowNum(), 20);
    HeaderMatch best = new HeaderMatch(-1, 0, Map.of());
    for (int i = 0; i <= maxScanRow; i++) {
      Row row = sheet.getRow(i);
      if (row == null) {
        continue;
      }
      Map<String, Integer> fields = new HashMap<>();
      if (row.getFirstCellNum() < 0) {
        continue;
      }
      for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
        String field = resolveImportField(cellText(row, c, formatter));
        if (field != null && !fields.containsKey(field)) {
          fields.put(field, c);
        }
      }
      if (fields.size() > best.count) {
        best = new HeaderMatch(i, fields.size(), fields);
      }
    }
    return best.count >= 3 ? best : new HeaderMatch(-1, 0, Map.of());
  }

  private ProductPropertyImportRequest.ProductPropertyRow toImportRow(
      Row row, DataFormatter formatter, Map<String, Integer> fields) {
    ProductPropertyImportRequest.ProductPropertyRow item =
        new ProductPropertyImportRequest.ProductPropertyRow();
    item.setLevel1Code(value(row, fields, "level1Code", formatter));
    item.setBusinessDivision(value(row, fields, "businessDivision", formatter));
    item.setLevel1Name(value(row, fields, "businessDivision", formatter));
    item.setProductCode(value(row, fields, "productCode", formatter));
    item.setParentCode(value(row, fields, "productCode", formatter));
    item.setProductName(value(row, fields, "productName", formatter));
    item.setParentName(value(row, fields, "productName", formatter));
    item.setProductModel(value(row, fields, "productModel", formatter));
    item.setParentModel(value(row, fields, "productModel", formatter));
    item.setProductSpec(value(row, fields, "productSpec", formatter));
    item.setParentSpec(value(row, fields, "productSpec", formatter));
    item.setProductAttr(value(row, fields, "productAttr", formatter));
    item.setRemark(value(row, fields, "remark", formatter));
    item.setPeriod(formatPeriod(value(row, fields, "period", formatter)));
    item.setPropertyYear(parseYear(value(row, fields, "propertyYear", formatter)));
    item.setAnnualUsage(parseDecimal(value(row, fields, "annualUsage", formatter)));
    return item;
  }

  private String resolveImportField(String header) {
    String normalized = normalizeHeader(header);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    Map<String, List<String>> aliases = new HashMap<>();
    aliases.put("level1Code", List.of("一级编码"));
    aliases.put("businessDivision", List.of("事业部", "一级编码名称", "生产事业部"));
    aliases.put("productCode", List.of("产品料号", "父件编码", "物料编码", "料号"));
    aliases.put("productName", List.of("产品名称", "父件名称", "物料名称", "品名"));
    aliases.put("productModel", List.of("产品型号", "父件型号", "型号"));
    aliases.put("productSpec", List.of("产品规格", "父件规格", "规格"));
    aliases.put("productAttr", List.of("产品属性"));
    aliases.put("annualUsage", List.of("预计年用量", "年用量"));
    aliases.put("remark", List.of("备注"));
    aliases.put("propertyYear", List.of("年度", "年份"));
    aliases.put("period", List.of("期间", "月份"));
    for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
      for (String alias : entry.getValue()) {
        String normalizedAlias = normalizeHeader(alias);
        if (normalized.equals(normalizedAlias) || normalized.contains(normalizedAlias)) {
          return entry.getKey();
        }
      }
    }
    return null;
  }

  private String value(
      Row row, Map<String, Integer> fields, String field, DataFormatter formatter) {
    Integer index = fields.get(field);
    if (index == null) {
      return null;
    }
    return trimToNull(cellText(row, index, formatter));
  }

  private String cellText(Row row, int index, DataFormatter formatter) {
    if (row == null || index < 0) {
      return "";
    }
    Cell cell = row.getCell(index);
    return cell == null ? "" : formatter.formatCellValue(cell);
  }

  private boolean isBlankRow(Row row, DataFormatter formatter) {
    if (row.getFirstCellNum() < 0) {
      return true;
    }
    for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
      if (StringUtils.hasText(cellText(row, i, formatter))) {
        return false;
      }
    }
    return true;
  }

  private boolean isEmptyImportRow(ProductPropertyImportRequest.ProductPropertyRow row) {
    return !StringUtils.hasText(row.getProductCode())
        && !StringUtils.hasText(row.getProductName())
        && !StringUtils.hasText(row.getProductAttr())
        && row.getAnnualUsage() == null;
  }

  private String normalizeHeader(String text) {
    if (!StringUtils.hasText(text)) {
      return "";
    }
    return text.replaceAll("[\\s\\n\\r\\t（）()，,：:；;_/\\\\-]", "").trim();
  }

  private String formatPeriod(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String text = value.trim();
    if (text.matches("\\d{4}-\\d{1,2}")) {
      String[] parts = text.split("-");
      return parts[0] + "-" + String.format("%02d", Integer.parseInt(parts[1]));
    }
    if (text.matches("\\d{4}/\\d{1,2}")) {
      String[] parts = text.split("/");
      return parts[0] + "-" + String.format("%02d", Integer.parseInt(parts[1]));
    }
    if (text.matches("\\d{4}年\\d{1,2}月?")) {
      String[] parts = text.replace("月", "").split("年");
      return parts[0] + "-" + String.format("%02d", Integer.parseInt(parts[1]));
    }
    if (text.matches("\\d{4}")) {
      return text + "-01";
    }
    return text;
  }

  private BigDecimal parseDecimal(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String text = value.trim().replace(",", "");
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private record HeaderMatch(int index, int count, Map<String, Integer> fields) {}

  private ProductPropertyAnnualSyncRow toSyncRow(ProductPropertyRequest request, Long id) {
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setId(id);
    if (request == null) {
      return row;
    }
    row.setLevel1Code(request.getLevel1Code());
    row.setLevel1Name(request.getLevel1Name());
    row.setParentCode(request.getParentCode());
    row.setParentName(request.getParentName());
    row.setParentSpec(request.getParentSpec());
    row.setParentModel(request.getParentModel());
    row.setPeriod(request.getPeriod());
    row.setProductAttr(request.getProductAttr());
    row.setPropertyYear(request.getPropertyYear());
    row.setBusinessDivision(request.getBusinessDivision());
    row.setProductCode(request.getProductCode());
    row.setProductName(request.getProductName());
    row.setProductModel(request.getProductModel());
    row.setProductSpec(request.getProductSpec());
    row.setAnnualUsage(request.getAnnualUsage());
    row.setRemark(request.getRemark());
    row.setAttrSourceType(request.getAttrSourceType());
    row.setAttrSourceBatchNo(request.getAttrSourceBatchNo());
    row.setAnnualUsageSourceType(request.getAnnualUsageSourceType());
    row.setAnnualUsageSourceBatchNo(request.getAnnualUsageSourceBatchNo());
    row.setAnnualUsageOaNo(request.getAnnualUsageOaNo());
    row.setAnnualUsageOaLineId(request.getAnnualUsageOaLineId());
    row.setEffectiveFrom(request.getEffectiveFrom());
    row.setEffectiveTo(request.getEffectiveTo());
    row.setMatchRiskFlag(request.getMatchRiskFlag());
    row.setMatchRiskReason(request.getMatchRiskReason());
    return row;
  }

  private ProductPropertyAnnualSyncRow toSyncRow(
      ProductPropertyImportRequest.ProductPropertyRow source, Integer rowNo) {
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setRowNo(rowNo);
    row.setLevel1Code(source.getLevel1Code());
    row.setLevel1Name(source.getLevel1Name());
    row.setParentCode(source.getParentCode());
    row.setParentName(source.getParentName());
    row.setParentSpec(source.getParentSpec());
    row.setParentModel(source.getParentModel());
    row.setPeriod(source.getPeriod());
    row.setProductAttr(source.getProductAttr());
    row.setPropertyYear(source.getPropertyYear());
    row.setBusinessDivision(source.getBusinessDivision());
    row.setProductCode(source.getProductCode());
    row.setProductName(source.getProductName());
    row.setProductModel(source.getProductModel());
    row.setProductSpec(source.getProductSpec());
    row.setAnnualUsage(source.getAnnualUsage());
    row.setRemark(source.getRemark());
    row.setAttrSourceType(source.getAttrSourceType());
    row.setAttrSourceBatchNo(source.getAttrSourceBatchNo());
    row.setAnnualUsageSourceType(source.getAnnualUsageSourceType());
    row.setAnnualUsageSourceBatchNo(source.getAnnualUsageSourceBatchNo());
    row.setAnnualUsageOaNo(source.getAnnualUsageOaNo());
    row.setAnnualUsageOaLineId(source.getAnnualUsageOaLineId());
    row.setEffectiveFrom(source.getEffectiveFrom());
    row.setEffectiveTo(source.getEffectiveTo());
    row.setMatchRiskFlag(source.getMatchRiskFlag());
    row.setMatchRiskReason(source.getMatchRiskReason());
    return row;
  }

  private void fillFromRow(ProductProperty entity, ProductPropertyImportRequest.ProductPropertyRow row) {
    entity.setLevel1Code(row.getLevel1Code());
    entity.setLevel1Name(row.getLevel1Name());
    entity.setParentCode(row.getParentCode());
    entity.setParentName(row.getParentName());
    entity.setParentSpec(row.getParentSpec());
    entity.setParentModel(row.getParentModel());
    entity.setPeriod(row.getPeriod());
    entity.setProductAttr(row.getProductAttr());
    entity.setPropertyYear(row.getPropertyYear());
    entity.setBusinessDivision(row.getBusinessDivision());
    entity.setProductCode(row.getProductCode());
    entity.setProductName(row.getProductName());
    entity.setProductModel(row.getProductModel());
    entity.setProductSpec(row.getProductSpec());
    entity.setAnnualUsage(row.getAnnualUsage());
    entity.setRemark(row.getRemark());
    entity.setAttrSourceType(row.getAttrSourceType());
    entity.setAttrSourceBatchNo(row.getAttrSourceBatchNo());
    entity.setAnnualUsageSourceType(row.getAnnualUsageSourceType());
    entity.setAnnualUsageSourceBatchNo(row.getAnnualUsageSourceBatchNo());
    entity.setAnnualUsageOaNo(row.getAnnualUsageOaNo());
    entity.setAnnualUsageOaLineId(row.getAnnualUsageOaLineId());
    entity.setEffectiveFrom(row.getEffectiveFrom());
    entity.setEffectiveTo(row.getEffectiveTo());
    entity.setMatchRiskFlag(row.getMatchRiskFlag());
    entity.setMatchRiskReason(row.getMatchRiskReason());
  }

  private void merge(ProductProperty target, ProductProperty source) {
    if (source.getLevel1Code() != null) {
      target.setLevel1Code(source.getLevel1Code());
    }
    if (source.getLevel1Name() != null) {
      target.setLevel1Name(source.getLevel1Name());
    }
    if (source.getParentCode() != null) {
      target.setParentCode(source.getParentCode());
    }
    if (source.getParentName() != null) {
      target.setParentName(source.getParentName());
    }
    if (source.getParentSpec() != null) {
      target.setParentSpec(source.getParentSpec());
    }
    if (source.getParentModel() != null) {
      target.setParentModel(source.getParentModel());
    }
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
    if (source.getProductAttr() != null) {
      target.setProductAttr(source.getProductAttr());
    }
    if (source.getPropertyYear() != null) {
      target.setPropertyYear(source.getPropertyYear());
    }
    if (source.getBusinessDivision() != null) {
      target.setBusinessDivision(source.getBusinessDivision());
    }
    if (source.getProductCode() != null) {
      target.setProductCode(source.getProductCode());
    }
    if (source.getProductName() != null) {
      target.setProductName(source.getProductName());
    }
    if (source.getProductModel() != null) {
      target.setProductModel(source.getProductModel());
    }
    if (source.getProductSpec() != null) {
      target.setProductSpec(source.getProductSpec());
    }
    if (source.getAnnualUsage() != null) {
      target.setAnnualUsage(source.getAnnualUsage());
      target.setAnnualUsageUpdatedAt(LocalDateTime.now());
    }
    if (source.getRemark() != null) {
      target.setRemark(source.getRemark());
    }
    if (source.getAttrSourceType() != null) {
      target.setAttrSourceType(source.getAttrSourceType());
    }
    if (source.getAttrSourceBatchNo() != null) {
      target.setAttrSourceBatchNo(source.getAttrSourceBatchNo());
    }
    if (source.getAnnualUsageSourceType() != null) {
      target.setAnnualUsageSourceType(source.getAnnualUsageSourceType());
    }
    if (source.getAnnualUsageSourceBatchNo() != null) {
      target.setAnnualUsageSourceBatchNo(source.getAnnualUsageSourceBatchNo());
    }
    if (source.getAnnualUsageOaNo() != null) {
      target.setAnnualUsageOaNo(source.getAnnualUsageOaNo());
    }
    if (source.getAnnualUsageOaLineId() != null) {
      target.setAnnualUsageOaLineId(source.getAnnualUsageOaLineId());
    }
    if (source.getEffectiveFrom() != null) {
      target.setEffectiveFrom(source.getEffectiveFrom());
    }
    if (source.getEffectiveTo() != null) {
      target.setEffectiveTo(source.getEffectiveTo());
    }
    if (source.getMatchRiskFlag() != null) {
      target.setMatchRiskFlag(source.getMatchRiskFlag());
    }
    if (source.getMatchRiskReason() != null) {
      target.setMatchRiskReason(source.getMatchRiskReason());
    }
  }

  private void merge(ProductProperty entity, ProductPropertyRequest request) {
    if (request == null) {
      return;
    }
    if (request.getLevel1Code() != null) {
      entity.setLevel1Code(request.getLevel1Code());
    }
    if (request.getLevel1Name() != null) {
      entity.setLevel1Name(request.getLevel1Name());
    }
    if (request.getParentCode() != null) {
      entity.setParentCode(request.getParentCode());
    }
    if (request.getParentName() != null) {
      entity.setParentName(request.getParentName());
    }
    if (request.getParentSpec() != null) {
      entity.setParentSpec(request.getParentSpec());
    }
    if (request.getParentModel() != null) {
      entity.setParentModel(request.getParentModel());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getProductAttr() != null) {
      entity.setProductAttr(request.getProductAttr());
    }
    if (request.getPropertyYear() != null) {
      entity.setPropertyYear(request.getPropertyYear());
    }
    if (request.getBusinessDivision() != null) {
      entity.setBusinessDivision(request.getBusinessDivision());
    }
    if (request.getProductCode() != null) {
      entity.setProductCode(request.getProductCode());
    }
    if (request.getProductName() != null) {
      entity.setProductName(request.getProductName());
    }
    if (request.getProductModel() != null) {
      entity.setProductModel(request.getProductModel());
    }
    if (request.getProductSpec() != null) {
      entity.setProductSpec(request.getProductSpec());
    }
    if (request.getAnnualUsage() != null) {
      entity.setAnnualUsage(request.getAnnualUsage());
      entity.setAnnualUsageUpdatedAt(LocalDateTime.now());
    }
    if (request.getRemark() != null) {
      entity.setRemark(request.getRemark());
    }
    if (request.getAttrSourceType() != null) {
      entity.setAttrSourceType(request.getAttrSourceType());
    }
    if (request.getAttrSourceBatchNo() != null) {
      entity.setAttrSourceBatchNo(request.getAttrSourceBatchNo());
    }
    if (request.getAnnualUsageSourceType() != null) {
      entity.setAnnualUsageSourceType(request.getAnnualUsageSourceType());
    }
    if (request.getAnnualUsageSourceBatchNo() != null) {
      entity.setAnnualUsageSourceBatchNo(request.getAnnualUsageSourceBatchNo());
    }
    if (request.getAnnualUsageOaNo() != null) {
      entity.setAnnualUsageOaNo(request.getAnnualUsageOaNo());
    }
    if (request.getAnnualUsageOaLineId() != null) {
      entity.setAnnualUsageOaLineId(request.getAnnualUsageOaLineId());
    }
    if (request.getEffectiveFrom() != null) {
      entity.setEffectiveFrom(request.getEffectiveFrom());
    }
    if (request.getEffectiveTo() != null) {
      entity.setEffectiveTo(request.getEffectiveTo());
    }
    if (request.getMatchRiskFlag() != null) {
      entity.setMatchRiskFlag(request.getMatchRiskFlag());
    }
    if (request.getMatchRiskReason() != null) {
      entity.setMatchRiskReason(request.getMatchRiskReason());
    }
  }

  private void fillDefaults(
      ProductProperty entity, String defaultAttrSourceType, String defaultAnnualUsageSourceType) {
    entity.setLevel1Code(trimToNull(entity.getLevel1Code()));
    entity.setLevel1Name(trimToNull(entity.getLevel1Name()));
    entity.setParentCode(trimToNull(entity.getParentCode()));
    entity.setParentName(trimToNull(entity.getParentName()));
    entity.setParentSpec(trimToNull(entity.getParentSpec()));
    entity.setParentModel(trimToNull(entity.getParentModel()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setProductAttr(trimToNull(entity.getProductAttr()));
    entity.setBusinessDivision(trimToNull(entity.getBusinessDivision()));
    entity.setProductCode(trimToNull(entity.getProductCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setProductModel(trimToNull(entity.getProductModel()));
    entity.setProductSpec(trimToNull(entity.getProductSpec()));
    entity.setRemark(trimToNull(entity.getRemark()));
    entity.setAttrSourceType(trimToNull(entity.getAttrSourceType()));
    entity.setAttrSourceBatchNo(trimToNull(entity.getAttrSourceBatchNo()));
    entity.setAnnualUsageSourceType(trimToNull(entity.getAnnualUsageSourceType()));
    entity.setAnnualUsageSourceBatchNo(trimToNull(entity.getAnnualUsageSourceBatchNo()));
    entity.setAnnualUsageOaNo(trimToNull(entity.getAnnualUsageOaNo()));
    entity.setAnnualUsageOaLineId(trimToNull(entity.getAnnualUsageOaLineId()));
    entity.setMatchRiskReason(trimToNull(entity.getMatchRiskReason()));
    syncAnnualFields(entity);
    if (!StringUtils.hasText(entity.getAttrSourceType()) && defaultAttrSourceType != null) {
      entity.setAttrSourceType(defaultAttrSourceType);
    }
    if (entity.getAnnualUsage() != null) {
      if (!StringUtils.hasText(entity.getAnnualUsageSourceType())
          && defaultAnnualUsageSourceType != null) {
        entity.setAnnualUsageSourceType(defaultAnnualUsageSourceType);
      }
      if (entity.getAnnualUsageUpdatedAt() == null) {
        entity.setAnnualUsageUpdatedAt(LocalDateTime.now());
      }
    }
    if (entity.getMatchRiskFlag() == null) {
      entity.setMatchRiskFlag(StringUtils.hasText(entity.getProductCode()) ? 0 : 1);
    }
  }

  private void syncAnnualFields(ProductProperty entity) {
    if (!StringUtils.hasText(entity.getBusinessDivision())) {
      entity.setBusinessDivision(entity.getLevel1Name());
    }
    if (!StringUtils.hasText(entity.getLevel1Name())) {
      entity.setLevel1Name(entity.getBusinessDivision());
    }
    if (!StringUtils.hasText(entity.getLevel1Code())) {
      entity.setLevel1Code(firstText(entity.getBusinessDivision(), entity.getLevel1Name()));
    }
    if (!StringUtils.hasText(entity.getProductCode())) {
      entity.setProductCode(entity.getParentCode());
    }
    if (!StringUtils.hasText(entity.getParentCode())) {
      entity.setParentCode(entity.getProductCode());
    }
    if (!StringUtils.hasText(entity.getProductName())) {
      entity.setProductName(entity.getParentName());
    }
    if (!StringUtils.hasText(entity.getParentName())) {
      entity.setParentName(entity.getProductName());
    }
    if (!StringUtils.hasText(entity.getProductModel())) {
      entity.setProductModel(entity.getParentModel());
    }
    if (!StringUtils.hasText(entity.getParentModel())) {
      entity.setParentModel(entity.getProductModel());
    }
    if (!StringUtils.hasText(entity.getProductSpec())) {
      entity.setProductSpec(entity.getParentSpec());
    }
    if (!StringUtils.hasText(entity.getParentSpec())) {
      entity.setParentSpec(entity.getProductSpec());
    }
    if (entity.getPropertyYear() == null) {
      entity.setPropertyYear(parseYear(entity.getPeriod()));
    }
    if (!StringUtils.hasText(entity.getPeriod()) && entity.getPropertyYear() != null) {
      entity.setPeriod(entity.getPropertyYear() + "-01");
    }
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    if (StringUtils.hasText(second)) {
      return second.trim();
    }
    return null;
  }

  private Integer parseYear(String period) {
    if (!StringUtils.hasText(period) || period.trim().length() < 4) {
      return null;
    }
    try {
      return Integer.valueOf(period.trim().substring(0, 4));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(ProductProperty entity) {
    return StringUtils.hasText(entity.getLevel1Code())
        && StringUtils.hasText(entity.getLevel1Name())
        && StringUtils.hasText(entity.getParentCode())
        && StringUtils.hasText(entity.getPeriod())
        && StringUtils.hasText(entity.getProductAttr());
  }

  private ProductProperty findExisting(ProductProperty entity) {
    if (entity.getPropertyYear() != null && StringUtils.hasText(entity.getProductCode())) {
      return productPropertyMapper.selectOne(
          Wrappers.lambdaQuery(ProductProperty.class)
              .eq(ProductProperty::getPropertyYear, entity.getPropertyYear())
              .eq(ProductProperty::getProductCode, entity.getProductCode())
              .last("LIMIT 1"));
    }
    var query = Wrappers.lambdaQuery(ProductProperty.class)
        .eq(ProductProperty::getLevel1Code, entity.getLevel1Code())
        .eq(ProductProperty::getParentCode, entity.getParentCode());
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(ProductProperty::getPeriod, entity.getPeriod());
    } else {
      query.isNull(ProductProperty::getPeriod);
    }
    return productPropertyMapper.selectOne(query.last("LIMIT 1"));
  }
}
