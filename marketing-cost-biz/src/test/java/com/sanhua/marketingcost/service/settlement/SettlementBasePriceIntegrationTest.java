package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sanhua.marketingcost.controller.SettlementBasePriceController;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class SettlementBasePriceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired SettlementBasePriceService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired SettlementBasePriceController controller;
  @Autowired FinanceFactorPublicationService publications;

  @BeforeEach void clearSettlementBases() { jdbc.update("DELETE FROM lp_settlement_base_price"); }
  @AfterEach void logout() { SecurityContextHolder.clearContext(); }

  @Test void pairHasIndependentMonthlyRevisionsAndCarriesForwardWithoutFutureLeakage() {
    var october = new SettlementScope("COMMERCIAL", "2026-10");
    assertThat(service.effective(october)).isEmpty();
    var first = service.save(october, change("104.526", "24.51", 0), "finance");
    assertThat(first.revisionNo()).isOne();
    assertThat(service.save(october, change("104.526", "24.510", 1), "finance").id()).isEqualTo(first.id());
    assertThat(service.effective(new SettlementScope("COMMERCIAL", "2026-11"))).contains(first);
    var updated = service.save(october, change("105", "25", 1), "finance");
    assertThat(updated.revisionNo()).isEqualTo(2);
    service.save(new SettlementScope("COMMERCIAL", "2026-12"), change("999", "999", 0), "finance");
    assertThat(service.effective(new SettlementScope("COMMERCIAL", "2026-11"))).contains(updated);
    assertThat(service.effective(new SettlementScope("COMMERCIAL", "2026-09"))).isEmpty();
    assertThat(service.effective(new SettlementScope("HOUSEHOLD", "2026-11"))).isEmpty();
    assertThat(service.history(october)).extracting(SettlementBasePrice::id).containsExactly(updated.id(), first.id());
  }

  @Test void novemberPairWinsAsAWholeAndTonConversionIsExact() {
    service.save(new SettlementScope("COMMERCIAL", "2026-10"), change("104.526", "24.51", 0), "finance");
    var november = new SettlementScope("COMMERCIAL", "2026-11");
    var result = service.save(november, new SettlementBasePriceService.Change(new BigDecimal("110000"),
        new BigDecimal("25000"), "CNY/T", 0, "元/吨转换"), "finance");
    assertThat(service.effective(november)).contains(result);
    assertThat(result.copperPrice()).isEqualByComparingTo("110");
    assertThat(result.zincPrice()).isEqualByComparingTo("25");
    assertThat(result.unit()).isEqualTo("CNY/KG");
    assertThat(result.priceMonth()).isEqualTo("2026-11");
  }

  @Test void incompletePairsUnsupportedUnitsAndPrecisionLossDoNotCreateRevisions() {
    var scope = new SettlementScope("COMMERCIAL", "2026-10");
    for (var change : List.of(
        new SettlementBasePriceService.Change(BigDecimal.ONE, null, "CNY/KG", 0, "test"),
        new SettlementBasePriceService.Change(BigDecimal.ONE, BigDecimal.ZERO, "USD/KG", 0, "test"),
        change("0.0000000000001", "25", 0), change("-1", "25", 0))) {
      assertThatThrownBy(() -> service.save(scope, change, "finance")).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(service.history(scope)).isEmpty();
    var zero = service.save(scope, change("0", "0", 0), "finance");
    assertThat(zero.copperPrice()).isZero();
    assertThat(zero.zincPrice()).isZero();
  }

  @Test void concurrentEditsReportConflictAndNeverMixTheTwoMetals() throws Exception {
    var scope = new SettlementScope("COMMERCIAL", "2026-10");
    service.save(scope, change("104.526", "24.51", 0), "finance");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
        start.await();
        try { service.save(scope, change(i == 0 ? "106" : "107", i == 0 ? "26" : "27", 1), "finance"); return true; }
        catch (OptimisticLockingFailureException conflict) { return false; }
      })).toList();
      start.countDown();
      assertThat(List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(service.history(scope)).hasSize(2);
    var pair = service.effective(scope).orElseThrow();
    assertThat(pair.copperPrice().subtract(pair.zincPrice())).isEqualByComparingTo("80");
    assertThatThrownBy(() -> service.save(scope, change("999", "999", 1), "finance"))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test void threeBaseFamiliesAreIsolatedInBothDirections() throws Exception {
    publishOrdinary("210", "commercial-original.xlsx", "CU_90000");
    publishOrdinary("220", "plate-original.xlsx", "BOARD");
    var before = jdbc.queryForList("CHECKSUM TABLE lp_factor_identity,lp_factor_monthly_price,lp_factor_upload_batch,"
        + "lp_factor_row_ref,lp_finance_effective_price,lp_price_fixed_item,lp_price_linked_item,lp_price_range_factor_rule");
    var scope = new SettlementScope("COMMERCIAL", "2026-10");
    service.save(scope, change("104.526", "24.51", 0), "finance");
    service.save(scope, change("105", "25", 1), "finance");
    assertThat(jdbc.queryForList("CHECKSUM TABLE lp_factor_identity,lp_factor_monthly_price,lp_factor_upload_batch,"
        + "lp_factor_row_ref,lp_finance_effective_price,lp_price_fixed_item,lp_price_linked_item,lp_price_range_factor_rule")).isEqualTo(before);
    var settlement = service.history(scope);
    var board = jdbc.queryForList("SELECT * FROM lp_factor_monthly_price WHERE price_org_code='220' ORDER BY id");
    assertThat(jdbc.update("UPDATE lp_factor_monthly_price SET price=price+1 WHERE price_org_code='210'")).isPositive();
    assertThat(service.history(scope)).isEqualTo(settlement);
    assertThat(jdbc.queryForList("SELECT * FROM lp_factor_monthly_price WHERE price_org_code='220' ORDER BY id")).isEqualTo(board);
    var commercial = jdbc.queryForList("SELECT * FROM lp_factor_monthly_price WHERE price_org_code='210' ORDER BY id");
    assertThat(jdbc.update("UPDATE lp_factor_monthly_price SET price=price+2 WHERE price_org_code='220'")).isPositive();
    assertThat(service.history(scope)).isEqualTo(settlement);
    assertThat(jdbc.queryForList("SELECT * FROM lp_factor_monthly_price WHERE price_org_code='210' ORDER BY id")).isEqualTo(commercial);
  }

  @Test void apiUsesAuthenticatedBusinessUnitAndRejectsInsufficientPermission() {
    authenticate("price:settlement-base:list");
    assertThat(controller.effective("2026-10").isSuccess()).isTrue();
    var request = new SettlementBasePriceController.SaveRequest("2026-10", new BigDecimal("104.526"),
        new BigDecimal("24.51"), "CNY/KG", 0, "财务维护");
    assertThatThrownBy(() -> controller.save(request, () -> "finance")).isInstanceOf(AccessDeniedException.class);
    authenticate("price:settlement-base:edit");
    assertThatThrownBy(() -> controller.save(request, null)).hasMessageContaining("登录");
    assertThat(controller.save(request, () -> "finance").getData().businessUnitType()).isEqualTo("HOUSEHOLD");
    assertThat(service.effective(new SettlementScope("COMMERCIAL", "2026-10"))).isEmpty();
    authenticate("unrelated");
    assertThatThrownBy(() -> controller.effective("2026-10")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.history("2026-10")).isInstanceOf(AccessDeniedException.class);
  }

  @Test void maintenanceApiExposesExactDecimalStrings() {
    authenticate("price:settlement-base:list");
    service.save(new SettlementScope("HOUSEHOLD", "2026-10"), change("123456789.123456789012", "0.000000000001", 0), "finance");
    var view = controller.effective("2026-10").getData();
    assertThat(view.copperPrice()).isEqualTo("123456789.123456789012");
    assertThat(view.zincPrice()).isEqualTo("0.000000000001");
  }

  private void authenticate(String permission) {
    var authentication = new UsernamePasswordAuthenticationToken("finance", "", List.of(new SimpleGrantedAuthority(permission)));
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private void publishOrdinary(String org, String file, String scheme) throws Exception {
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/finance-factor/" + file)) { bytes = input.readAllBytes(); }
    var scope = new FinanceFactorImportContext("COMMERCIAL", org, "2026-10", null, scheme);
    var preview = publications.preview(bytes, file, scope);
    publications.publish(bytes, file, scope, preview.previewFingerprint(), "test");
  }

  private SettlementBasePriceService.Change change(String cu, String zn, int expected) {
    return new SettlementBasePriceService.Change(new BigDecimal(cu), new BigDecimal(zn), "CNY/KG", expected, "财务月度维护");
  }
}
