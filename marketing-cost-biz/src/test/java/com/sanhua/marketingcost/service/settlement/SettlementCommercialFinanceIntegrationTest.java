package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.List;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
class SettlementCommercialFinanceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired SettlementPriceImportService imports;
  @Autowired SettlementPriceReader reader;
  @Autowired SettlementPriceRepository prices;
  @Autowired SettlementRuleRevisionFactory ruleRevisions;
  @Autowired FinanceFactorPublicationService finance;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  private final SettlementScope october = new SettlementScope("COMMERCIAL", "2026-10");
  private static final String[][] BLANK_ZINC_PRICES = {
      {"201600446", "0.2981"}, {"203240247", "0.0232"}, {"201200143", "0.0416"},
      {"201200772", "0.0443"}, {"201201131", "0.0892"}, {"201260029", "0.0703"}, {"721250136", "0.0745"}};
  private static final Path ARCHIVE;
  static {
    try { ARCHIVE = Files.createTempDirectory("settlement-commercial-tests-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }

  @BeforeEach void source() throws Exception {
    jdbc.update("DELETE FROM lp_settlement_price");
    jdbc.update("DELETE FROM lp_settlement_base_price");
    SettlementFinanceTestSupport.clear(jdbc);
    byte[] bytes = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    var preview = imports.preview(bytes, "最终结算价格.xls", october);
    assertThat(imports.confirm(bytes, "最终结算价格.xls", october, preview.fingerprint(), "finance").inserted()).isEqualTo(46);
  }

  @Test void finalRulesUseCommercialPrecisionForBothOrganizationsWhileHouseholdValuesRemainEvidence() throws Exception {
    SettlementFinanceTestSupport.publish(finance, october, "90", "21.6362912421045");
    for (String org : java.util.List.of("210", "220")) {
      String[][] samples = {{"203240646", "4.7146"}, {"1079000300147", "0.3145"},
          {"203240446", "0.0946"}, {"101303761", "8.6136"}};
      for (var sample : samples) {
        var result = reader.resolve(request(sample[0], org));
        assertThat(result.unitPrice()).as(sample[0]).isEqualByComparingTo(sample[1]);
        var captured = json.readTree(result.evidence().inputSnapshotJson());
        assertThat(captured.path("base").path("financial").path("scope").path("priceOrgCode").asText()).isEqualTo("210");
        assertThat(captured.path("rule").path("referenceCu").decimalValue()).isEqualByComparingTo("108.352");
        assertThat(captured.path("base").path("zincPrice").decimalValue()).isEqualByComparingTo("21.6362912421045");
        var prepared = new PricePrepareItem();
        prepared.setBusinessUnitType("COMMERCIAL"); prepared.setMaterialCode(sample[0]); prepared.setPeriodMonth("2026-10");
        prepared.setPriceOrgCode(org); prepared.setResultRefId(result.resultRefId()); prepared.setUnitPrice(result.unitPrice());
        prepared.setInputSnapshotJson(result.evidence().inputSnapshotJson());
        assertThat(new SettlementCalculationEvidence(json).read(prepared).path("unitPrice").decimalValue())
            .isEqualByComparingTo(sample[1]);
      }
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_base_price", Integer.class)).isZero();
  }

  @Test void unpublishedFinanceNeverUsesHouseholdGivenPricesEvenWhenBlankZincHasBecomeZero() {
    assertThat(reader.resolve(request("203240646", "210")).failureCode()).isEqualTo("SETTLEMENT_BASE_MISSING");
    assertThat(reader.resolve(request("201600446", "210")).failureCode()).isEqualTo("SETTLEMENT_BASE_MISSING");
    assertThat(reader.resolve(new SettlementPriceRequest("COMMERCIAL", "809633", "2026-10", "210", SettlementPriceType.SETTLE_FIXED))
        .unitPrice()).isEqualByComparingTo("4.1658");
  }

  @Test void allSevenBlankZincWeightsCalculateAsZeroForBothOrganizationsAndRetainRawEvidence() throws Exception {
    SettlementFinanceTestSupport.publish(finance, october, "90", "21.6362912421045");
    assertThat(prices.effective(october, null, SettlementPriceType.SETTLE_LINKED))
        .hasSize(11).allMatch(SettlementPriceRecord::isWeightsConfirmed);
    for (var sample : BLANK_ZINC_PRICES) {
      var rule = prices.effective(october, sample[0], SettlementPriceType.SETTLE_LINKED).getFirst();
      assertThat(rule.getOriginalZincWeightG()).isNull();
      assertThat(rule.getZincWeightG()).isZero();
      assertThat(rule.getCopperWeightG()).isEqualByComparingTo(rule.getOriginalCopperWeightG());
      assertThat(rule.getWeightConfirmationNote()).contains("原稿锌重为空，按原公式以0克计算");
      for (String org : List.of("210", "220")) {
        var result = reader.resolve(request(sample[0], org));
        assertThat(result.hasBlockingFailure()).as(sample[0] + "/" + org).isFalse();
        assertThat(result.unitPrice()).isEqualByComparingTo(sample[1]);
        var snapshot = json.readTree(result.evidence().inputSnapshotJson());
        assertThat(snapshot.path("mode").asText()).isEqualTo(SettlementPriceMode.LINKED_FORMULA.name());
        assertThat(snapshot.path("rule").path("originalZincWeightG").isNull()).isTrue();
        assertThat(snapshot.path("rule").path("zincWeightG").decimalValue()).isZero();
        var captured = new PricePrepareItem();
        captured.setBusinessUnitType("COMMERCIAL"); captured.setMaterialCode(sample[0]);
        captured.setPeriodMonth("2026-10"); captured.setPriceOrgCode(org);
        captured.setResultRefId(result.resultRefId()); captured.setUnitPrice(result.unitPrice());
        captured.setInputSnapshotJson(result.evidence().inputSnapshotJson());
        assertThat(new SettlementCalculationEvidence(json).read(captured).path("unitPrice").decimalValue())
            .isEqualByComparingTo(sample[1]);
      }
    }
    // 使用原表家用参考价应还原原公式；正式计算仍使用商用发布值。
    SettlementFinanceTestSupport.publish(finance, october, "108.352", "25.597");
    String[] household = {"0.3234", "0.0256", "0.0453", "0.0482", "0.0970", "0.0761", "0.0803"};
    for (int i = 0; i < BLANK_ZINC_PRICES.length; i++)
      assertThat(reader.resolve(request(BLANK_ZINC_PRICES[i][0], "210")).unitPrice()).isEqualByComparingTo(household[i]);
  }

  @Test void zincOnlyPublicationDoesNotChangeZeroZincRowsButCopperPublicationDoesWithoutRuleReimport() {
    SettlementFinanceTestSupport.publish(finance, october, "90", "21.6362912421045");
    var before = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    SettlementFinanceTestSupport.publish(finance, october, "90", "40");
    for (var sample : BLANK_ZINC_PRICES)
      assertThat(reader.resolve(request(sample[0], "220")).unitPrice()).isEqualByComparingTo(sample[1]);
    SettlementFinanceTestSupport.publish(finance, october, "95", "40");
    String[] copper95 = {"0.3050", "0.0239", "0.0426", "0.0454", "0.0913", "0.0719", "0.0761"};
    for (int i = 0; i < BLANK_ZINC_PRICES.length; i++)
      assertThat(reader.resolve(request(BLANK_ZINC_PRICES[i][0], "210")).unitPrice()).isEqualByComparingTo(copper95[i]);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(before);
  }

  @Test void identicalBlankSourcePreservesLegacyWeightsWhileChangedSourceAppendsRevision() throws Exception {
    var initial = prices.effective(october, "201600446", SettlementPriceType.SETTLE_LINKED).getFirst();
    var confirmed = SettlementRuleTestSupport.legacyWeights(prices, ruleRevisions, initial, "1.4", "0.2");
    byte[] original = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    var replay = imports.confirm(original, "最终结算价格.xls", october,
        imports.preview(original, "最终结算价格.xls", october).fingerprint(), "finance");
    assertThat(replay.unchanged()).isEqualTo(46);
    assertThat(prices.effective(october, "201600446", SettlementPriceType.SETTLE_LINKED).getFirst().getId())
        .isEqualTo(confirmed.getId());
    byte[] edited;
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(original)); var output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(0).getRow(14).createCell(7).setCellValue(0.5);
      workbook.write(output);
      edited = output.toByteArray();
    }
    assertThat(imports.confirm(edited, "补充锌重.xls", october,
        imports.preview(edited, "补充锌重.xls", october).fingerprint(), "finance").revised()).isOne();
    var latest = prices.effective(october, "201600446", SettlementPriceType.SETTLE_LINKED).getFirst();
    assertThat(latest.getZincWeightG()).isEqualByComparingTo("0.5");
    assertThat(latest.getOriginalZincWeightG()).isEqualByComparingTo("0.5");
    assertThat(prices.byId("COMMERCIAL", initial.getId()).orElseThrow().getOriginalZincWeightG()).isNull();
    assertThat(prices.byId("COMMERCIAL", initial.getId()).orElseThrow().getZincWeightG()).isZero();
    assertThat(prices.byId("COMMERCIAL", confirmed.getId()).orElseThrow().getZincWeightG()).isEqualByComparingTo("0.2");
  }

  @Test void blankCopperAndNonNumericZincAreNotCoveredByTheBlankZincRule() throws Exception {
    byte[] original = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    byte[] blankCopper;
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(original)); var output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(0).getRow(14).getCell(6).setBlank();
      workbook.write(output);
      blankCopper = output.toByteArray();
    }
    var book = new SettlementWorkbookParser().parse(blankCopper, "缺铜重.xls");
    var raw = book.rules().stream().filter(r -> r.materialCode().equals("201600446")).findFirst().orElseThrow();
    var candidate = new SettlementRuleRevisionFactory(json).fromImport(raw, book, october, null, "finance");
    assertThat(candidate.isWeightsConfirmed()).isFalse();
    assertThat(candidate.getCopperWeightG()).isNull();
    assertThatThrownBy(() -> new SettlementPriceCalculator().calculate(candidate, null))
        .isInstanceOf(SettlementPriceCalculator.CalculationException.class).hasMessageContaining("请修正原稿后重新导入");
    // 已有可计算版本遇到新原稿铜重缺失，也不能沿用旧重量掩盖缺口。
    imports.confirm(blankCopper, "缺铜重.xls", october,
        imports.preview(blankCopper, "缺铜重.xls", october).fingerprint(), "finance");
    assertThat(reader.resolve(request("201600446", "210")).failureCode()).isEqualTo("SETTLEMENT_WEIGHTS_MISSING");
    var missing = prices.effective(october, "201600446", SettlementPriceType.SETTLE_LINKED).getFirst();
    assertThat(missing.getCopperWeightG()).isNull();
    assertThat(missing.isWeightsConfirmed()).isFalse();
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(original)); var output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(0).getRow(14).createCell(7).setCellValue("待补");
      workbook.write(output);
      assertThatThrownBy(() -> imports.preview(output.toByteArray(), "无效锌重.xls", october)).hasMessageContaining("必须是非负数");
    }
  }

  @Test void newPublicationUpdatesPriceWithoutReimportAndOldCapturedCalculationStillValid() throws Exception {
    SettlementFinanceTestSupport.publish(finance, october, "90", "21.6362912421045");
    var before = reader.resolve(request("203240646", "220"));
    var prepared = new PricePrepareItem();
    prepared.setBusinessUnitType("COMMERCIAL"); prepared.setMaterialCode("203240646"); prepared.setPeriodMonth("2026-10");
    prepared.setPriceOrgCode("220"); prepared.setResultRefId(before.resultRefId()); prepared.setUnitPrice(before.unitPrice());
    prepared.setInputSnapshotJson(before.evidence().inputSnapshotJson());
    var originalRules = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    SettlementFinanceTestSupport.publish(finance, october, "110", "26");
    var after = reader.resolve(request("203240646", "220"));
    assertThat(after.resultRefId()).isEqualTo(before.resultRefId());
    assertThat(after.unitPrice()).isEqualByComparingTo("4.8783");
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(originalRules);
    assertThat(new SettlementCalculationEvidence(json).read(prepared).path("unitPrice").decimalValue()).isEqualByComparingTo("4.7146");
    assertThat(prepared.getInputSnapshotJson()).isEqualTo(before.evidence().inputSnapshotJson());
  }

  @Test void incompletePublishedPairIsReportedAndCannotMixZincFromPreviousRevision() {
    SettlementFinanceTestSupport.publish(finance, october, "90", "21.6362912421045");
    var later = SettlementFinanceTestSupport.publish(finance, october, "110", "26");
    jdbc.update("UPDATE lp_factor_monthly_price p JOIN lp_factor_identity i ON i.id=p.factor_identity_id SET p.status='DRAFT' WHERE p.source_upload_batch_id=? AND i.canonical_factor_key='AVG|1#ZN'", later.batchId());
    var result = reader.resolve(request("203240646", "210"));
    assertThat(result.failureCode()).isEqualTo("SETTLEMENT_FINANCE_BASE_INVALID");
    assertThat(result.unitPrice()).isNull();
    assertThat(result.hasBlockingFailure()).isTrue();
  }

  @Test void identicalFinalReimportPreservesAcceptedWeightsAndRuleIds() throws Exception {
    byte[] bytes = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    var preview = imports.preview(bytes, "最终结算价格.xls", october);
    var before = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    assertThat(imports.confirm(bytes, "最终结算价格.xls", october, preview.fingerprint(), "finance").unchanged()).isEqualTo(46);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(before);
  }

  @Test void commercialUnitAndTaxAreExplicitAndLegacySettlementBaseCannotOverridePublication() {
    var publication = SettlementFinanceTestSupport.publish(finance, october, "90", "21.6362912421045");
    jdbc.update("INSERT INTO lp_settlement_base_price(business_unit_type,price_month,revision_no,copper_price,zinc_price,source_kind,change_reason,created_by) VALUES('COMMERCIAL','2026-10',1,999,999,'FINANCE','历史保留','test')");
    assertThat(reader.resolve(request("203240646", "220")).unitPrice()).isEqualByComparingTo("4.7146");
    jdbc.update("UPDATE lp_factor_monthly_price SET price=price*1000,unit='CNY/TON' WHERE source_upload_batch_id=?", publication.batchId());
    assertThat(reader.resolve(request("203240646", "210")).unitPrice()).isEqualByComparingTo("4.7146");
    jdbc.update("UPDATE lp_factor_monthly_price SET tax_included=0 WHERE source_upload_batch_id=?", publication.batchId());
    assertThat(reader.resolve(request("203240646", "210")).failureCode()).isEqualTo("SETTLEMENT_FINANCE_BASE_INVALID");
    assertThat(jdbc.queryForObject("SELECT copper_price FROM lp_settlement_base_price", BigDecimal.class)).isEqualByComparingTo("999");
  }

  @Test void finalSourceReplacesOlderSampleWeightsAndIdenticalReimportPreservesConfirmedVersion() throws Exception {
    jdbc.update("DELETE FROM lp_settlement_price");
    byte[] old = getClass().getResourceAsStream("/settlement/original-20261017.xls").readAllBytes();
    imports.confirm(old, "旧原稿.xls", october, imports.preview(old, "旧原稿.xls", october).fingerprint(), "finance");
    var prior = prices.effective(october, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst();
    jdbc.update("UPDATE lp_settlement_price SET copper_weight_g=7.8,zinc_weight_g=1.48,weights_confirmed=1,weight_confirmation_note='旧样例',weights_confirmed_by='test',weights_confirmed_at=NOW() WHERE id=?", prior.getId());
    byte[] finalFile = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    imports.confirm(finalFile, "最终原稿.xls", october, imports.preview(finalFile, "最终原稿.xls", october).fingerprint(), "finance");
    var updated = prices.effective(october, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst();
    assertThat(updated.getCopperWeightG()).isEqualByComparingTo("7.6384");
    assertThat(updated.getZincWeightG()).isEqualByComparingTo("0.3306");
    assertThat(prices.byId("COMMERCIAL", prior.getId()).orElseThrow().getCopperWeightG()).isEqualByComparingTo("7.8");
    assertThat(imports.confirm(finalFile, "最终原稿.xls", october, imports.preview(finalFile, "最终原稿.xls", october).fingerprint(), "finance").unchanged()).isEqualTo(46);
    assertThat(prices.effective(october, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst().getId()).isEqualTo(updated.getId());
  }

  private SettlementPriceRequest request(String material, String organization) {
    return new SettlementPriceRequest("COMMERCIAL", material, "2026-10", organization, SettlementPriceType.SETTLE_LINKED);
  }
}
