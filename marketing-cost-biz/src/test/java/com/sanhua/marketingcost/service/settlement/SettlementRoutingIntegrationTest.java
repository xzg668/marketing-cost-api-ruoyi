package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
class SettlementRoutingIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired MaterialPriceRouterService router;
  @Autowired NormalMaterialPricePrepareStrategy preparation;
  @Autowired SettlementPriceImportService imports;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired List<PriceResolver> resolvers;
  @Autowired SettlementPriceRepository prices;
  @Autowired SettlementWeightService weights;
  @Autowired MaterialPricePriorityService priorities;
  private static final Path ARCHIVE;
  private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 11, 15, 10, 0);
  static {
    try { ARCHIVE = Files.createTempDirectory("settlement-routing-tests-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }

  @BeforeEach void importSource() throws Exception {
    for (String table : List.of("lp_price_linked_item", "lp_price_range_item", "lp_price_fixed_item", "lp_material_price_type"))
      jdbc.update("DELETE FROM " + table + " WHERE material_code='203240646'");
    jdbc.update("DELETE FROM lp_price_linked_calc_item WHERE oa_no='STP-ROUTE'");
    jdbc.update("DELETE FROM lp_settlement_price");
    jdbc.update("DELETE FROM lp_settlement_base_price");
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { bytes = input.readAllBytes(); }
    var scope = new SettlementScope("COMMERCIAL", "2026-10");
    imports.confirm(bytes, "original.xls", scope, imports.preview(bytes, "original.xls", scope).fingerprint(), "finance");
    jdbc.update("DELETE FROM lp_settlement_base_price"); // 给定模式无需任何普通/结算基价。
  }

  @Test void allFiveSourcesAreSelectedInDefaultOrderAsHigherSourcesDisappear() {
    fiveSources();
    String[] types = {"LINKED", "SETTLE_LINKED", "RANGE", "SETTLE_FIXED", "FIXED"};
    String[] expected = {"11.1", "4.841", "22.2", "4.1658", "33.3"};
    for (int i = 0; i < types.length; i++) {
      assertThat(router.listCandidates("203240646", "2026-11", AS_OF.toLocalDate(), "210", "COMMERCIAL")
          .getFirst().selectedSourceType()).isEqualTo(types[i]);
      assertSingleAndBatch(types[i], expected[i]);
      switch (types[i]) {
        case "LINKED" -> jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code='203240646'");
        case "RANGE" -> jdbc.update("DELETE FROM lp_price_range_item WHERE material_code='203240646'");
        case "FIXED" -> jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code='203240646'");
        default -> jdbc.update("UPDATE lp_settlement_price SET active=0 WHERE material_code='203240646' AND price_type=?", types[i]);
      }
    }
  }

  @Test void manualPriorityWinsAndLowerSettlementFailureOnlyBlocksWhenSelected() {
    fiveSources();
    var rule = prices.effective(new SettlementScope("COMMERCIAL", "2026-10"), "203240646", SettlementPriceType.SETTLE_LINKED).getFirst();
    weights.confirm("COMMERCIAL", rule.getId(), new SettlementWeightService.Change(BigDecimal.ONE, BigDecimal.ONE, 1, "测试确认"), "finance");
    jdbc.update("UPDATE lp_settlement_price SET expression='broken expression' WHERE material_code='203240646' AND revision_no=2");
    var scope = new MaterialPricePriorityService.Scope("COMMERCIAL", "2026-11", "210", "203240646");
    priorities.initialize(scope, "测试");
    priorities.change(scope, List.of(MaterialPriceSourceType.FIXED, MaterialPriceSourceType.SETTLE_LINKED,
        MaterialPriceSourceType.LINKED, MaterialPriceSourceType.RANGE, MaterialPriceSourceType.SETTLE_FIXED), priorities.read(scope).revision(), 1L);
    assertSingleAndBatch("FIXED", "33.3");
    priorities.change(scope, null, priorities.read(scope).revision(), 1L);
    assertSingleAndBatch("LINKED", "11.1");
    jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code='203240646'");
    var plan = item("203240646", "210");
    var single = preparation.calculate("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, plan);
    var batch = preparation.prepareBatch("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, List.of(plan), true).get(plan);
    for (var failure : List.of(single, batch)) {
      assertThat(failure.getStatus()).isEqualTo("FAILED");
      assertThat(failure.getReasonCode()).isEqualTo("SETTLEMENT_FORMULA_INVALID");
      assertThat(failure.getPriceType()).isEqualTo("SETTLE_LINKED");
      assertThat(failure.getUnitPrice()).isNull();
    }
  }

  private void assertSingleAndBatch(String type, String price) {
    var plan = item("203240646", "210");
    var single = preparation.calculate("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, plan);
    var batch = preparation.prepareBatch("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, List.of(plan), true).get(plan);
    for (var result : List.of(single, batch)) {
      assertThat(result.getStatus()).describedAs(type + ": " + result.getMessage()).isEqualTo("READY");
      assertThat(result.getPriceType()).isEqualTo(type);
      assertThat(result.getUnitPrice()).isEqualByComparingTo(price);
    }
  }

  private void fiveSources() {
    var fixed = prices.effective(new SettlementScope("COMMERCIAL", "2026-10"), "809633", SettlementPriceType.SETTLE_FIXED).getFirst().copy();
    fixed.setId(null);
    fixed.setMaterialCode("203240646");
    prices.insert(fixed);
    jdbc.update("""
        INSERT INTO lp_price_linked_item(business_unit_type,org_code,material_code,pricing_month,
          rule_status,source_kind,formula_expr,effective_from,created_at,updated_at)
        VALUES('COMMERCIAL','210','203240646','2026-10','PUBLISHED','PUBLIC','11.1','2026-10-01',NOW(),NOW())
        """);
    jdbc.update("""
        INSERT INTO lp_price_range_item(business_unit_type,org_code,material_code,range_basis,
          range_low,range_high,price_incl_tax,effective_from,supplier_code,created_at,updated_at)
        VALUES('COMMERCIAL','210','203240646','QTY',0,100,22.2,'2026-10-01','S-STP',NOW(),NOW())
        """);
    jdbc.update("""
        INSERT INTO lp_price_fixed_item(business_unit_type,org_code,material_code,source_kind,
          source_type,fixed_price,effective_from,effective_to,supplier_code,created_at,updated_at)
        VALUES('COMMERCIAL','210','203240646','PUBLIC','PURCHASE_FIXED',33.3,'2026-10-01','2027-01-01','S-STP',NOW(),NOW())
        """);
  }

  @Test void discoveryAndPreparationShareOctoberSettlementAcrossBothOrganizationsWithoutBase() throws Exception {
    Long sharedId = null;
    for (String org : List.of("210", "220")) {
      var routes = router.listCandidates("203240646", "2026-11", AS_OF.toLocalDate(), org, "COMMERCIAL");
      assertThat(routes.getFirst().selectedSourceType()).isEqualTo("SETTLE_LINKED");
      var single = preparation.calculate("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, item("203240646", org));
      assertThat(single.getStatus()).isEqualTo("READY");
      assertThat(single.getPriceType()).isEqualTo("SETTLE_LINKED");
      assertThat(single.getUnitPrice()).isEqualByComparingTo("4.841");
      assertThat(single.getAmount()).isEqualByComparingTo("9.682");
      assertThat(single.getResultRefType()).isEqualTo("SETTLEMENT_PRICE");
      assertThat(json.readTree(single.getInputSnapshotJson()).path("mode").asText()).isEqualTo("LINKED_GIVEN");
      if (sharedId == null) sharedId = single.getResultRefId();
      else assertThat(single.getResultRefId()).isEqualTo(sharedId);
      var plan = item("203240646", org);
      var batch = preparation.prepareBatch("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, List.of(plan), true).get(plan);
      assertThat(batch.getUnitPrice()).isEqualByComparingTo(single.getUnitPrice());
      assertThat(batch.getPriceType()).isEqualTo(single.getPriceType());
      assertThat(batch.getResultRefId()).isEqualTo(single.getResultRefId());
      assertThat(batch.getResultRefType()).isEqualTo("SETTLEMENT_PRICE");
    }
    assertThat(resolvers).extracting(PriceResolver::priceType).doesNotHaveDuplicates();
  }

  @Test void batchDiscoveryMatchesSingleAndFutureOtherBusinessUnitOrUnsupportedOrganizationAreExcluded() {
    var codes = List.of("203240646", "809633");
    for (String org : List.of("210", "220")) {
      var batch = router.listCandidatesBatch(codes, "2026-11", AS_OF.toLocalDate(), org, "COMMERCIAL");
      for (var code : codes) assertThat(batch.get(code)).isEqualTo(router.listCandidates(code, "2026-11", AS_OF.toLocalDate(), org, "COMMERCIAL"));
      assertThat(batch.get("809633").getFirst().selectedSourceType()).isEqualTo("SETTLE_FIXED");
      var fixed = preparation.calculate("STP-ROUTE", "COMMERCIAL", "2026-11", AS_OF, null, item("809633", org));
      assertThat(fixed.getUnitPrice()).isEqualByComparingTo("4.1658");
      assertThat(fixed.getResultRefType()).isEqualTo("SETTLEMENT_PRICE");
    }
    assertThat(router.listCandidates("203240646", "2026-09", LocalDate.of(2026,9,1), "210", "COMMERCIAL")).isEmpty();
    assertThat(router.listCandidates("203240646", "2026-11", AS_OF.toLocalDate(), "210", "HOUSEHOLD")).isEmpty();
    assertThat(router.listCandidates("203240646", "2026-11", AS_OF.toLocalDate(), "999", "COMMERCIAL")).isEmpty();
  }

  private PricePreparePlanItem item(String code, String org) {
    var row = new BomCostingRow();
    row.setPriceOrgCode(org);
    row.setMaterialOrganizationCode("COMMERCIAL");
    row.setQtyPerTop(new BigDecimal("2"));
    var item = new PricePreparePlanItem();
    item.setMaterialCode(code);
    item.setMaterialName("结算测试");
    item.setBomRow(row);
    return item;
  }
}
