package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.ProductPropertyImportResult;
import com.sanhua.marketingcost.dto.ProductPropertyRuleSaveRequest;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.ProductPropertyRule;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyRuleMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ProductPropertyService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductPropertyServiceImpl implements ProductPropertyService {
  private static final String TARGET_SHEET = "报价系统展示-产品属性";
  private static final String DEFAULT_BUSINESS_UNIT = "COMMERCIAL";
  private static final String MODE_INCREMENTAL = "INCREMENTAL";
  private static final String MODE_FULL = "FULL";
  private static final Set<String> EXPECTED_ATTRIBUTES =
      Set.of("非标品", "标准品", "定制品", "OEM");
  private static final int BATCH_SIZE = 500;
  private static final int MESSAGE_LIMIT = 200;

  private final ProductPropertyMapper productPropertyMapper;
  private final ProductPropertyRuleMapper ruleMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;

  public ProductPropertyServiceImpl(
      ProductPropertyMapper productPropertyMapper,
      ProductPropertyRuleMapper ruleMapper,
      MaterialMasterRawMapper materialMasterRawMapper) {
    this.productPropertyMapper = productPropertyMapper;
    this.ruleMapper = ruleMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public Page<ProductProperty> page(
      Integer propertyYear,
      String businessDivision,
      String productCode,
      String productName,
      String productAttr,
      String businessUnitType,
      int page,
      int pageSize) {
    String bu = resolveBusinessUnit(businessUnitType);
    var query = Wrappers.lambdaQuery(ProductProperty.class)
        .eq(ProductProperty::getBusinessUnitType, bu)
        .eq(propertyYear != null, ProductProperty::getPropertyYear, propertyYear)
        .like(StringUtils.hasText(businessDivision), ProductProperty::getBusinessDivision,
            trim(businessDivision))
        .like(StringUtils.hasText(productCode), ProductProperty::getProductCode, trim(productCode))
        .like(StringUtils.hasText(productName), ProductProperty::getProductName, trim(productName))
        .eq(StringUtils.hasText(productAttr), ProductProperty::getProductAttr, trim(productAttr))
        .orderByDesc(ProductProperty::getPropertyYear)
        .orderByAsc(ProductProperty::getProductCode);
    Page<ProductProperty> result = productPropertyMapper.selectPage(new Page<>(page, pageSize), query);
    decorateWithRules(result.getRecords(), bu);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "productProperty", allEntries = true)
  public ProductPropertyImportResult importExcel(
      InputStream input,
      String fileName,
      Integer propertyYear,
      String businessUnitType,
      String importMode) {
    ProductPropertyImportResult result = new ProductPropertyImportResult();
    String bu = resolveBusinessUnit(businessUnitType);
    String mode = normalizeMode(importMode, result);
    if (propertyYear == null || propertyYear < 2000 || propertyYear > 2100) {
      result.addError("年度必须在 2000 到 2100 之间");
      return result;
    }
    if (mode == null) {
      return result;
    }

    List<ImportRow> rows;
    try (Workbook workbook = WorkbookFactory.create(input)) {
      SheetSelection selection = selectSheet(workbook);
      if (selection.error() != null) {
        result.addError(selection.error());
        return result;
      }
      rows = parseRows(selection.sheet(), selection.header(), result);
    } catch (Exception ex) {
      result.addError("Excel 解析失败：" + safeMessage(ex));
      return result;
    }
    result.setTotal(rows.size());
    if (rows.isEmpty()) {
      result.addError("未解析到有效产品属性数据");
      return result;
    }

    validateDuplicatesAndRequired(rows, result);
    Map<String, BigDecimal> rules = ruleMap(propertyYear, bu);
    if (!rules.keySet().containsAll(EXPECTED_ATTRIBUTES)) {
      result.addError("请先维护 " + propertyYear + " 年全部四项产品属性上浮规则");
    }
    for (ImportRow row : rows) {
      if (StringUtils.hasText(row.productAttr()) && !rules.containsKey(row.productAttr())) {
        addError(result, "第 " + row.rowNo() + " 行产品属性“" + row.productAttr() + "”没有年度上浮规则");
      }
    }

    Map<String, Set<String>> masterDivisions = loadMasterDivisions(
        rows.stream().map(ImportRow::productCode).filter(StringUtils::hasText).toList());
    List<ImportRow> resolved = new ArrayList<>(rows.size());
    int excelDivision = 0;
    int resolvedDivision = 0;
    for (ImportRow row : rows) {
      Set<String> databaseValues = masterDivisions.getOrDefault(row.productCode(), Set.of());
      if (StringUtils.hasText(row.businessDivision())) {
        excelDivision++;
        if (!databaseValues.isEmpty() && !databaseValues.contains(row.businessDivision())) {
          addWarning(result, "第 " + row.rowNo() + " 行料号 " + row.productCode()
              + "：Excel 生产事业部“" + row.businessDivision() + "”与料品档案“"
              + String.join("/", databaseValues) + "”不同，已按 Excel 导入");
        }
        resolved.add(row);
      } else if (databaseValues.size() == 1) {
        resolvedDivision++;
        resolved.add(row.withBusinessDivision(databaseValues.iterator().next()));
      } else if (databaseValues.isEmpty()) {
        addError(result, "第 " + row.rowNo() + " 行料号 " + row.productCode()
            + " 未提供生产事业部，且系统料品档案无法匹配");
      } else {
        addError(result, "第 " + row.rowNo() + " 行料号 " + row.productCode()
            + " 在系统料品档案匹配到多个生产事业部：" + String.join("/", databaseValues));
      }
    }
    result.setExcelDivision(excelDivision);
    result.setResolvedDivision(resolvedDivision);
    if (!result.getErrors().isEmpty()) {
      return result;
    }

    List<ProductProperty> existing = productPropertyMapper.selectList(
        Wrappers.lambdaQuery(ProductProperty.class)
            .eq(ProductProperty::getBusinessUnitType, bu)
            .eq(ProductProperty::getPropertyYear, propertyYear));
    Set<String> existingCodes = existing.stream()
        .map(ProductProperty::getProductCode).collect(Collectors.toSet());
    Set<String> importedCodes = resolved.stream()
        .map(ImportRow::productCode).collect(Collectors.toCollection(LinkedHashSet::new));
    result.setInserted((int) importedCodes.stream().filter(code -> !existingCodes.contains(code)).count());
    result.setUpdated(importedCodes.size() - result.getInserted());

    String batchNo = batchNo(fileName);
    List<ProductProperty> entities = resolved.stream()
        .map(row -> toEntity(row, propertyYear, bu, batchNo))
        .toList();
    for (int start = 0; start < entities.size(); start += BATCH_SIZE) {
      productPropertyMapper.upsertBatch(entities.subList(start, Math.min(start + BATCH_SIZE, entities.size())));
    }

    if (MODE_FULL.equals(mode)) {
      List<Long> removedIds = existing.stream()
          .filter(row -> !importedCodes.contains(row.getProductCode()))
          .map(ProductProperty::getId)
          .toList();
      for (int start = 0; start < removedIds.size(); start += BATCH_SIZE) {
        productPropertyMapper.deleteByIds(
            removedIds.subList(start, Math.min(start + BATCH_SIZE, removedIds.size())));
      }
      result.setRemoved(removedIds.size());
    }
    return result;
  }

  @Override
  public List<ProductPropertyRule> listRules(Integer propertyYear, String businessUnitType) {
    if (propertyYear == null) {
      return List.of();
    }
    return ruleMapper.selectList(Wrappers.lambdaQuery(ProductPropertyRule.class)
        .eq(ProductPropertyRule::getBusinessUnitType, resolveBusinessUnit(businessUnitType))
        .eq(ProductPropertyRule::getPropertyYear, propertyYear)
        .orderByAsc(ProductPropertyRule::getId));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "productProperty", allEntries = true)
  public List<ProductPropertyRule> saveRules(ProductPropertyRuleSaveRequest request) {
    if (request == null || request.getPropertyYear() == null
        || request.getPropertyYear() < 2000 || request.getPropertyYear() > 2100) {
      throw new IllegalArgumentException("年度必须在 2000 到 2100 之间");
    }
    String bu = resolveBusinessUnit(request.getBusinessUnitType());
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    for (ProductPropertyRuleSaveRequest.RuleRow row : request.getRules()) {
      String attr = row == null ? null : trim(row.getProductAttr());
      BigDecimal rate = row == null ? null : row.getUpliftRate();
      if (!EXPECTED_ATTRIBUTES.contains(attr) || rate == null
          || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("上浮规则必须包含四类有效属性，比例范围为 0 到 1");
      }
      if (values.put(attr, rate.setScale(6, RoundingMode.HALF_UP)) != null) {
        throw new IllegalArgumentException("产品属性规则重复：" + attr);
      }
    }
    if (!values.keySet().equals(EXPECTED_ATTRIBUTES)) {
      throw new IllegalArgumentException("必须同时维护非标品、标准品、定制品、OEM 四项规则");
    }
    Map<String, ProductPropertyRule> existing = listRules(request.getPropertyYear(), bu).stream()
        .collect(Collectors.toMap(ProductPropertyRule::getProductAttr, value -> value));
    for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
      ProductPropertyRule entity = existing.get(entry.getKey());
      if (entity == null) {
        entity = new ProductPropertyRule();
        entity.setBusinessUnitType(bu);
        entity.setPropertyYear(request.getPropertyYear());
        entity.setProductAttr(entry.getKey());
        entity.setUpliftRate(entry.getValue());
        ruleMapper.insert(entity);
      } else {
        entity.setUpliftRate(entry.getValue());
        ruleMapper.updateById(entity);
      }
    }
    return listRules(request.getPropertyYear(), bu);
  }

  private void decorateWithRules(List<ProductProperty> records, String bu) {
    Map<Integer, Map<String, BigDecimal>> rulesByYear = new HashMap<>();
    for (ProductProperty row : records) {
      Map<String, BigDecimal> rules = rulesByYear.computeIfAbsent(
          row.getPropertyYear(), year -> ruleMap(year, bu));
      BigDecimal rate = rules.get(row.getProductAttr());
      row.setUpliftRate(rate);
      row.setCoefficient(rate == null ? null : BigDecimal.ONE.add(rate));
    }
  }

  private Map<String, BigDecimal> ruleMap(Integer year, String bu) {
    if (year == null) {
      return Map.of();
    }
    return ruleMapper.selectList(Wrappers.lambdaQuery(ProductPropertyRule.class)
            .eq(ProductPropertyRule::getBusinessUnitType, bu)
            .eq(ProductPropertyRule::getPropertyYear, year))
        .stream().collect(Collectors.toMap(
            ProductPropertyRule::getProductAttr,
            ProductPropertyRule::getUpliftRate,
            (left, right) -> right,
            LinkedHashMap::new));
  }

  private SheetSelection selectSheet(Workbook workbook) {
    DataFormatter formatter = new DataFormatter();
    Sheet exact = workbook.getSheet(TARGET_SHEET);
    if (exact != null) {
      HeaderMatch header = findHeader(exact, formatter);
      return header.valid()
          ? new SheetSelection(exact, header, null)
          : new SheetSelection(null, null, "工作表“" + TARGET_SHEET + "”缺少 A-E 必需表头");
    }
    List<SheetSelection> candidates = new ArrayList<>();
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      Sheet sheet = workbook.getSheetAt(i);
      HeaderMatch header = findHeader(sheet, formatter);
      if (header.valid()) {
        candidates.add(new SheetSelection(sheet, header, null));
      }
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    if (candidates.isEmpty()) {
      return new SheetSelection(null, null, "未找到产品属性工作表；支持原工作簿第二页或单独 A-E/A-F 工作表");
    }
    return new SheetSelection(null, null, "检测到多个产品属性候选工作表，请保留一页或命名为“" + TARGET_SHEET + "”");
  }

  private HeaderMatch findHeader(Sheet sheet, DataFormatter formatter) {
    int max = Math.min(sheet.getLastRowNum(), 20);
    for (int rowIndex = 0; rowIndex <= max; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || row.getFirstCellNum() < 0) {
        continue;
      }
      Map<String, Integer> fields = new HashMap<>();
      for (int column = row.getFirstCellNum(); column < row.getLastCellNum(); column++) {
        String field = resolveField(cellText(row.getCell(column), formatter));
        if (field != null) {
          fields.putIfAbsent(field, column);
        }
      }
      if (fields.keySet().containsAll(Set.of(
          "productCode", "productName", "productSpec", "productModel", "productAttr"))) {
        return new HeaderMatch(rowIndex, fields);
      }
    }
    return new HeaderMatch(-1, Map.of());
  }

  private List<ImportRow> parseRows(
      Sheet sheet, HeaderMatch header, ProductPropertyImportResult result) {
    DataFormatter formatter = new DataFormatter();
    List<ImportRow> rows = new ArrayList<>();
    for (int index = header.rowIndex() + 1; index <= sheet.getLastRowNum(); index++) {
      Row row = sheet.getRow(index);
      if (row == null) {
        continue;
      }
      String code = value(row, header.fields(), "productCode", formatter);
      String name = value(row, header.fields(), "productName", formatter);
      String spec = value(row, header.fields(), "productSpec", formatter);
      String model = value(row, header.fields(), "productModel", formatter);
      String attr = value(row, header.fields(), "productAttr", formatter);
      String division = value(row, header.fields(), "businessDivision", formatter);
      if (!StringUtils.hasText(code) && !StringUtils.hasText(name) && !StringUtils.hasText(attr)) {
        continue;
      }
      rows.add(new ImportRow(index + 1, code, name, spec, model, attr, division));
    }
    return rows;
  }

  private void validateDuplicatesAndRequired(
      List<ImportRow> rows, ProductPropertyImportResult result) {
    Set<String> seen = new HashSet<>();
    for (ImportRow row : rows) {
      if (!StringUtils.hasText(row.productCode())) {
        addError(result, "第 " + row.rowNo() + " 行料号为空");
      } else if (!seen.add(row.productCode())) {
        addError(result, "第 " + row.rowNo() + " 行料号 " + row.productCode() + " 在文件中重复");
      }
      if (!StringUtils.hasText(row.productName())) {
        addError(result, "第 " + row.rowNo() + " 行品名为空");
      }
      if (!StringUtils.hasText(row.productAttr())) {
        addError(result, "第 " + row.rowNo() + " 行产品属性为空");
      } else if (!EXPECTED_ATTRIBUTES.contains(row.productAttr())) {
        addError(result, "第 " + row.rowNo() + " 行产品属性不支持：" + row.productAttr());
      }
    }
  }

  private Map<String, Set<String>> loadMasterDivisions(List<String> codes) {
    Map<String, Set<String>> values = new HashMap<>();
    List<String> unique = codes.stream().distinct().toList();
    for (int start = 0; start < unique.size(); start += BATCH_SIZE) {
      List<MaterialMasterRaw> rows = materialMasterRawMapper.selectActiveProductionDivisionsByCodes(
          unique.subList(start, Math.min(start + BATCH_SIZE, unique.size())));
      for (MaterialMasterRaw row : rows) {
        String code = trim(row.getMaterialCode());
        String division = trim(row.getProductionDivision());
        if (StringUtils.hasText(code) && StringUtils.hasText(division)) {
          values.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add(division);
        }
      }
    }
    return values;
  }

  private ProductProperty toEntity(
      ImportRow row, Integer year, String bu, String batchNo) {
    ProductProperty entity = new ProductProperty();
    entity.setBusinessUnitType(bu);
    entity.setPropertyYear(year);
    entity.setProductCode(row.productCode());
    entity.setProductName(row.productName());
    entity.setProductSpec(row.productSpec());
    entity.setProductModel(row.productModel());
    entity.setProductAttr(row.productAttr());
    entity.setBusinessDivision(row.businessDivision());
    entity.setSourceType("BUSINESS_EXCEL");
    entity.setSourceBatchNo(batchNo);
    return entity;
  }

  private String resolveField(String header) {
    return switch (normalizeHeader(header)) {
      case "料号", "产品料号", "物料编码" -> "productCode";
      case "品名", "产品名称", "物料名称" -> "productName";
      case "规格", "产品规格" -> "productSpec";
      case "型号", "产品型号" -> "productModel";
      case "产品属性", "判定规则" -> "productAttr";
      case "生产事业部", "事业部" -> "businessDivision";
      default -> null;
    };
  }

  private String value(
      Row row, Map<String, Integer> fields, String field, DataFormatter formatter) {
    Integer index = fields.get(field);
    return index == null ? null : trim(cellText(row.getCell(index), formatter));
  }

  private String cellText(Cell cell, DataFormatter formatter) {
    if (cell == null) {
      return "";
    }
    if (cell.getCellType() == CellType.FORMULA) {
      return switch (cell.getCachedFormulaResultType()) {
        case STRING -> cell.getStringCellValue();
        case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
        case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
        default -> "";
      };
    }
    if (cell.getCellType() == CellType.NUMERIC) {
      return NumberToTextConverter.toText(cell.getNumericCellValue());
    }
    return formatter.formatCellValue(cell);
  }

  private String normalizeHeader(String value) {
    return StringUtils.hasText(value)
        ? value.replaceAll("[\\s\\n\\r\\t（）()，,：:；;_/\\\\-]", "").trim()
        : "";
  }

  private String resolveBusinessUnit(String requested) {
    String value = trim(requested);
    String current = trim(BusinessUnitContext.getCurrentBusinessUnitType());
    if (StringUtils.hasText(current) && !BusinessUnitContext.isAdmin()) {
      if (StringUtils.hasText(value) && !current.equalsIgnoreCase(value)) {
        throw new IllegalArgumentException("无权访问其他业务单元的产品属性");
      }
      return current.toUpperCase();
    }
    if (!StringUtils.hasText(value)) value = current;
    return StringUtils.hasText(value) ? value.toUpperCase() : DEFAULT_BUSINESS_UNIT;
  }

  private String normalizeMode(String value, ProductPropertyImportResult result) {
    String mode = StringUtils.hasText(value) ? value.trim().toUpperCase() : MODE_INCREMENTAL;
    if (!Set.of(MODE_INCREMENTAL, MODE_FULL).contains(mode)) {
      result.addError("导入模式只支持 INCREMENTAL（增量）或 FULL（全量）");
      return null;
    }
    return mode;
  }

  private String batchNo(String fileName) {
    String safe = StringUtils.hasText(fileName) ? fileName.replaceAll("[^0-9A-Za-z._\\-\\u4e00-\\u9fa5]", "_") : "upload.xlsx";
    String value = "BUSINESS_EXCEL:" + safe + ":"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    return value.length() <= 128 ? value : value.substring(0, 128);
  }

  private void addError(ProductPropertyImportResult result, String value) {
    if (result.getErrors().size() < MESSAGE_LIMIT) {
      result.addError(value);
    } else if (result.getErrors().size() == MESSAGE_LIMIT) {
      result.addError("错误过多，仅展示前 " + MESSAGE_LIMIT + " 条");
    }
  }

  private void addWarning(ProductPropertyImportResult result, String value) {
    if (result.getWarnings().size() < MESSAGE_LIMIT) {
      result.addWarning(value);
    } else if (result.getWarnings().size() == MESSAGE_LIMIT) {
      result.addWarning("提示过多，仅展示前 " + MESSAGE_LIMIT + " 条");
    }
  }

  private String safeMessage(Exception ex) {
    return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record HeaderMatch(int rowIndex, Map<String, Integer> fields) {
    boolean valid() { return rowIndex >= 0; }
  }

  private record SheetSelection(Sheet sheet, HeaderMatch header, String error) {}

  private record ImportRow(
      int rowNo,
      String productCode,
      String productName,
      String productSpec,
      String productModel,
      String productAttr,
      String businessDivision) {
    ImportRow withBusinessDivision(String value) {
      return new ImportRow(rowNo, productCode, productName, productSpec, productModel, productAttr, value);
    }
  }
}
