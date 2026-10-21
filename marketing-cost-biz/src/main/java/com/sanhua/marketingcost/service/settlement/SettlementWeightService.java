package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 财务确认铜锌重量，追加规则修订并保留原始单价及来源，不修改旧版本。 */
@Service
public class SettlementWeightService {
  public record Change(BigDecimal copperWeightG, BigDecimal zincWeightG, int expectedRevision, String reason) {}
  private final SettlementPriceRepository prices;
  private final SettlementRuleRevisionFactory revisions;
  private final TransactionTemplate transaction;

  public SettlementWeightService(SettlementPriceRepository prices, SettlementRuleRevisionFactory revisions,
      PlatformTransactionManager transactions) {
    this.prices = prices;
    this.revisions = revisions;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public SettlementPriceRecord confirm(String businessUnit, long id, Change change, String actor) {
    if (change == null) throw new IllegalArgumentException("铜锌重量及确认依据必填");
    String operator = SettlementScope.required(actor, "确认人", 64);
    try {
      return transaction.execute(status -> {
        var current = prices.byId(businessUnit, id).orElseThrow(() -> new IllegalArgumentException("结算价格不存在"));
        if (current.getPriceType() != SettlementPriceType.SETTLE_LINKED)
          throw new IllegalArgumentException("仅结算联动价需要确认铜锌重量");
        var scope = new SettlementScope(businessUnit, current.getPriceMonth());
        var latest = prices.history(scope, current.getMaterialCode(), current.getPriceType()).getFirst();
        if (!Objects.equals(latest.getId(), current.getId()) || current.getRevisionNo() != change.expectedRevision()) throw conflict();
        var weights = new SettlementWeights(current.getOriginalCopperWeightG(), current.getOriginalZincWeightG(),
            change.copperWeightG(), change.zincWeightG(), true, change.reason());
        if (current.isWeightsConfirmed() && current.getCopperWeightG().compareTo(weights.copperG()) == 0
            && current.getZincWeightG().compareTo(weights.zincG()) == 0
            && Objects.equals(current.getWeightConfirmationNote(), weights.confirmationNote())) return current;
        return prices.insert(revisions.withConfirmedWeights(current, weights, operator));
      });
    } catch (DuplicateKeyException | ConcurrencyFailureException changed) { throw conflict(); }
  }

  private OptimisticLockingFailureException conflict() {
    return new OptimisticLockingFailureException("结算价格或重量已变化，请刷新当前修订后重试");
  }
}
