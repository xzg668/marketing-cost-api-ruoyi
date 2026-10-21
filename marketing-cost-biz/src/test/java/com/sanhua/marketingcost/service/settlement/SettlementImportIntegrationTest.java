package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.*;
import org.springframework.dao.OptimisticLockingFailureException;
import com.sanhua.marketingcost.controller.SettlementPriceController;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
class SettlementImportIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired SettlementPriceImportService imports;
  @Autowired SettlementPriceRepository prices;
  @Autowired SettlementBasePriceService bases;
  @Autowired JdbcTemplate jdbc;
  @Autowired SettlementWeightService weights;
  @Autowired SettlementPriceController controller;
  private static final java.nio.file.Path ARCHIVE;
  static {
    try { ARCHIVE = java.nio.file.Files.createTempDirectory("settlement-import-tests-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }
  private final SettlementScope october = new SettlementScope("COMMERCIAL", "2026-10");

  @BeforeEach void clearSettlement() {
    jdbc.execute("DROP TRIGGER IF EXISTS stp_reject_import");
    jdbc.update("DELETE FROM lp_settlement_price");
    jdbc.update("DELETE FROM lp_settlement_base_price");
  }
  @AfterEach void cleanTrigger() {
    jdbc.execute("DROP TRIGGER IF EXISTS stp_reject_import");
    SecurityContextHolder.clearContext();
  }

  @Test void originalImportRetainsAllSourcesAndReimportHasNoBusinessChanges() throws Exception {
    byte[] bytes = original();
    var preview = imports.preview(bytes, "明细表导出_20261017.xls", october);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_price", Integer.class)).isZero();
    var result = imports.confirm(bytes, "明细表导出_20261017.xls", october, preview.fingerprint(), "finance");
    assertThat(result.inserted()).isEqualTo(37);
    assertThat(result.sourceRows()).isEqualTo(38);
    var rows = prices.effective(october, null, null);
    assertThat(rows).hasSize(37);
    assertThat(rows.stream().filter(r -> r.getPriceType().name().equals("SETTLE_FIXED"))).hasSize(31);
    assertThat(bases.effective(october).orElseThrow().copperPrice()).isEqualByComparingTo("104.526");
    var duplicate = rows.stream().filter(r -> r.getMaterialCode().equals("809633")).findFirst().orElseThrow();
    assertThat(duplicate.getSourceRowsJson()).contains("39", "40", "CA-SH-00420260801-0017", "CA-SH-00420260828-2824");
    assertThat(imports.original("COMMERCIAL", duplicate.getId()).bytes()).isEqualTo(bytes);
    var fresh = imports.preview(bytes, "明细表导出_20261017.xls", october);
    var replay = imports.confirm(bytes, "明细表导出_20261017.xls", october, fresh.fingerprint(), "finance");
    assertThat(replay.unchanged()).isEqualTo(37);
    assertThat(replay.inserted() + replay.revised()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_price", Integer.class)).isEqualTo(37);
  }

  @Test void oneChangedRowCreatesOneRevisionAndPartialNovemberImportKeepsAllOtherMaterials() throws Exception {
    upload(original(), october);
    var before = prices.effective(october, null, null);
    byte[] changed = edited(sheet -> sheet.getRow(3).getCell(8).setCellValue(99));
    var updated = upload(changed, october);
    assertThat(updated.revised()).isOne();
    assertThat(updated.unchanged()).isEqualTo(36);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_price", Integer.class)).isEqualTo(38);
    var old = before.stream().filter(r -> r.getMaterialCode().equals("201809648")).findFirst().orElseThrow();
    assertThat(prices.byId("COMMERCIAL", old.getId()).orElseThrow().getGivenPrice()).isEqualByComparingTo(old.getGivenPrice());
    byte[] partial = edited(sheet -> {
      for (int r = sheet.getLastRowNum(); r >= 4; r--) if (sheet.getRow(r) != null) sheet.removeRow(sheet.getRow(r));
      sheet.getRow(3).getCell(8).setCellValue(88);
    });
    var november = new SettlementScope("COMMERCIAL", "2026-11");
    assertThat(upload(partial, november).inserted()).isOne();
    assertThat(prices.effective(november, null, null)).hasSize(37);
    assertThat(prices.effective(november, "201809648", SettlementPriceType.SETTLE_FIXED).getFirst().getGivenPrice()).isEqualByComparingTo("88");
    assertThat(prices.effective(november, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst().getPriceMonth()).isEqualTo("2026-10");
    assertThat(prices.effective(october, "201809648", SettlementPriceType.SETTLE_FIXED).getFirst().getGivenPrice()).isEqualByComparingTo("99");
  }

  @Test void financeConfirmedWeightsSurviveBlankOriginalReimportAndHistoryStaysIntact() throws Exception {
    upload(original(), october);
    var initial = linked();
    var confirmed = weights.confirm("COMMERCIAL", initial.getId(), weightChange(1), "finance");
    assertThat(confirmed.getRevisionNo()).isEqualTo(2);
    assertThat(confirmed.weights().completeAndConfirmed()).isTrue();
    assertThat(confirmed.getOriginalCopperWeightG()).isEqualByComparingTo("7.8");
    assertThat(prices.byId("COMMERCIAL", initial.getId()).orElseThrow().isWeightsConfirmed()).isFalse();
    assertThat(upload(original(), october).unchanged()).isEqualTo(37);
    assertThat(linked().getId()).isEqualTo(confirmed.getId());
    assertThat(linked().getCopperWeightG()).isEqualByComparingTo("8");
    assertThat(weights.confirm("COMMERCIAL", confirmed.getId(), weightChange(2), "finance").getId()).isEqualTo(confirmed.getId());
    assertThatThrownBy(() -> weights.confirm("HOUSEHOLD", confirmed.getId(), weightChange(2), "finance"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> weights.confirm("COMMERCIAL", initial.getId(), weightChange(1), "finance"))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test void missingWeightAndExcessPrecisionCannotBeConfirmedButExplicitZerosCan() throws Exception {
    upload(original(), october);
    var initial = linked();
    for (var change : List.of(new SettlementWeightService.Change(null, BigDecimal.ZERO, 1, "test"),
        new SettlementWeightService.Change(new BigDecimal("0.0000000000001"), BigDecimal.ZERO, 1, "test")))
      assertThatThrownBy(() -> weights.confirm("COMMERCIAL", initial.getId(), change, "finance")).isInstanceOf(IllegalArgumentException.class);
    var zero = weights.confirm("COMMERCIAL", initial.getId(), new SettlementWeightService.Change(BigDecimal.ZERO, BigDecimal.ZERO, 1, "财务确认无铜锌"), "finance");
    assertThat(zero.weights().completeAndConfirmed()).isTrue();
    assertThat(zero.getCopperWeightG()).isZero();
    assertThat(zero.getZincWeightG()).isZero();
  }

  @Test void databaseFailureAfterEarlierRowsRollsBackWholeImportAndBaseInitialization() throws Exception {
    jdbc.execute("""
        CREATE TRIGGER stp_reject_import BEFORE INSERT ON lp_settlement_price FOR EACH ROW
        BEGIN
          IF NEW.material_code='203240646' AND EXISTS(SELECT 1 FROM lp_settlement_price) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='stp failure after earlier rows';
          END IF;
        END
        """);
    assertThatThrownBy(() -> upload(original(), october)).hasMessageContaining("stp failure after earlier rows");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_price", Integer.class)).isZero();
    assertThat(bases.history(october)).isEmpty();
  }

  @Test void maintainedBasesAndAllAdjacentPriceSourcesArePreserved() throws Exception {
    bases.save(october, new SettlementBasePriceService.Change(new BigDecimal("120"), new BigDecimal("30"), "CNY/KG", 0, "财务确认"), "finance");
    var checksum = jdbc.queryForList(protectedChecksums());
    upload(original(), october);
    assertThat(bases.effective(october).orElseThrow().copperPrice()).isEqualByComparingTo("120");
    assertThat(bases.history(october)).hasSize(1);
    assertThat(jdbc.queryForList(protectedChecksums())).isEqualTo(checksum);
    assertThat(linked().getReferenceCu()).isEqualByComparingTo("104.526");
    assertThat(linked().getBudgetCu()).isEqualByComparingTo("78");
  }

  @Test void stalePreviewCannotOverwriteAConcurrentFinanceEdit() throws Exception {
    byte[] bytes = original();
    var preview = imports.preview(bytes, "original.xls", october);
    bases.save(october, new SettlementBasePriceService.Change(new BigDecimal("120"), new BigDecimal("30"), "CNY/KG", 0, "财务确认"), "finance");
    assertThatThrownBy(() -> imports.confirm(bytes, "original.xls", october, preview.fingerprint(), "finance"))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_price", Integer.class)).isZero();
  }

  @Test void importAndWeightPermissionsAreSeparateAndOriginalDownloadIsBusinessScoped() throws Exception {
    var file = new MockMultipartFile("file", "original.xls", "application/vnd.ms-excel", original());
    authenticate("price:settlement:list");
    assertThat(controller.list("2026-10", null, null).getData()).isEmpty();
    assertThat(controller.pricing("2026-10", null, null).getData()).isEmpty();
    assertThatThrownBy(() -> controller.preview(file, "2026-10")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.confirmWeights(1L, weightChange(1), () -> "finance")).isInstanceOf(AccessDeniedException.class);
    authenticate("price:settlement:import");
    var preview = controller.preview(file, "2026-10").getData();
    assertThatThrownBy(() -> controller.confirm(file, "2026-10", preview.fingerprint(), null)).hasMessageContaining("登录");
    var result = controller.confirm(file, "2026-10", preview.fingerprint(), () -> "finance").getData();
    long id = result.ruleIds().getFirst();
    assertThat(prices.byId("HOUSEHOLD", id)).isPresent();
    assertThat(prices.byId("COMMERCIAL", id)).isEmpty();
    authenticate("price:settlement:list");
    assertThat(controller.original(id).getBody()).isEqualTo(original());
    assertThat(controller.detail(id).getData().getBusinessUnitType()).isEqualTo("HOUSEHOLD");
    assertThat(controller.history(id).getData()).hasSize(1);
    assertThat(controller.pricing("2026-11", null, null).getData()).hasSize(37);
    assertThatThrownBy(() -> imports.original("COMMERCIAL", id)).hasMessageContaining("不存在");
    authenticate("unrelated");
    assertThatThrownBy(() -> controller.pricing("2026-10", null, null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.detail(id)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.original(id)).isInstanceOf(AccessDeniedException.class);
  }

  @Test void everyPersistedGivenPriceAndRawWeightMatchesIndependentSourceAudit() throws Exception {
    upload(original(), october);
    var audit = new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream("/settlement/original-audit.json"));
    int counted = 0;
    for (var source : audit.path("rows")) {
      var cells = source.path("values");
      var type = cells.get(10).asText().equals("联动价格") ? SettlementPriceType.SETTLE_LINKED : SettlementPriceType.SETTLE_FIXED;
      var row = prices.effective(october, cells.get(0).asText(), type).getFirst();
      assertThat(row.getGivenPrice()).isEqualByComparingTo(cells.get(8).decimalValue());
      assertThat(row.getPlannedPrice()).isEqualByComparingTo(cells.get(3).decimalValue());
      assertThat(row.getMarkupRatio()).isEqualByComparingTo(cells.get(4).decimalValue());
      assertThat(row.getBaseSettlePrice()).isEqualByComparingTo(cells.get(5).decimalValue());
      if (cells.get(6).isNumber()) assertThat(row.getOriginalCopperWeightG()).isEqualByComparingTo(cells.get(6).decimalValue());
      else assertThat(row.getOriginalCopperWeightG()).isNull();
      if (cells.get(7).isNumber()) assertThat(row.getOriginalZincWeightG()).isEqualByComparingTo(cells.get(7).decimalValue());
      else assertThat(row.getOriginalZincWeightG()).isNull();
      assertThat(row.isWeightsConfirmed()).isFalse();
      counted++;
    }
    assertThat(counted).isEqualTo(38);
  }

  private void authenticate(String permission) {
    var auth = new UsernamePasswordAuthenticationToken("finance", "", List.of(new SimpleGrantedAuthority(permission)));
    auth.setDetails(java.util.Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Test void simultaneousUploadAndWeightConfirmationHaveOneWinnerAndSafeRetryPreservesBothChanges() throws Exception {
    upload(original(), october);
    var initial = linked();
    byte[] changed = edited(sheet -> sheet.getRow(9).getCell(8).setCellValue(4.941));
    var preview = imports.preview(changed, "changed.xls", october);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var upload = pool.submit(() -> { start.await(); try {
        imports.confirm(changed, "changed.xls", october, preview.fingerprint(), "finance"); return true;
      } catch (OptimisticLockingFailureException conflict) { return false; } });
      var weight = pool.submit(() -> { start.await(); try {
        weights.confirm("COMMERCIAL", initial.getId(), weightChange(1), "finance"); return true;
      } catch (OptimisticLockingFailureException conflict) { return false; } });
      start.countDown();
      assertThat(List.of(upload.get(15, TimeUnit.SECONDS), weight.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
    }
    upload(changed, october);
    var current = linked();
    if (!current.isWeightsConfirmed()) weights.confirm("COMMERCIAL", current.getId(), weightChange(current.getRevisionNo()), "finance");
    assertThat(linked().getGivenPrice()).isEqualByComparingTo("4.941");
    assertThat(linked().getCopperWeightG()).isEqualByComparingTo("8");
    assertThat(linked().getZincWeightG()).isEqualByComparingTo("1.5");
  }

  private String protectedChecksums() {
    return "CHECKSUM TABLE lp_price_fixed_item,lp_price_fixed_item_srm_raw,lp_price_linked_item,lp_price_range_factor_rule,"
        + "lp_factor_monthly_price,lp_factor_upload_batch,lp_finance_effective_price,lp_quote_cost_run_version,lp_cost_run_part_item,lp_price_prepare_item";
  }

  private SettlementPriceRecord linked() { return prices.effective(october, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst(); }
  private SettlementWeightService.Change weightChange(int expected) {
    return new SettlementWeightService.Change(new BigDecimal("8"), new BigDecimal("1.5"), expected, "财务实测确认");
  }
  private SettlementPriceImportService.ImportResult upload(byte[] bytes, SettlementScope scope) {
    var preview = imports.preview(bytes, "original.xls", scope);
    return imports.confirm(bytes, "original.xls", scope, preview.fingerprint(), "finance");
  }
  private byte[] edited(Consumer<Sheet> edit) throws Exception {
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(original()))) {
      edit.accept(book.getSheetAt(0));
      var out = new ByteArrayOutputStream(); book.write(out); return out.toByteArray();
    }
  }

  private byte[] original() throws Exception {
    try (var input = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { return input.readAllBytes(); }
  }
}
