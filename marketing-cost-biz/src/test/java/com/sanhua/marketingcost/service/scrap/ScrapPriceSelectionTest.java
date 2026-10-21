package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScrapPriceSelectionTest extends ScrapPriceTestFixture {
  @Test
  void formulaApprovalBelongsToCurrentProductAndIsSharedByBothPriceScenarios() {
    load(List.of(row("S1", "甲", null, "90"), row("S2", "乙", "95", null)));
    var reviews = new SupplierPriceReviewContext(171L).forOrganization("220");
    var date = LocalDate.of(2026, 10, 19);
    assertThatThrownBy(
            () ->
                service.selectForFormula(
                    "COMMERCIAL", "2026-10", "scrap", "220", "OA", date, true, reviews))
        .hasMessageContaining("供货比率不足");
    var pending = reviews.reviews().getFirst();
    assertThat(pending.scope().oaFormItemId()).isEqualTo(171L);
    assertThat(pending.scope().orgCode()).isEqualTo("220");
    assertThat(pending.scope().pricingDate()).isEqualTo(date);
    assertThat(pending.scope().priceType()).isEqualTo("SCRAP_PRICE");

    var approval = new SupplierPriceDecision();
    approval.setDecision("FALLBACK_HIGH");
    approval.setFingerprint(pending.fingerprint());
    when(decisions.latest(pending.scopeKey())).thenReturn(approval);
    var form = new OaForm();
    form.setCopperPrice(new BigDecimal("120000"));
    when(forms.selectOne(any())).thenReturn(form);
    for (boolean finance : List.of(true, false)) {
      var result =
          service.selectForFormula(
              "COMMERCIAL", "2026-10", "scrap", "220", "OA", date, finance, reviews);
      // 客户 Cu 提升后乙的计算价会高于甲，选商仍遵循已确认的公共价候选。
      assertThat(result.evidence().supplierCode()).isEqualTo("S1");
      assertThat(result.unitPrice()).isEqualByComparingTo("90");
      assertThat(reviews.reviews()).hasSize(1);
      assertThat(reviews.reviews().getFirst().status()).isEqualTo("FALLBACK_HIGH");
    }
    var otherProduct = new SupplierPriceReviewContext(189L).forOrganization("220");
    assertThatThrownBy(
            () ->
                service.selectForFormula(
                    "COMMERCIAL", "2026-10", "scrap", "220", "OA", date, true, otherProduct))
        .hasMessageContaining("供货比率不足");
    assertThat(otherProduct.reviews().getFirst().scopeKey()).isNotEqualTo(pending.scopeKey());
    assertThatThrownBy(
            () ->
                service.selectForFormula(
                    "COMMERCIAL", "2026-10", "scrap", "220", "OA", date.plusDays(1), true, reviews))
        .hasMessageContaining("供货比率不足");
  }

  @Test
  void standaloneFormulaPreviewCannotBorrowAProductsSupplierApproval() {
    load(List.of(row("S1", "甲", null, "8"), row("S2", "乙", null, "9")));
    assertThatThrownBy(
            () ->
                service.selectForFormula(
                    "COMMERCIAL",
                    "2026-10",
                    "scrap",
                    "220",
                    "OA",
                    LocalDate.of(2026, 10, 19),
                    true,
                    null))
        .hasMessageContaining("供货比率不足");
    verifyNoInteractions(decisions);
  }

  @Test
  void pairUsesItsOwnCommercialBasisAndOnlyAllowsTheCustomerCopperDifference() {
    load(List.of(row(null, null, "95", null)));
    var form = new OaForm();
    form.setCopperPrice(new BigDecimal("110000"));
    when(forms.selectOne(any())).thenReturn(form);
    var base = select("220", finance(), false);
    var customer = select("220", PricePrepareScenarioContext.oaLocked(7L, "220"), false);
    assertThat(
            evidence.validatePair(
                "scrap",
                customer.evidence().inputSnapshotJson(),
                base.evidence().inputSnapshotJson(),
                customer.unitPrice(),
                base.unitPrice(),
                new BigDecimal("110")))
        .isTrue();
    assertThatThrownBy(
            () ->
                evidence.validatePair(
                    "scrap",
                    customer.evidence().inputSnapshotJson(),
                    base.evidence().inputSnapshotJson(),
                    customer.unitPrice(),
                    base.unitPrice(),
                    new BigDecimal("111")))
        .hasMessageContaining("客户Cu");
    load(List.of(row(null, null, "96", null)));
    var changed = select("220", finance(), false);
    assertThatThrownBy(
            () ->
                evidence.validatePair(
                    "scrap",
                    customer.evidence().inputSnapshotJson(),
                    changed.evidence().inputSnapshotJson(),
                    customer.unitPrice(),
                    changed.unitPrice(),
                    new BigDecimal("110")))
        .hasMessageContaining("依据不一致");
  }

  @Test
  void consumerOrganizationsShareCommercialPublicPriceAndKeepSeparateEvidence() {
    load(List.of(row(null, null, "90", null)));
    var commercial = select("210", finance(), false);
    var board = select("220", finance(), false);
    assertThat(board.unitPrice()).isEqualByComparingTo(commercial.unitPrice());
    assertThat(board.unitPrice()).isEqualByComparingTo(expected("90"));
    var saved = evidence.read(board.evidence().inputSnapshotJson());
    assertThat(saved.consumerOrgCode()).isEqualTo("220");
    assertThat(saved.context().financial().scope().priceOrgCode()).isEqualTo("210");
    assertThat(saved.supplier().code()).isNull();
    verifyNoInteractions(ratios, decisions, forms);
  }

  @Test
  void customerCopperChangesOnlyQuoteSnapshotAndDoesNotWritePublicPrice() {
    var row = row(null, null, "95", null);
    String before = row.getCalculationJson();
    load(List.of(row));
    var form = new OaForm();
    form.setCopperPrice(new BigDecimal("110000"));
    when(forms.selectOne(any())).thenReturn(form);
    var base = select("220", finance(), false);
    var customer = select("220", PricePrepareScenarioContext.oaLocked(7L, "220"), false);
    assertThat(base.unitPrice()).isEqualByComparingTo(expected("95"));
    assertThat(customer.unitPrice()).isEqualByComparingTo(expected("110"));
    assertThat(row.getUnitPrice()).isEqualByComparingTo(expected("95"));
    assertThat(row.getCalculationJson()).isEqualTo(before);
    var frozen = evidence.read(customer.evidence().inputSnapshotJson());
    assertThat(frozen.context().customerCopperPricePerTon()).isEqualByComparingTo("110000");
    assertThat(frozen.context().financial().factors().get("AVG|1#CU").price())
        .isEqualByComparingTo("95");
  }

  @Test
  void fixedZeroNeedsNeitherFinancialPublicationNorCustomerCopper() {
    load(List.of(row(null, null, null, "0")));
    var result = select("220", PricePrepareScenarioContext.oaLocked(7L, "220"), true);
    assertThat(result.unitPrice()).isEqualByComparingTo("0");
    assertThat(evidence.read(result.evidence().inputSnapshotJson()).context()).isNull();
    verifyNoInteractions(forms);
  }

  @Test
  void nameOnlySingleSupplierIsValidButUnidentifiedMultipleCandidatesAreConflicts() {
    load(List.of(row(null, "回收商", null, "8")));
    assertThat(select("210", finance(), false).evidence().supplierName()).isEqualTo("回收商");
    load(List.of(row(null, null, null, "8"), row("S1", "回收商", null, "9")));
    assertThatThrownBy(() -> select("210", finance(), false)).hasMessageContaining("身份不明确");
    load(List.of(row(null, "甲", null, "8"), row(null, "乙", null, "9")));
    assertThatThrownBy(() -> select("210", finance(), false)).hasMessageContaining("身份不明确");
  }

  @Test
  void codedSuppliersUseExistingRatioPolicyAndDoNotPickTheFirstOrHighestArbitrarily() {
    load(List.of(row("S1", "甲", null, "8"), row("S2", "乙", null, "9")));
    var ratio = new SupplierSupplyRatio();
    ratio.setId(41L);
    ratio.setSupplierCode("S1");
    ratio.setSupplyRatio(new BigDecimal("0.8"));
    when(ratios.selectList(any())).thenReturn(List.of(ratio));
    var result = select("220", finance(), false);
    assertThat(result.unitPrice()).isEqualByComparingTo("8");
    assertThat(result.evidence().supplyRatioRecordId()).isEqualTo(41L);
    assertThat(evidence.read(result.evidence().inputSnapshotJson()).supplier().selectionRule())
        .isEqualTo("RATIO_MAX");
  }

  @Test
  void missingRatiosRemainExplicitReviewAndCannotFallThroughToOrdinaryPrice() {
    load(List.of(row("S1", "甲", null, "8"), row("S2", "乙", null, "9")));
    when(ratios.selectList(any())).thenReturn(List.of());
    var scenario = finance();
    assertThatThrownBy(() -> select("220", scenario, false)).hasMessageContaining("供货比率不足");
    assertThat(scenario.supplierReviews().reviews()).hasSize(1);
  }

  @Test
  void futureRulesAndMissingBasesBlockScrapWhileUnknownOrdinaryMaterialIsNotIntercepted() {
    when(scopes.read(anyString(), anyString(), anyBoolean()))
        .thenReturn(new ScrapPriceScopeReader.Scope(Set.of("scrap"), Map.of()));
    assertThatThrownBy(() -> select("210", finance(), false)).hasMessageContaining("没有适用价格规则");
    assertThat(
            service.select(
                "COMMERCIAL",
                "2026-10",
                "ordinary",
                "210",
                "OA",
                LocalDate.now(),
                finance(),
                false,
                false))
        .isEmpty();
    assertThatThrownBy(
            () ->
                service.select(
                    "COMMERCIAL",
                    "2026-10",
                    "unknown",
                    "210",
                    "OA",
                    LocalDate.now(),
                    finance(),
                    false,
                    true))
        .hasMessageContaining("没有适用价格规则");
    var waiting = row(null, null, "90", null);
    waiting.setCalcStatus("WAITING_BASE");
    waiting.setUnitPrice(null);
    waiting.setCalcMessage("待基价");
    load(List.of(waiting));
    assertThatThrownBy(() -> select("210", finance(), false)).hasMessageContaining("待基价");
  }

  @Test
  void frozenSnapshotReplaysWithoutMutableRowsAndRejectsTamperedInputsAndAmount() throws Exception {
    var row = row(null, null, "90", null);
    load(List.of(row));
    String captured = select("220", finance(), false).evidence().inputSnapshotJson();
    row.setUnitPrice(new BigDecimal("999"));
    row.setExpression("[Cu]*999");
    reset(scopes, forms);
    assertThat(evidence.read(captured).unitPrice()).isEqualByComparingTo(expected("90"));
    verifyNoInteractions(scopes, forms);
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(captured);
    tree.put("unitPrice", "999");
    String wrongAmount = tree.toString();
    assertThatThrownBy(() -> evidence.read(wrongAmount)).hasMessageContaining("金额不一致");
    tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(captured);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("inputs")).put("Cu", "95");
    String tampered = tree.toString();
    assertThatThrownBy(() -> evidence.read(tampered)).hasMessageContaining("金额不一致");
  }
}
