package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-26 百产品百缺价统一提交门禁性能")
class TechnicalTaskValidationPerformanceTest {
  @Test
  void validatesOneHundredProductsAndTenThousandResolvedGapsWithoutDegradation() {
    TechnicalTaskValidator validator = new TechnicalTaskValidator(
        mock(QuotePriceDraftRepository.class));
    List<QuoteCollaborationGap> gaps = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      QuoteCollaborationGap gap = new QuoteCollaborationGap();
      gap.setGapCategory("PRICE");
      gap.setGapStatus("RESOLVED");
      gap.setMaterialCode("MAT-" + index);
      gaps.add(gap);
    }
    long started = System.nanoTime();
    for (int product = 0; product < 100; product++) {
      QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
      task.setId((long) product + 1);
      task.setNeedBom(0);
      task.setNeedPackage(0);
      task.setNeedPrice(1);
      task.setBusinessUnitType("COMMERCIAL");
      task.setApplicableOrgCode("210");
      assertThat(validator.validate(task, gaps)).isEmpty();
    }
    double elapsed = (System.nanoTime() - started) / 1_000_000D;
    System.out.printf(Locale.ROOT,
        "QCBP26_PERF operation=technical_submit_validation products=100 gaps_per_product=100 total_gap_checks=10000 elapsed_ms=%.3f%n",
        elapsed);
    assertThat(elapsed).isLessThan(2_000D);
  }
}
