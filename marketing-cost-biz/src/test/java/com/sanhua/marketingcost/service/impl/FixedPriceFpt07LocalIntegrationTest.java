package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemImportResponse;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.PriceFixedItemService;
import com.sanhua.marketingcost.service.pricing.FixedPriceResolver;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class FixedPriceFpt07LocalIntegrationTest extends BomMapperTestBase {

  private static final String IMPORT_FILE_NAME = "FPT-07-固定价测试夹具.xlsx";
  private static final String PRICING_MONTH = "2026-03";

  @Autowired
  private PriceFixedItemService priceFixedItemService;
  @Autowired
  private FixedPriceResolver fixedPriceResolver;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void fixtureImportIsIdempotentAndCostResolverIsIsolated() {
    ParsedFixedPriceRows parsed = fixedPriceRows();
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

  private ParsedFixedPriceRows fixedPriceRows() {
    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> purchaseRows = new ArrayList<>();
    for (int i = 1; i <= 15; i++) {
      PriceFixedItemImportRequest.PriceFixedItemImportRow row = baseFixtureRow();
      row.setSourceType("PURCHASE_FIXED");
      row.setSourceSystem("EXCEL");
      row.setSourceSheetName("固定采购价5");
      row.setSourceRowNo(i + 1);
      row.setExternalRowId("FPT07-PUR-" + (i == 15 ? 14 : i));
      row.setMaterialCode(i == 1 ? "203240246" : "FPT07_PURCHASE_" + i);
      row.setMaterialName("固定采购价夹具" + i);
      row.setFixedPrice(new BigDecimal("10.000000").add(BigDecimal.valueOf(i, 3)));
      row.setCurrentTaxExcludedPrice(row.getFixedPrice());
      purchaseRows.add(row);
    }

    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> householdRows = new ArrayList<>();
    for (int i = 1; i <= 95; i++) {
      PriceFixedItemImportRequest.PriceFixedItemImportRow row = baseFixtureRow();
      row.setSourceType("SETTLE_FIXED");
      row.setSourceSystem("EXCEL");
      row.setSourceSheetName("家用结算价9");
      row.setSourceRowNo(i + 1);
      row.setMaterialCode(i == 1 ? "203259840" : "FPT07_SETTLE_" + i);
      row.setMaterialName("家用结算价夹具" + i);
      row.setSettleReferenceHeader("铜价/锌价");
      if (i <= 57) {
        BigDecimal price =
            i == 1 ? new BigDecimal("0.340314") : BigDecimal.ONE.add(BigDecimal.valueOf(i, 3));
        row.setFixedPrice(price);
        row.setSettleReferencePrice(price);
      } else {
        row.setSettleReferenceText("不用提供");
      }
      householdRows.add(row);
    }

    PriceFixedItemImportRequest.PriceFixedItemImportRow u9 = baseFixtureRow();
    u9.setSourceType("SETTLE_FIXED");
    u9.setSourceSystem("U9");
    u9.setSourceSheetName("固定采购价5");
    u9.setSourceRowNo(100);
    u9.setMaterialCode("301220046");
    u9.setMaterialName("U9结算价夹具");
    u9.setProcessNo("U9");
    u9.setFixedPrice(new BigDecimal("27.185800"));
    u9.setSettleReferencePrice(u9.getFixedPrice());
    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> u9Rows = List.of(u9);

    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> settleRows =
        new ArrayList<>(householdRows.size() + u9Rows.size());
    settleRows.addAll(householdRows);
    settleRows.addAll(u9Rows);
    return new ParsedFixedPriceRows(purchaseRows, householdRows, u9Rows, settleRows);
  }

  private PriceFixedItemImportRequest.PriceFixedItemImportRow baseFixtureRow() {
    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setPricingMonth(PRICING_MONTH);
    row.setTaxIncluded(false);
    row.setUnit("件");
    return row;
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
