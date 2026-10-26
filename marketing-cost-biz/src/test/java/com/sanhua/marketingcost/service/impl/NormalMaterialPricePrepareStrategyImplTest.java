package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NormalMaterialPricePrepareStrategyImplTest {
  private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 10, 25, 10, 0);
  private final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  private MonthlyQuotationSessions sessions;
  private QuotationPriceSession session;
  private NormalMaterialPricePrepareStrategyImpl strategy;
  private final Map<String, String> prices = new HashMap<>();
  private final Map<String, String> customerPrices = new HashMap<>();

  @BeforeEach void setUp() {
    sessions = mock(MonthlyQuotationSessions.class, org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    session = mock(QuotationPriceSession.class);
    strategy = new NormalMaterialPricePrepareStrategyImpl(sessions, codec);
    when(sessions.open(any(), any(), any(), any(), any())).thenReturn(session);
    when(session.conditions(any())).thenReturn(NonCuConditions.empty());
    when(session.read(any())).thenAnswer(call -> {
      var values = new LinkedHashMap<QuotationPriceSession.Input, QuotationPriceSession.Value>();
      var problems = new LinkedHashMap<QuotationPriceSession.Input, MonthlyMaterialPrices.Problem>();
      for (var input : (Collection<QuotationPriceSession.Input>) call.getArgument(0)) {
        if (!prices.containsKey(input.materialCode())) {
          problems.put(input, new MonthlyMaterialPrices.Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING, "没有适用来源"));
          continue;
        }
        var price = new BigDecimal(prices.get(input.materialCode()));
        var customer = new BigDecimal(customerPrices.getOrDefault(input.materialCode(), price.toPlainString()));
        var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
            input.materialCode(), input.specification(), input.unit(), "CNY", TaxBasis.EXCLUDED, null),
            input.organization(), input.conditions(), input.basisQuantity());
        var fact = MonthlyAdoptionPlan.PriceFact.normal(price,
            MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"PURCHASE_FIXED\"}"), Set.of());
        var selection = new MonthlyAdoptionPlan.Selection(71L,
            new MonthlyPriceCoverage(Set.of(input.organization()), QuantityInterval.all(), ConditionDomain.any()), fact);
        values.put(input, new QuotationPriceSession.Value(request, selection, price, customer));
      }
      return new QuotationPriceSession.Read(values, problems);
    });
  }

  @Test void preservesCapturedPricePrecisionAndActualQuantity() {
    prices.put("MAT", "12.3000009");
    var item = item("MAT", "2.5", "/root/material");
    var result = strategy.prepare("OA", "COMMERCIAL", "2026-10", AS_OF, item);
    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("12.3000009");
    assertThat(result.getAmount()).isEqualByComparingTo("30.75000225");
    assertThat(result.getResultRefType()).isEqualTo(MonthlyPreparationPrice.KIND);
    assertThat(result.getResultRefId()).isEqualTo(71L);
    var saved = codec.decode(result.getInputSnapshotJson(), MonthlyPreparationPrice.class);
    assertThat(saved.quantity()).isEqualByComparingTo("2.5");
    assertThat(saved.selection().monthlyPriceId()).isEqualTo(71L);
    assertThat(saved.request().family().unit()).isEqualTo("件");
    verify(session).consume(eq("root/material"), any(QuotationPriceSession.Input.class), eq(new BigDecimal("2.5")));
    var scenario = ArgumentCaptor.forClass(PricePrepareScenarioContext.class);
    verify(sessions).open(eq("OA"), eq("COMMERCIAL"), eq("2026-10"), eq(AS_OF), scenario.capture());
    assertThat(scenario.getValue().supplierReviews().oaFormItemId()).isEqualTo(11L);
  }

  @Test void financeAndCustomerUseTheSameCapturedSelectionWithDifferentAmounts() {
    prices.put("MAT", "10"); customerPrices.put("MAT", "13");
    var item = item("MAT", "2", "/root/material");
    var customer = strategy.calculate("OA", "COMMERCIAL", "2026-10", AS_OF, null, item);
    var finance = strategy.calculate("OA", "COMMERCIAL", "2026-10", AS_OF,
        new PricePrepareScenarioContext(QuotePriceScenarioType.FINANCE_QUOTE_BASE, "GROUP", "SOURCE", Map.of()), item);
    assertThat(customer.getAmount()).isEqualByComparingTo("26");
    assertThat(finance.getAmount()).isEqualByComparingTo("20");
    assertThat(codec.decode(customer.getInputSnapshotJson(), MonthlyPreparationPrice.class).selection())
        .isEqualTo(codec.decode(finance.getInputSnapshotJson(), MonthlyPreparationPrice.class).selection());
  }

  @Test void batchReadsOnceAndKeepsOrganizationQuantityAndOccurrenceSeparate() {
    prices.put("MAT", "3");
    var first = item("MAT", "2", "/root/a");
    var second = item("MAT", "4", "/root/b"); second.getBomRow().setPriceOrgCode("220");
    var result = strategy.prepareBatch("OA", "COMMERCIAL", "2026-10", AS_OF, null, List.of(first, second), true);
    assertThat(result.get(first).getAmount()).isEqualByComparingTo("6");
    assertThat(result.get(second).getAmount()).isEqualByComparingTo("12");
    verify(session, times(1)).read(any());
    verify(session).consume(eq("root/a"), argThat((QuotationPriceSession.Input i) -> "210".equals(i.organization())), eq(new BigDecimal("2")));
    verify(session).consume(eq("root/b"), argThat((QuotationPriceSession.Input i) -> "220".equals(i.organization())), eq(new BigDecimal("4")));
  }

  @Test void missingAndMissingQuantityRemainGapsAndDoNotConsume() {
    var missing = item("MISSING", "1", "/root/missing");
    var invalid = item("INVALID", null, "/root/invalid");
    var results = strategy.prepareBatch("OA", "COMMERCIAL", "2026-10", AS_OF, null, List.of(missing, invalid), false);
    assertThat(results.get(missing).getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(results.get(missing).getReasonCode()).isEqualTo("MISSING");
    assertThat(results.get(missing).getMessage()).isEqualTo("没有适用来源");
    assertThat(results.get(invalid).getStatus()).isEqualTo("FAILED");
    assertThat(results.get(invalid).getReasonCode()).isEqualTo("INVALID_INPUT");
    verify(session, never()).consume(any(), any(QuotationPriceSession.Input.class), any());
  }

  @Test void zeroQuantityDoesNotRequireOrConsumeMonthlyPrice() {
    var result = strategy.prepare("OA", "COMMERCIAL", "2026-10", AS_OF, item("MISSING", "0", "/root/zero"));
    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getAmount()).isEqualByComparingTo("0");
    assertThat(result.getResultRefType()).isEqualTo("ZERO_USAGE");
    verify(session).read(argThat(Collection::isEmpty));
    verify(session, never()).consume(any(), any(QuotationPriceSession.Input.class), any());
  }

  @Test void byproductUsesAbsoluteScrapBasisButRetainsNegativeAmount() {
    prices.put("SCRAP", "10.177");
    var item = item("SCRAP", "-0.25", "/root/scrap");
    item.getBomRow().setSettlementRowType("BYPRODUCT_EXTRA");
    var result = strategy.calculate("OA", "COMMERCIAL", "2026-10", AS_OF, null, item);
    assertThat(result.getAmount()).isEqualByComparingTo("-2.54425");
    var evidence = codec.decode(result.getInputSnapshotJson(), MonthlyPreparationPrice.class);
    assertThat(evidence.quantity()).isEqualByComparingTo("-0.25");
    assertThat(evidence.request().priceBasisQuantity()).isEqualByComparingTo("0.25");
    verify(session).consume(eq("root/scrap"), argThat((QuotationPriceSession.Input i) ->
        i.purpose() == QuotationPriceSession.Purpose.SCRAP && i.taxBasis() == TaxBasis.EXCLUDED && "KG".equals(i.unit())), eq(new BigDecimal("0.25")));
  }

  @Test void monthlyDependenciesAreRequiredAndSessionFailureIsNotHidden() {
    assertThatThrownBy(() -> new NormalMaterialPricePrepareStrategyImpl(null, codec)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new NormalMaterialPricePrepareStrategyImpl(sessions, null)).isInstanceOf(NullPointerException.class);
    var failure = new IllegalStateException("月价依据不可用");
    doThrow(failure).when(session).read(any());
    assertThatThrownBy(() -> strategy.prepare("OA", "COMMERCIAL", "2026-10", AS_OF, item("MAT", "1", "/root/a")))
        .isSameAs(failure);
  }

  private PricePreparePlanItem item(String code, String quantity, String path) {
    var row = new BomCostingRow(); row.setOaFormItemId(11L); row.setPriceOrgCode("210");
    row.setMaterialCode(code); row.setUnit("件"); row.setMaterialSpec("SPEC"); row.setPath(path);
    row.setQtyPerTop(quantity == null ? null : new BigDecimal(quantity)); row.setSettlementRowType("NORMAL");
    var item = new PricePreparePlanItem(); item.setBomRow(row); item.setMaterialCode(code); return item;
  }
}
