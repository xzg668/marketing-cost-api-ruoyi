package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.YearMonth;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 只在导入已提交后重试本次涉及的等待事项；不改变其他报价的人工选择。 */
@Component
public class SupplierPriceImportRetry {
  private static final Logger log = LoggerFactory.getLogger(SupplierPriceImportRetry.class);
  private final SupplierPriceDecisionMapper decisions;
  private final SupplierPriceReviewWorkflow workflow;

  public SupplierPriceImportRetry(SupplierPriceDecisionMapper decisions, SupplierPriceReviewWorkflow workflow) {
    this.decisions = decisions;
    this.workflow = workflow;
  }

  @Async("costRunExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void imported(SupplierRatiosImported event) {
    var month = YearMonth.now(CostPricingPeriodUtils.BUSINESS_ZONE).toString();
    var retried = new HashSet<String>();
    for (var decision : decisions.waiting(event.businessUnitType(), month)) {
      if (!event.materialCodes().contains(decision.getMaterialCode())) continue;
      String product = decision.getOaFormItemId() + ":" + decision.getPricingDate();
      if (!retried.add(product)) continue;
      try {
        workflow.reprice(decision);
      } catch (RuntimeException error) {
        decision.setRetryStatus("FAILED");
        decision.setRetryMessage("自动取价未完成，请在核算工作台查看价格缺口");
        decisions.updateById(decision);
        log.warn("供货比率导入后自动取价失败：itemId={}, month={}",
            decision.getOaFormItemId(), decision.getPeriodMonth(), error);
      }
    }
  }
}
