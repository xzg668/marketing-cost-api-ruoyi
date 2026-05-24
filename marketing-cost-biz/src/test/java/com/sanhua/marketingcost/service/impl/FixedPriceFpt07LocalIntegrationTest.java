package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemImportResponse;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.service.PriceFixedItemService;
import com.sanhua.marketingcost.service.pricing.FixedPriceResolver;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class FixedPriceFpt07LocalIntegrationTest {

  private static final Path EXCEL_PATH =
      Path.of("/Users/xiexicheng/Documents/sales_cost/3 产品成本计算表（3.29- 提供）5.15改.xls");
  private static final String IMPORT_FILE_NAME = "3 产品成本计算表（3.29- 提供）5.15改.xls";
  private static final String PRICING_MONTH = "2026-03";
  private static final DataFormatter FORMATTER = new DataFormatter();

  @Autowired
  private PriceFixedItemService priceFixedItemService;
  @Autowired
  private FixedPriceResolver fixedPriceResolver;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void realExcelImportIsIdempotentAndCostResolverIsIsolated() throws Exception {
    ParsedFixedPriceRows parsed = parseWorkbook();
    assertThat(parsed.purchaseRows()).hasSize(15);
    assertThat(parsed.householdSettleRows()).hasSize(95);
    assertThat(parsed.u9Rows()).hasSize(1);
    assertThat(parsed.householdSettleRows().stream().filter(row -> row.getFixedPrice() != null)).hasSize(57);
    assertThat(parsed.householdSettleRows().stream().filter(row -> row.getFixedPrice() == null)).hasSize(38);

    PriceFixedItemImportResponse firstPurchase =
        importRows("FPT07_PURCHASE_FIRST", parsed.purchaseRows());
    PriceFixedItemImportResponse secondPurchase =
        importRows("FPT07_PURCHASE_SECOND", parsed.purchaseRows());
    PriceFixedItemImportResponse firstSettle =
        importRows("FPT07_SETTLE_FIRST", parsed.settleRows());
    PriceFixedItemImportResponse secondSettle =
        importRows("FPT07_SETTLE_SECOND", parsed.settleRows());

    assertThat(firstPurchase.getCreatedCount() + firstPurchase.getUpdatedCount()).isEqualTo(15);
    assertThat(secondPurchase.getCreatedCount()).isZero();
    assertThat(secondPurchase.getUpdatedCount()).isEqualTo(15);
    assertThat(firstSettle.getCreatedCount() + firstSettle.getUpdatedCount()).isEqualTo(96);
    assertThat(firstSettle.getWarnings()).hasSize(38);
    assertThat(secondSettle.getCreatedCount()).isZero();
    assertThat(secondSettle.getUpdatedCount()).isEqualTo(96);
    assertThat(secondSettle.getWarnings()).hasSize(38);

    printImportResult("固定采购价首次导入", firstPurchase);
    printImportResult("固定采购价重复导入", secondPurchase);
    printImportResult("结算固定价首次导入", firstSettle);
    printImportResult("结算固定价重复导入", secondSettle);
    printDatabaseStats();

    assertThat(count("source_type='PURCHASE_FIXED' AND source_sheet_name='固定采购价5'")).isEqualTo(14);
    assertThat(count("source_type='PURCHASE_FIXED' AND fixed_price IS NOT NULL")).isEqualTo(14);
    assertThat(count("source_type='SETTLE_FIXED' AND source_system='EXCEL' AND source_sheet_name='家用结算价9'"))
        .isEqualTo(95);
    assertThat(count("source_type='SETTLE_FIXED' AND source_system='U9' AND source_sheet_name='固定采购价5'"))
        .isEqualTo(1);
    assertThat(count("source_type='SETTLE_FIXED' AND fixed_price IS NOT NULL")).isEqualTo(58);
    assertThat(count("source_type='SETTLE_FIXED' AND fixed_price IS NULL")).isEqualTo(38);

    BigDecimal settlePrice203259840 = queryPrice(
        "source_type='SETTLE_FIXED' AND source_system='EXCEL' AND material_code='203259840'");
    BigDecimal u9Price301220046 = queryPrice(
        "source_type='SETTLE_FIXED' AND source_system='U9' AND material_code='301220046'");
    assertThat(settlePrice203259840).isEqualByComparingTo("0.340314");
    assertThat(u9Price301220046).isEqualByComparingTo("27.185800");

    PriceResolveResult purchaseHit = fixedPriceResolver.resolve(
        "FPT07", part("203240246"), route("固定采购价"));
    PriceResolveResult purchaseAsSettleMiss = fixedPriceResolver.resolve(
        "FPT07", part("203240246"), route("结算价"));
    PriceResolveResult settleHit = fixedPriceResolver.resolve(
        "FPT07", part("203259840"), route("结算价"));
    PriceResolveResult settleAsPurchaseMiss = fixedPriceResolver.resolve(
        "FPT07", part("203259840"), route("固定采购价"));

    assertThat(purchaseHit.unitPrice()).isNotNull();
    assertThat(purchaseHit.priceSource()).isEqualTo("固定采购价");
    assertThat(purchaseAsSettleMiss.unitPrice()).isNull();
    assertThat(settleHit.unitPrice()).isEqualByComparingTo("0.340314");
    assertThat(settleHit.priceSource()).isEqualTo("结算固定价");
    assertThat(settleAsPurchaseMiss.unitPrice()).isNull();
  }

  private PriceFixedItemImportResponse importRows(
      String batchNo, List<PriceFixedItemImportRequest.PriceFixedItemImportRow> rows) {
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setImportFileName(IMPORT_FILE_NAME);
    request.setImportedBy("FPT07");
    request.setSourceBatchNo(batchNo);
    request.setRows(rows);
    return priceFixedItemService.importItems(request);
  }

  private ParsedFixedPriceRows parseWorkbook() throws Exception {
    assertThat(Files.exists(EXCEL_PATH)).as("真实 Excel 文件存在").isTrue();
    try (InputStream input = Files.newInputStream(EXCEL_PATH);
        Workbook workbook = WorkbookFactory.create(input)) {
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> purchaseRows =
          parsePurchaseFixedRows(workbook);
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> householdRows =
          parseHouseholdSettleRows(workbook);
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> u9Rows = parseU9Rows(workbook);
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> settleRows =
          new ArrayList<>(householdRows.size() + u9Rows.size());
      settleRows.addAll(householdRows);
      settleRows.addAll(u9Rows);
      return new ParsedFixedPriceRows(purchaseRows, householdRows, u9Rows, settleRows);
    }
  }

  private List<PriceFixedItemImportRequest.PriceFixedItemImportRow> parsePurchaseFixedRows(
      Workbook workbook) {
    Sheet sheet = sheet(workbook, "固定采购价5");
    int headerRowNo = findHeaderRow(sheet, List.of("id", "流程编号", "物料代码", "现不含税价格"));
    Map<String, Integer> header = headerIndex(sheet.getRow(headerRowNo));
    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> rows = new ArrayList<>();
    for (int i = headerRowNo + 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) {
        continue;
      }
      String processNo = text(row, header, "流程编号");
      BigDecimal fixedPrice = number(row, header, "现不含税价格");
      String externalRowId = text(row, header, "id");
      String materialCode = text(row, header, "物料代码");
      String materialName = text(row, header, "物料名称");
      if ("U9".equals(processNo) || externalRowId.isBlank() || materialCode.isBlank()
          || materialName.isBlank() || fixedPrice == null) {
        continue;
      }
      PriceFixedItemImportRequest.PriceFixedItemImportRow item =
          basePurchaseRow(sheet.getSheetName(), i + 1, row, header, fixedPrice);
      item.setSourceType("PURCHASE_FIXED");
      item.setExternalRowId(externalRowId);
      item.setSourceSystem(text(row, header, "SRM单据编号").isBlank() ? "EXCEL" : "SRM");
      rows.add(item);
    }
    return rows;
  }

  private PriceFixedItemImportRequest.PriceFixedItemImportRow basePurchaseRow(
      String sheetName, int sourceRowNo, Row row, Map<String, Integer> header, BigDecimal fixedPrice) {
    PriceFixedItemImportRequest.PriceFixedItemImportRow item =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    item.setSourceSheetName(sheetName);
    item.setSourceRowNo(sourceRowNo);
    item.setPricingMonth(PRICING_MONTH);
    item.setTaxIncluded(false);
    item.setProcessStatus(text(row, header, "流程状态"));
    item.setSrmDocNo(text(row, header, "SRM单据编号"));
    item.setProcessNo(text(row, header, "流程编号"));
    item.setMaterialCategory(text(row, header, "物料类别"));
    item.setMaterialCode(text(row, header, "物料代码"));
    item.setMaterialName(text(row, header, "物料名称"));
    item.setSpecModel(firstText(text(row, header, "规格型号"), text(row, header, "型号")));
    item.setUnit(text(row, header, "价格单位"));
    item.setTaxRate(number(row, header, "税率"));
    item.setBlankWeight(number(row, header, "下料重量"));
    item.setNetWeight(number(row, header, "产品净重"));
    item.setOriginalProcessFee(number(row, header, "原不含税加工费"));
    item.setOriginalProcessFeeTaxIncluded(number(row, header, "原含税加工费"));
    item.setOriginalTaxExcludedPrice(number(row, header, "原不含税价格"));
    item.setOriginalTaxIncludedPrice(number(row, header, "原含税价格"));
    item.setOriginalSupplierName(text(row, header, "原供方名称"));
    item.setCurrentProcessFee(number(row, header, "现不含税加工费"));
    item.setCurrentProcessFeeTaxIncluded(number(row, header, "现含税加工费"));
    item.setCurrentTaxExcludedPrice(fixedPrice);
    item.setFixedPrice(fixedPrice);
    item.setCurrentTaxIncludedPrice(number(row, header, "现含税价格"));
    item.setCurrentSupplierName(text(row, header, "现供方名称"));
    item.setSupplierName(text(row, header, "现供方名称"));
    item.setChangeAmount(number(row, header, "上涨额"));
    item.setChangeRate(number(row, header, "幅度"));
    item.setExecutionPeriodText(text(row, header, "执行日期"));
    item.setAnnualUsageText(text(row, header, "预计年用量"));
    item.setApplicant(text(row, header, "申请人"));
    item.setApplyDept(text(row, header, "申请部门"));
    item.setMarketSituation(text(row, header, "市场行情"));
    item.setSimilarCompare(text(row, header, "类似物比较"));
    item.setApprovalConclusion(text(row, header, "结论"));
    item.setApprovalType(text(row, header, "审批表类型"));
    item.setBusinessDivision(text(row, header, "涉及事业部"));
    item.setPrintFlag(text(row, header, "是否打印"));
    return item;
  }

  private List<PriceFixedItemImportRequest.PriceFixedItemImportRow> parseHouseholdSettleRows(
      Workbook workbook) {
    Sheet sheet = sheet(workbook, "家用结算价9");
    int headerRowNo = findHeaderRow(sheet, List.of("料号", "料品名称", "型号", "计划价", "上浮比例"));
    Row headerRow = sheet.getRow(headerRowNo);
    Map<String, Integer> header = headerIndex(headerRow);
    int referenceIndex = findReferenceIndex(headerRow);
    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> rows = new ArrayList<>();
    for (int i = headerRowNo + 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) {
        continue;
      }
      String materialCode = text(row, header, "料号");
      String materialName = text(row, header, "料品名称");
      if (materialCode.isBlank() || materialName.isBlank()) {
        continue;
      }
      String referenceRaw = text(row.getCell(referenceIndex));
      BigDecimal referencePrice = number(row.getCell(referenceIndex));
      PriceFixedItemImportRequest.PriceFixedItemImportRow item =
          new PriceFixedItemImportRequest.PriceFixedItemImportRow();
      item.setSourceType("SETTLE_FIXED");
      item.setSourceSystem("EXCEL");
      item.setSourceSheetName(sheet.getSheetName());
      item.setSourceRowNo(i + 1);
      item.setPricingMonth(PRICING_MONTH);
      item.setTaxIncluded(false);
      item.setMaterialCode(materialCode);
      item.setMaterialName(materialName);
      item.setSpecModel(text(row, header, "型号"));
      item.setPlannedPrice(number(row, header, "计划价"));
      item.setMarkupRatio(number(row, header, "上浮比例"));
      item.setBaseSettlePrice(number(row, header, "基准结算价"));
      item.setLinkedSettlePrice(number(row, header, "联动结算价"));
      item.setRemark(text(row, header, "备注"));
      item.setSettleReferenceHeader(text(headerRow.getCell(referenceIndex)));
      item.setSettleReferencePrice(referencePrice);
      item.setFixedPrice(referencePrice);
      if (referencePrice == null) {
        item.setSettleReferenceText(referenceRaw);
      }
      rows.add(item);
    }
    return rows;
  }

  private List<PriceFixedItemImportRequest.PriceFixedItemImportRow> parseU9Rows(Workbook workbook) {
    Sheet sheet = sheet(workbook, "固定采购价5");
    int headerRowNo = findHeaderRow(sheet, List.of("流程编号", "物料代码", "物料名称", "现不含税价格"));
    Map<String, Integer> header = headerIndex(sheet.getRow(headerRowNo));
    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> rows = new ArrayList<>();
    for (int i = headerRowNo + 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null || !"U9".equals(text(row, header, "流程编号"))) {
        continue;
      }
      BigDecimal fixedPrice = number(row, header, "现不含税价格");
      if (fixedPrice == null) {
        continue;
      }
      PriceFixedItemImportRequest.PriceFixedItemImportRow item =
          basePurchaseRow(sheet.getSheetName(), i + 1, row, header, fixedPrice);
      item.setSourceType("SETTLE_FIXED");
      item.setSourceSystem("U9");
      rows.add(item);
    }
    return rows;
  }

  private Sheet sheet(Workbook workbook, String expectedName) {
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      Sheet sheet = workbook.getSheetAt(i);
      if (normalize(sheet.getSheetName()).equals(normalize(expectedName))) {
        return sheet;
      }
    }
    throw new IllegalArgumentException("未找到 sheet: " + expectedName);
  }

  private int findHeaderRow(Sheet sheet, List<String> labels) {
    List<String> normalizedLabels = labels.stream().map(this::normalize).toList();
    int bestRow = -1;
    int bestHits = 0;
    for (int i = 0; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) {
        continue;
      }
      int hits = 0;
      for (Cell cell : row) {
        if (normalizedLabels.contains(normalize(text(cell)))) {
          hits += 1;
        }
      }
      if (hits > bestHits) {
        bestHits = hits;
        bestRow = i;
      }
    }
    if (bestRow < 0) {
      throw new IllegalArgumentException("未找到表头: " + sheet.getSheetName());
    }
    return bestRow;
  }

  private Map<String, Integer> headerIndex(Row row) {
    Map<String, Integer> index = new HashMap<>();
    for (Cell cell : row) {
      String text = text(cell);
      if (!text.isBlank()) {
        index.put(normalize(text), cell.getColumnIndex());
      }
    }
    return index;
  }

  private int findReferenceIndex(Row headerRow) {
    for (Cell cell : headerRow) {
      String normalized = normalize(text(cell));
      if (normalized.contains("铜价") || normalized.contains("锌价")) {
        return cell.getColumnIndex();
      }
    }
    throw new IllegalArgumentException("家用结算价9 未找到铜价/锌价列");
  }

  private String text(Row row, Map<String, Integer> header, String label) {
    Integer column = header.get(normalize(label));
    return column == null ? "" : text(row.getCell(column));
  }

  private String text(Cell cell) {
    return cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
  }

  private BigDecimal number(Row row, Map<String, Integer> header, String label) {
    Integer column = header.get(normalize(label));
    return column == null ? null : number(row.getCell(column));
  }

  private BigDecimal number(Cell cell) {
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC) {
      return BigDecimal.valueOf(cell.getNumericCellValue());
    }
    if (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC) {
      return BigDecimal.valueOf(cell.getNumericCellValue());
    }
    return parseNumber(text(cell));
  }

  private BigDecimal parseNumber(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String cleaned = value.trim().replace(",", "").replace("%", "");
    if (!Pattern.matches("[-+]?\\d+(\\.\\d+)?", cleaned)) {
      return null;
    }
    return new BigDecimal(cleaned);
  }

  private String normalize(String value) {
    return Objects.toString(value, "")
        .replace("\uFEFF", "")
        .replace("：", "")
        .replace(":", "")
        .replaceAll("[\\s\\u3000]+", "")
        .trim();
  }

  private String firstText(String first, String second) {
    return first == null || first.isBlank() ? second : first;
  }

  private long count(String whereClause) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_price_fixed_item WHERE " + whereClause, Long.class);
  }

  private BigDecimal queryPrice(String whereClause) {
    return jdbcTemplate.queryForObject(
        "SELECT fixed_price FROM lp_price_fixed_item WHERE " + whereClause + " LIMIT 1", BigDecimal.class);
  }

  private void printImportResult(String title, PriceFixedItemImportResponse response) {
    System.out.printf(
        "%s: created=%d, updated=%d, skipped=%d, warnings=%d, errors=%d%n",
        title,
        response.getCreatedCount(),
        response.getUpdatedCount(),
        response.getSkippedCount(),
        response.getWarnings().size(),
        response.getErrors().size());
  }

  private void printDatabaseStats() {
    System.out.println("lp_price_fixed_item 统计:");
    jdbcTemplate.queryForList(
            """
            SELECT source_type, source_system, COUNT(*) total,
                   SUM(fixed_price IS NOT NULL) priced,
                   SUM(fixed_price IS NULL) no_price
            FROM lp_price_fixed_item
            WHERE source_type IN ('PURCHASE_FIXED', 'SETTLE_FIXED')
            GROUP BY source_type, source_system
            ORDER BY source_type, source_system
            """)
        .forEach(System.out::println);
  }

  private CostRunPartItemDto part(String partCode) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode(partCode);
    return item;
  }

  private PriceTypeRoute route(String rawPriceType) {
    return new PriceTypeRoute(
        "FPT07", null, PriceTypeEnum.FIXED, 1,
        LocalDate.parse("2026-03-01"), null, "FPT07", rawPriceType);
  }

  private record ParsedFixedPriceRows(
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> purchaseRows,
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> householdSettleRows,
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> u9Rows,
      List<PriceFixedItemImportRequest.PriceFixedItemImportRow> settleRows) {
  }
}
