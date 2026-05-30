package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunTaskBatchPressurePlanTest {

  @Test
  void buildsFortyThousandMonthlyRepriceModel() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 27, 0, 0);
    List<CostRunTask> tasks = new ArrayList<>(40_000);
    for (int oaIndex = 1; oaIndex <= 200; oaIndex++) {
      for (int productIndex = 1; productIndex <= 200; productIndex++) {
        tasks.add(monthlyTask(
            (long) tasks.size() + 1,
            "BATCH-MRP-202605",
            "MRP-202605",
            "OA-" + String.format("%03d", oaIndex),
            "P-" + String.format("%03d-%03d", oaIndex, productIndex),
            "2026-05",
            priceAsOfTime,
            8801L));
      }
    }

    CostRunTaskBatchPressurePlan plan = CostRunTaskBatchPressurePlan.from(tasks);

    assertThat(plan.taskCount()).isEqualTo(40_000);
    assertThat(plan.sceneCount()).isEqualTo(1);
    assertThat(plan.sourceCount()).isEqualTo(1);
    assertThat(plan.oaGroupCount()).isEqualTo(200);
    assertThat(plan.productCount()).isEqualTo(40_000);
    assertThat(plan.monthlyRepriceContextKeys()).singleElement()
        .satisfies(key -> {
          assertThat(key.pricingMonth()).isEqualTo("2026-05");
          assertThat(key.priceAsOfTime()).isEqualTo(priceAsOfTime);
          assertThat(key.adjustBatchId()).isEqualTo(8801L);
        });
    assertThat(plan.contextViolations()).isEmpty();
    assertThat(plan.recommendedClaimBatches(100)).isEqualTo(400);
  }

  @Test
  void reportsMonthlyRepriceContextDriftInsideOneBatch() {
    LocalDateTime firstTime = LocalDateTime.of(2026, 5, 27, 0, 0);
    LocalDateTime secondTime = LocalDateTime.of(2026, 6, 1, 0, 0);
    CostRunTask first = monthlyTask(
        1L, "BATCH-MRP-202605", "MRP-202605", "OA-001", "P-001", "2026-05", firstTime, 8801L);
    CostRunTask second = monthlyTask(
        2L, "BATCH-MRP-202605", "MRP-202605", "OA-002", "P-002", "2026-06", secondTime, 8802L);

    CostRunTaskBatchPressurePlan plan = CostRunTaskBatchPressurePlan.from(List.of(first, second));

    assertThat(plan.hasContextViolations()).isTrue();
    assertThat(plan.contextViolations()).singleElement()
        .satisfies(violation -> {
          assertThat(violation.batchNo()).isEqualTo("BATCH-MRP-202605");
          assertThat(violation.sourceNo()).isEqualTo("MRP-202605");
          assertThat(violation.contexts()).hasSize(2);
          assertThat(String.join("\n", violation.contexts()))
              .contains("pricingMonth=2026-05")
              .contains("pricingMonth=2026-06")
              .contains("adjustBatchId=8801")
              .contains("adjustBatchId=8802");
        });
  }

  @Test
  void separatesCacheKeysAcrossMonthlyRepriceBatches() {
    CostRunTask first = monthlyTask(
        1L,
        "BATCH-MRP-202605",
        "MRP-202605",
        "OA-001",
        "P-001",
        "2026-05",
        LocalDateTime.of(2026, 5, 27, 0, 0),
        8801L);
    CostRunTask second = monthlyTask(
        2L,
        "BATCH-MRP-202606",
        "MRP-202606",
        "OA-001",
        "P-001",
        "2026-06",
        LocalDateTime.of(2026, 6, 27, 0, 0),
        9901L);

    CostRunTaskBatchPressurePlan plan = CostRunTaskBatchPressurePlan.from(List.of(first, second));

    assertThat(plan.monthlyRepriceContextKeys()).hasSize(2);
    assertThat(plan.bomContextKeys()).hasSize(1);
    assertThat(plan.contextViolations()).isEmpty();
  }

  private CostRunTask monthlyTask(
      Long id,
      String batchNo,
      String sourceNo,
      String oaNo,
      String productCode,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      Long adjustBatchId) {
    CostRunTask task = new CostRunTask();
    task.setId(id);
    task.setBatchNo(batchNo);
    task.setScene(CostRunTaskScene.MONTHLY_REPRICE.name());
    task.setSourceNo(sourceNo);
    task.setCalcObjectKey(oaNo + "#" + productCode);
    task.setOaNo(oaNo);
    task.setProductCode(productCode);
    task.setBusinessUnitType("COMMERCIAL");
    task.setPricingMonth(pricingMonth);
    task.setPriceAsOfTime(priceAsOfTime);
    task.setAdjustBatchId(adjustBatchId);
    task.setBomSourcePolicy("HISTORICAL");
    return task;
  }
}
