package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
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

  @ParameterizedTest
  @CsvSource({"0.7226,0.722600", "0,0.0000"})
  void equalEffectivePricesNeedNeitherRatioQueriesNorApproval(String first, String second) {
    var a = candidate("A", first); var b = candidate("B", second);
    var result = service.select(scope, List.of(a, b));
    assertThat(result.candidate()).isEqualTo(b);
    assertThat(result.rule()).isEqualTo("SAME_PRICE");
    assertThat(result.ratio()).isNull();
    assertThat(result.review()).isNull();
    verifyNoInteractions(ratios, decisions);
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
  void equalPricesDoNotRequireCompleteOrUniqueRatioInputs() {
    var a = ratio("A", "0.1"); var b = ratio("B", null);
    var result = service.select(scope, List.of(candidate("A", "0.45"), candidate("B", "0.450")),
        List.of(a, a, b));
    assertThat(result.candidate()).isNotNull();
    assertThat(result.review()).isNull();
    assertThat(result.rule()).isEqualTo("SAME_PRICE");
    verifyNoInteractions(ratios, decisions);
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

  private SupplierSupplyRatio ratio(String supplier, String value) {
    var ratio = new SupplierSupplyRatio(); ratio.setSupplierCode(supplier);
    ratio.setSupplyRatio(value == null ? null : new BigDecimal(value));
    return ratio;
  }
}
