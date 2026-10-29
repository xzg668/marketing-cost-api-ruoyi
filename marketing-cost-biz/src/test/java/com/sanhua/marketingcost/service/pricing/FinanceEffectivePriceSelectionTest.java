package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class FinanceEffectivePriceSelectionTest {
  final FinanceEffectivePriceService prices = mock(FinanceEffectivePriceService.class);
  final SupplierSupplyRatioMapper ratios = mock(SupplierSupplyRatioMapper.class);
  final SupplierPriceDecisionMapper decisions = mock(SupplierPriceDecisionMapper.class);
  final FinanceEffectivePriceSelectionService service =
      new FinanceEffectivePriceSelectionService(
          prices, new SupplierPriceSelectionService(ratios, decisions));

  @BeforeAll
  static void metadata() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierSupplyRatio.class);
  }

  static Stream<Arguments> policies() {
    return Stream.of("LINKED", "RANGE")
        .flatMap(
            type ->
                Stream.of(
                    Arguments.of(type, "0.7", "0.3", "A", "RATIO_MAX"),
                    Arguments.of(type, "0.5", "0.5", "B", "RATIO_MAX"),
                    Arguments.of(type, "0.65", null, "A", "RATIO_MAX"),
                    Arguments.of(type, "0.4", null, null, "PENDING"),
                    Arguments.of(type, null, null, null, "PENDING"),
                    Arguments.of(type, "0", "0", null, "NO_PRICE")));
  }

  @ParameterizedTest
  @MethodSource("policies")
  void bothPriceTypesUseFixedPricePolicy(
      String type, String a, String b, String expected, String status) {
    var reviews = new SupplierPriceReviewContext(17L);
    var result =
        service.select(
            scope(type),
            List.of(price(type, "A", "10"), price(type, "B", "12")),
            reviews,
            List.of(ratio("A", a), ratio("B", b)));
    assertThat(result.price() == null ? null : result.price().getSupplierCode())
        .isEqualTo(expected);
    assertThat(result.supplier().rule()).isEqualTo(status);
    assertThat(reviews.reviews()).hasSize("PENDING".equals(status) ? 1 : 0);
    if ("PENDING".equals(status)) assertThat(reviews.reviews().getFirst().scope()).isEqualTo(scope(type));
    verifyNoInteractions(prices, ratios);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void singleQuoteUsesPriceWithoutRatioLookup(String type) {
    var result =
        service.select(
            scope(type), List.of(price(type, "A", "10")), new SupplierPriceReviewContext(17L));
    assertThat(result.price().getSupplierCode()).isEqualTo("A");
    assertThat(result.supplier().rule()).isEqualTo("SINGLE");
    verifyNoInteractions(ratios, prices, decisions);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void unquotedSupplierIsNotCandidateButKnownRatioStillLimitsRemainingRatio(String type) {
    var candidates = List.of(price(type, "A", "10"), price(type, "B", "12"));
    var chosen =
        service.select(
            scope(type),
            candidates,
            null,
            List.of(ratio("A", "0.3"), ratio("B", "0.1"), ratio("C", "0.6")));
    assertThat(chosen.price().getSupplierCode()).isEqualTo("A");
    chosen =
        service.select(
            scope(type),
            candidates,
            null,
            List.of(ratio("A", "0.4"), ratio("B", null), ratio("C", "0.5")));
    assertThat(chosen.price().getSupplierCode()).isEqualTo("A");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void confirmedHighestExpiresOnPriceCandidateOrRatioChangeAndWaitingResolvesOnImport(String type) {
    var candidates = List.of(price(type, "A", "10"), price(type, "B", "12"));
    var pending = service.select(scope(type), candidates, null, List.of());
    var decision = new SupplierPriceDecision();
    decision.setDecision("FALLBACK_HIGH");
    decision.setFingerprint(pending.supplier().review().fingerprint());
    when(decisions.latest(anyString())).thenReturn(decision);
    assertThat(service.select(scope(type), candidates, null, List.of()).price().getSupplierCode())
        .isEqualTo("B");
    candidates.getFirst().setUnitPrice(new BigDecimal("13"));
    assertThat(service.select(scope(type), candidates, null, List.of()).price()).isNull();
    candidates.getFirst().setUnitPrice(BigDecimal.TEN);
    candidates.getFirst().setInputFingerprint("new-original-same-price");
    assertThat(service.select(scope(type), candidates, null, List.of()).price()).isNull();
    candidates.getFirst().setInputFingerprint(type + "A");
    assertThat(service.select(scope(type), candidates, null, List.of(ratio("A", "0.2"))).price())
        .isNull();
    decision.setDecision("WAIT_IMPORT");
    assertThat(service.select(scope(type), candidates, null, List.of()).supplier().rule())
        .isEqualTo("WAIT_IMPORT");
    assertThat(
            service
                .select(
                    scope(type), candidates, null, List.of(ratio("A", "0.7"), ratio("B", "0.3")))
                .price()
                .getSupplierCode())
        .isEqualTo("A");
    verifyNoInteractions(prices);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void duplicateRatiosAndAmbiguousRuleIdentitiesAreErrors(String type) {
    var a = price(type, "A", "10");
    var b = price(type, "B", "12");
    assertThatThrownBy(
            () ->
                service.select(
                    scope(type),
                    List.of(a, b),
                    null,
                    List.of(ratio("A", "0.5"), ratio("A", "0.5"))))
        .hasMessageContaining("多条有效供货比率");
    b.setSupplierCode("A");
    b.setSpecification("another specification");
    assertThatThrownBy(() -> service.select(scope(type), List.of(a, b), null, List.of()))
        .hasMessageContaining("必须明确适用规则身份");
    verifyNoInteractions(prices);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void cannotMixScopePublicationConditionOrFuturePrices(String type) {
    var a = price(type, "A", "10");
    var b = price(type, "B", "12");
    b.setOrgCode("220");
    assertThatThrownBy(() -> service.select(scope(type), List.of(a, b), null, List.of()))
        .hasMessageContaining("范围不一致");
    b.setOrgCode("210");
    b.setFinanceBatchId(2L);
    assertThatThrownBy(() -> service.select(scope(type), List.of(a, b), null, List.of()))
        .hasMessageContaining("不同原稿月份或财务方案");
    b.setFinanceBatchId(1L);
    b.setConditionCode("TENDER");
    assertThatThrownBy(() -> service.select(scope(type), List.of(a, b), null, List.of()))
        .hasMessageContaining("不同原稿月份或财务方案");
    b.setConditionCode("GENERAL");
    b.setEffectiveFromMonth("2026-11");
    assertThatThrownBy(() -> service.select(scope(type), List.of(a, b), null, List.of()))
        .hasMessageContaining("范围不一致");
    verifyNoInteractions(prices);
  }

  static SupplierPriceScope scope(String type) {
    return new SupplierPriceScope(
        "COMMERCIAL", "OA-EFP", 17L, "2026-10", LocalDate.of(2026, 10, 14), "210", "MAT", type);
  }

  static FinanceEffectivePrice price(String type, String supplier, String value) {
    var p = new FinanceEffectivePrice();
    p.setId(supplier.equals("A") ? 1L : 2L);
    p.setBusinessUnitType("COMMERCIAL");
    p.setOrgCode("210");
    p.setMaterialCode("MAT");
    p.setPriceType(type);
    p.setOriginalMonth("2026-10");
    p.setEffectiveFromMonth("2026-10");
    p.setFinanceBatchId(1L);
    p.setSchemeCode("CU_90000");
    p.setConditionCode("GENERAL");
    p.setSupplierCode(supplier);
    p.setSupplierName(supplier);
    p.setInputFingerprint(type + supplier);
    p.setUnitPrice(new BigDecimal(value));
    return p;
  }

  static SupplierSupplyRatio ratio(String supplier, String value) {
    var r = new SupplierSupplyRatio();
    r.setSupplierCode(supplier);
    r.setSupplyRatio(value == null ? null : new BigDecimal(value));
    return r;
  }
}
