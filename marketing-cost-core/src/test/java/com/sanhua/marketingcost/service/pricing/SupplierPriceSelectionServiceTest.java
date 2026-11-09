package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.SupplierPriceDecision;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SupplierPriceSelectionServiceTest {
  private final SupplierSupplyRatioMapper ratios = mock(SupplierSupplyRatioMapper.class);
  private final SupplierPriceDecisionMapper decisions = mock(SupplierPriceDecisionMapper.class);
  private final SupplierPriceSelectionService service = new SupplierPriceSelectionService(ratios, decisions);
  private final SupplierPriceScope scope = new SupplierPriceScope("COMMERCIAL", "SAME-PRICE", 1L,
      "2026-10", LocalDate.of(2026, 10, 26), "210", "201350006", "FIXED");

  @Test void configuredHighestUsesBothDirectAndPreparedSelectionWithoutManualDecision() {
    var configured = ratio("A", "0.1");
    configured.setSelectionRule("无供货比率，取最高价");
    var candidates = List.of(candidate("A", "10"), candidate("B", "20"), candidate("C", "99"));
    var active = List.of(configured, ratio("C", "0"));
    when(ratios.selectList(any())).thenReturn(active);
    for (var result : List.of(service.select(scope, candidates), service.selectPrepared(scope, candidates, active, null))) {
      assertThat(result.candidate()).isNotNull();
      assertThat(result.candidate().supplierCode()).isEqualTo("B");
      assertThat(result.rule()).isEqualTo("CONFIGURED_HIGH");
      assertThat(result.review()).isNull();
      assertThat(result.message()).contains("配置");
    }
    verifyNoInteractions(decisions);
  }

  @Test void configuredHighestStillPrefersDecisiveRatioAndDoesNotInterpretUnrelatedNotes() {
    var a = ratio("A", "0.8"); a.setSelectionRule("无供货比例取最高价");
    var candidates = List.of(candidate("A", "10"), candidate("B", "20"));
    assertThat(service.selectPrepared(scope,candidates,List.of(a),null).candidate().supplierCode()).isEqualTo("A");
    a.setSupplyRatio(null); a.setSelectionRule("未确认是否取最高价");
    assertThat(service.selectPrepared(scope,candidates,List.of(a),null).candidate()).isNull();
    a.setSelectionRule("无供货比例取最高价");
    var waiting = new SupplierPriceDecision(); waiting.setScopeKey(SupplierPriceSelectionService.scopeKey(scope)); waiting.setDecision("WAIT_IMPORT");
    assertThat(service.selectPrepared(scope,candidates,List.of(a),waiting).rule()).isEqualTo("CONFIGURED_HIGH");
  }

  @Test void parallelRulesTakeHighestWithinSupplierBeforeExistingSupplierRatioPolicy() {
    var low = new SupplierPriceCandidate("A-low", "A", "供应商A", new BigDecimal("10"), null, null);
    var high = new SupplierPriceCandidate("A-high", "A", "供应商A", new BigDecimal("15"), null, null);
    var single = service.selectPrepared(scope, List.of(high, low), List.of(), null);
    assertThat(single.candidate()).isEqualTo(high);
    assertThat(single.review()).isNull();
    assertThat(single.rule()).isEqualTo("SAME_SUPPLIER_HIGH");
    var multi = service.selectPrepared(scope, List.of(low, candidate("B", "20"), high),
        List.of(ratio("A", ".8"), ratio("B", ".2")), null);
    assertThat(multi.candidate()).isEqualTo(high);
    assertThat(multi.rule()).isEqualTo("RATIO_MAX");
  }

  @Test void absentSupplierOnPublicScrapDoesNotRequireInventingASupplier() {
    var publicPrice = new SupplierPriceCandidate("PUBLIC-SCRAP", null, null, BigDecimal.ZERO, null, null);
    assertThat(service.selectPrepared(scope, List.of(publicPrice), List.of(), null).candidate()).isEqualTo(publicPrice);
  }

  @Test void absentMainSupplierContinuesToTheNextSourceEvenWithOneOrEqualPrices() {
    var main=List.of(ratio("MAIN","1"));
    for(var candidates:List.of(List.of(candidate("OTHER","1057.5221")),
        List.of(candidate("OTHER","10"),candidate("ANOTHER","10")))) {
      var selected=service.selectPrepared(scope,candidates,main,null);
      assertThat(selected.candidate()).isNull();
      assertThat(selected.rule()).isEqualTo(SupplierPriceSelectionService.NO_PRIMARY_SUPPLIER_PRICE);
      assertThat(selected.review()).isNull();
    }
    var order=service.selectPrepared(scope,List.of(candidate("MAIN","1094.6903"),candidate("OTHER","9999")),main,null);
    assertThat(order.candidate().supplierCode()).isEqualTo("MAIN");
    assertThat(order.rule()).isEqualTo("RATIO_MAX");
  }

  @ParameterizedTest
  @CsvSource({"0.7226,0.722600", "0,0.0000"})
  void equalEffectivePricesStillCheckForAConfiguredMainSupplier(String first, String second) {
    var a = candidate("A", first); var b = candidate("B", second);
    var result = service.select(scope, List.of(a, b));
    assertThat(result.candidate()).isEqualTo(b);
    assertThat(result.rule()).isEqualTo("SAME_PRICE");
    assertThat(result.ratio()).isNull();
    assertThat(result.review()).isNull();
    verify(ratios).selectList(any()); verifyNoInteractions(decisions);
    assertThat(service.select(scope, List.of(b, a)).candidate()).isEqualTo(b);
  }

  @Test
  void preparedMonthlySelectionAlsoIgnoresAnOldWaitingDecisionWhenPricesAreNowEqual() {
    var decision = new SupplierPriceDecision();
    decision.setScopeKey(SupplierPriceSelectionService.scopeKey(scope));
    decision.setDecision("WAIT_IMPORT");
    var candidates = List.of(candidate("A", "4.7719"), candidate("B", "4.771900"));
    var result = service.selectPrepared(scope, candidates, List.of(), decision);
    assertThat(result.candidate().unitPrice()).isEqualByComparingTo("4.7719");
    assertThat(result.review()).isNull();
    assertThat(result.rule()).isEqualTo("SAME_PRICE");
    verifyNoInteractions(ratios, decisions);
  }

  @Test
  void differentPricesCannotHideAmbiguousCurrentRelationships() {
    var a = ratio("A", "0.1");
    assertThatThrownBy(() -> service.select(scope,
        List.of(candidate("A", "0.45"), candidate("B", "0.46")), List.of(a, a)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("多条有效供货比率");
  }

  @Test void equalLinkedPricesIgnoreZeroRatiosForBothSuppliers() {
    var selected = service.selectPrepared(scope,
        List.of(candidate("S000109", "0.667610619469026549"), candidate("S002874", "0.667610619469026549")),
        List.of(ratio("S000109", "0"), ratio("S002874", "0")), null);
    assertThat(selected.rule()).isEqualTo("SAME_PRICE");
    assertThat(selected.candidate().unitPrice()).isEqualByComparingTo("0.667610619469026549");
    assertThat(selected.review()).isNull();
  }

  @Test void aUniqueQuotationIgnoresRatiosWhileMultiplePricesUseTheirSupplierRelations() {
    var zero = ratio("B", "0");
    assertThat(service.selectPrepared(scope, List.of(candidate("B", "99")), List.of(zero), null).candidate().supplierCode()).isEqualTo("B");
    assertThat(service.selectPrepared(scope, List.of(candidate("A", "10"), candidate("B", "10")), List.of(zero), null).candidate().supplierCode()).isEqualTo("B");
    var candidates = List.of(candidate("A", "10"), candidate("B", "99"), candidate("C", "20"));
    var missing = service.selectPrepared(scope, candidates, List.of(zero), null);
    assertThat(missing.review().candidates()).extracting(c -> c.price().supplierCode()).containsExactly("A", "C");
    var decision = new SupplierPriceDecision();decision.setScopeKey(SupplierPriceSelectionService.scopeKey(scope));
    decision.setDecision("FALLBACK_HIGH");decision.setFingerprint(missing.review().fingerprint());
    assertThat(service.selectPrepared(scope, candidates, List.of(zero), decision).candidate().supplierCode()).isEqualTo("C");
    zero.setSupplyRatio(null);
    assertThat(service.selectPrepared(scope, candidates, List.of(zero), decision).candidate()).isNull();
  }

  @Test void singleSupplierReadsRatiosAndSupplementalPackagingFallbackDoesNotChangeOrdinaryPolicy() {
    assertThat(service.select(scope, List.of(candidate("A", "0.84956"))).candidate().unitPrice())
        .isEqualByComparingTo("0.84956");
    verify(ratios).selectList(any()); verifyNoInteractions(decisions);
    var candidates = List.of(candidate("A", "10"), candidate("B", "20"));
    assertThat(service.selectPrepared(scope, candidates, List.of(), null).candidate()).isNull();
    var packaging = service.selectPrepared(scope, candidates, List.of(), null, true);
    assertThat(packaging.candidate().supplierCode()).isEqualTo("B");
    assertThat(packaging.rule()).isEqualTo("PACKAGE_AUTO_HIGH");
    assertThat(packaging.review()).isNull();
    assertThat(service.selectPrepared(scope, candidates, List.of(ratio("A", ".8"), ratio("B", ".2")), null, true)
        .candidate().supplierCode()).isEqualTo("A");
    assertThat(service.selectPrepared(scope, candidates, List.of(), null).candidate()).isNull();
  }

  @Test
  void genuinelyDifferentPricesStillWaitOrUseTheExistingDecisiveRatio() {
    var candidates = List.of(candidate("A", "1.148319"), candidate("B", "1.078"));
    var missing = service.selectPrepared(scope, candidates, List.of(), null);
    assertThat(missing.candidate()).isNull();
    assertThat(missing.review().status()).isEqualTo("PENDING");
    var selected = service.selectPrepared(scope, candidates,
        List.of(ratio("A", "0.8"), ratio("B", "0.2")), null);
    assertThat(selected.candidate().supplierCode()).isEqualTo("A");
    assertThat(selected.rule()).isEqualTo("RATIO_MAX");
  }

  @Test
  void differencesAreComparedWithoutRoundingAndMissingValuesAreNotEqualPrices() {
    for (var candidates : List.of(
        List.of(candidate("A", "0.722600001"), candidate("B", "0.722600002")),
        List.of(candidate("A", null), candidate("B", null)),
        List.of(candidate("A", "0"), candidate("B", null)))) {
      var result = service.selectPrepared(scope, candidates, List.of(), null);
      assertThat(result.candidate()).isNull();
      assertThat(result.review()).isNotNull();
    }
  }

  private SupplierPriceCandidate candidate(String supplier, String price) {
    return new SupplierPriceCandidate(supplier, supplier, supplier,
        price == null ? null : new BigDecimal(price), null, null);
  }

  @Test void nameOnlyInquirySuppliersCanBeReviewedAndConfirmedWithAStableFingerprint() {
    var a=new SupplierPriceCandidate("A",null,"甲方",new BigDecimal("10"),null,null);
    var b=new SupplierPriceCandidate("B",null,"乙方",new BigDecimal("20"),null,null);
    var first=service.selectPrepared(scope,List.of(a,b),List.of(),null);
    assertThat(first.review().status()).isEqualTo("PENDING");
    assertThat(service.selectPrepared(scope,List.of(b,a),List.of(),null).review().fingerprint()).isEqualTo(first.review().fingerprint());
    var decision=new SupplierPriceDecision();decision.setScopeKey(SupplierPriceSelectionService.scopeKey(scope));
    decision.setDecision("FALLBACK_HIGH");decision.setFingerprint(first.review().fingerprint());
    assertThat(service.selectPrepared(scope,List.of(a,b),List.of(),decision).candidate()).isEqualTo(b);
    var changed=new SupplierPriceCandidate("B",null,"另一供方",new BigDecimal("20"),null,null);
    assertThat(service.selectPrepared(scope,List.of(a,changed),List.of(),decision).candidate()).isNull();
  }

  private SupplierSupplyRatio ratio(String supplier, String value) {
    var ratio = new SupplierSupplyRatio(); ratio.setSupplierCode(supplier);
    ratio.setSupplyRatio(value == null ? null : new BigDecimal(value));
    return ratio;
  }
}
