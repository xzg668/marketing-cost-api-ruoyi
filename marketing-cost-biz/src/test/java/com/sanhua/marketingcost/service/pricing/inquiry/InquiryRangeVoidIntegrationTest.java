package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class InquiryRangeVoidIntegrationTest extends MonthlyInquiryPriceIntegrationTest {
  @Test void bandsVoidReimportKeepOriginalSavedEvidence() throws Exception {
    db.publishFinance("2026-10", "CU_90000");
    String model = "RANGE-" + code();
    var older = db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "1", "2"));
    var current = db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "2", "3"));
    var request = specification("", model, "220", "件");
    var candidate = selected(request);
    var adopted = prices.adopt(context(), new MonthlyAdoptionPlan(Map.of(request, candidate),
        List.of(new Use("root", request, BigDecimal.ONE)), Set.of())).plan().selections().get(request);
    var projection = new MonthlyPriceScenarios(codec, null, null, null, null, null, null, null);
    var pair = projection.calculate(Map.of(request, adopted), new BigDecimal("100000")).get(adopted.monthlyPriceId());
    assertThat(pair.financialPrice()).isEqualByComparingTo("2");
    assertThat(pair.customerPrice()).isEqualByComparingTo("3");
    var voids = new InquiryImportVoidService(db.repository, db.jdbc, codec, mock(TechnicalDataPendingState.class));
    var tx = new TransactionTemplate(db.manager);
    var result = tx.execute(status -> voids.voidBatch("COMMERCIAL", current.batchId(), "finance-test"));
    assertThat(result.retiredMonthlyPrices()).isGreaterThanOrEqualTo(1);
    assertThat(db.repository.matching("COMMERCIAL", "2026-10", Set.of(), Set.of(InquiryNames.normalize(model)))).isEmpty();
    assertThat(prices.resolveCandidates(context(), Set.of(request)).values()).doesNotContainKey(request);
    assertThat(prices.readByIds("COMMERCIAL", Set.of(adopted.monthlyPriceId())).get(adopted.monthlyPriceId()).fact()).isEqualTo(adopted.fact());
    assertThat(projection.calculate(Map.of(request, adopted), new BigDecimal("100000")).get(adopted.monthlyPriceId())).isEqualTo(pair);
    assertThat(tx.execute(status -> voids.voidBatch("COMMERCIAL", current.batchId(), "finance-test")).voidedRows()).isZero();
    assertThatThrownBy(() -> tx.execute(status -> voids.voidBatch("HOUSEHOLD", older.batchId(), "finance-test"))).hasMessageContaining("不存在");
    db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "4", "5"));
    var repaired = prices.resolveCandidates(context(), Set.of(request)).values().get(request).selection();
    assertThat(repaired.fact().price()).isEqualByComparingTo("4");
    var next = prices.adopt(context(), new MonthlyAdoptionPlan(Map.of(request, repaired),
        List.of(new Use("root", request, BigDecimal.ONE)), Set.of())).plan().selections().get(request);
    assertThat(next.monthlyPriceId()).isNotEqualTo(adopted.monthlyPriceId());
    assertThat(projection.calculate(Map.of(request, next), new BigDecimal("100000")).get(next.monthlyPriceId()).customerPrice()).isEqualByComparingTo("5");
    assertThat(db.service.original("COMMERCIAL", current.batchId()).bytes()).isNotEmpty();
  }

  @Test void voidTransactionRollsBackMetadataAndMonthlyRetirementTogether() throws Exception {
    String model = "ROLLBACK-" + code();
    var batch = db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "2", "3"));
    var request = specification("", model, "210", "件");
    var candidate = selected(request);
    var adopted = prices.adopt(context(), new MonthlyAdoptionPlan(Map.of(request, candidate),
        List.of(new Use("root", request, BigDecimal.ONE)), Set.of())).plan().selections().get(request);
    var pending = mock(TechnicalDataPendingState.class);
    when(pending.changed(any())).thenThrow(new IllegalStateException("forced rollback"));
    var voids = new InquiryImportVoidService(db.repository, db.jdbc, codec, pending);
    assertThatThrownBy(() -> new TransactionTemplate(db.manager).execute(status -> voids.voidBatch("COMMERCIAL", batch.batchId(), "finance-test"))).hasMessageContaining("forced rollback");
    assertThat(db.jdbc.queryForObject("SELECT status FROM lp_inquiry_import_batch WHERE id=?",String.class,batch.batchId())).isEqualTo("PUBLISHED");
    assertThat(db.jdbc.queryForObject("SELECT disabled FROM lp_material_monthly_price WHERE id=?",Integer.class,adopted.monthlyPriceId())).isZero();
  }

  @Test void voidOlderFilePreservesLaterImports() throws Exception {
    String model = "LATER-" + code();
    var old = db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "2", "3"));
    var later = db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "4", "5"));
    var voids = new InquiryImportVoidService(db.repository, db.jdbc, codec, mock(TechnicalDataPendingState.class));
    new TransactionTemplate(db.manager).execute(status -> voids.voidBatch("COMMERCIAL", old.batchId(), "finance-test"));
    assertThat(db.repository.current("COMMERCIAL", "2026-10", false)).singleElement().satisfies(value -> assertThat(value.batchId()).isEqualTo(later.batchId()));
    db.refresh.refresh("COMMERCIAL", "2026-10", "TEST", "finance-test");
    assertThat(selected(specification("", model, "210", "件")).fact().price()).isEqualByComparingTo("4");
  }
  @Test void voidRetiresTransitiveMonthlyDependenciesButKeepsTheirSavedFacts() throws Exception {
    String model = "DEPENDENCY-" + code();
    var batch = db.importFile("2026-10", InquiryRangePricingTest.workbook(model, "2", "3"));
    var child = specification("", model, "210", "件");
    var parent = specification("", model + "-PARENT", "210", "件");
    var top = specification("", model + "-TOP", "210", "件");
    var selectedChild = selected(child);
    var evidence = SourceEvidence.ofJson("{\"sourceKind\":\"DEPENDENCY_TEST\"}");
    var parentPrice = new Selection(null, selectedChild.applicability(),
        PriceFact.normal(BigDecimal.TEN, evidence, Set.of(child)));
    var topPrice = new Selection(null, selectedChild.applicability(),
        PriceFact.normal(BigDecimal.valueOf(20), evidence, Set.of(parent)));
    var adopted = prices.adopt(context(), new MonthlyAdoptionPlan(
        Map.of(child, selectedChild, parent, parentPrice, top, topPrice),
        List.of(new Use("root", top, BigDecimal.ONE)), Set.of())).plan().selections();
    var ids = adopted.values().stream().map(Selection::monthlyPriceId).collect(java.util.stream.Collectors.toSet());
    assertThat(ids).hasSize(3);
    var saved = prices.readByIds("COMMERCIAL", ids);
    var voids = new InquiryImportVoidService(db.repository, db.jdbc, codec, mock(TechnicalDataPendingState.class));
    var result = new TransactionTemplate(db.manager).execute(status -> voids.voidBatch("COMMERCIAL", batch.batchId(), "finance-test"));
    assertThat(result.retiredMonthlyPrices()).isEqualTo(3);
    for (long id : ids) {
      assertThat(db.jdbc.queryForObject("SELECT disabled FROM lp_material_monthly_price WHERE id=?", Integer.class, id)).isEqualTo(1);
      assertThat(prices.readByIds("COMMERCIAL", Set.of(id)).get(id).fact()).isEqualTo(saved.get(id).fact());
    }
  }

}
