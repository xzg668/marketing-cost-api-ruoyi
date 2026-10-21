package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SettlementBasePriceService {
  public record Change(BigDecimal copperPrice, BigDecimal zincPrice, String unit, int expectedRevision, String reason) {}
  private final SettlementBasePriceRepository repository;
  private final TransactionTemplate transaction;

  public SettlementBasePriceService(SettlementBasePriceRepository repository, PlatformTransactionManager transactions) {
    this.repository = repository;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Optional<SettlementBasePrice> effective(SettlementScope scope) { return repository.effective(scope); }
  public List<SettlementBasePrice> history(SettlementScope scope) { return repository.history(scope); }

  public SettlementBasePrice save(SettlementScope scope, Change change, String actor) {
    if (change == null) throw new IllegalArgumentException("结算铜锌基价必填");
    String operator = SettlementScope.required(actor, "维护人", 64);
    String reason = SettlementScope.required(change.reason(), "修改依据", 1024);
    if (change.expectedRevision() < 0) throw new IllegalArgumentException("预期修订号不能为负数");
    BigDecimal copper = normalizePrice(change.copperPrice(), change.unit());
    BigDecimal zinc = normalizePrice(change.zincPrice(), change.unit());
    try {
      return transaction.execute(status -> {
        var current = repository.latest(scope);
        int revision = current.map(SettlementBasePrice::revisionNo).orElse(0);
        if (revision != change.expectedRevision()) throw conflict();
        if (current.isPresent() && current.get().active()
            && current.get().copperPrice().compareTo(copper) == 0 && current.get().zincPrice().compareTo(zinc) == 0)
          return current.get();
        return repository.insert(scope, revision + 1, copper, zinc, "FINANCE", null, reason, operator);
      });
    } catch (DuplicateKeyException concurrentWrite) { throw conflict(); }
  }

  static BigDecimal normalizePrice(BigDecimal price, String unit) {
    if (price == null || price.signum() < 0) throw new IllegalArgumentException("铜价和锌价必须成组填写非负数");
    BigDecimal normalized = switch (unit == null ? "" : unit.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "CNY/KG" -> price;
      case "CNY/T" -> price.movePointLeft(3);
      default -> throw new IllegalArgumentException("基价单位仅支持 CNY/KG（元/公斤）或 CNY/T（元/吨）");
    };
    try {
      if (normalized.setScale(12, RoundingMode.UNNECESSARY).precision() > 24) throw new ArithmeticException("overflow");
    } catch (ArithmeticException invalid) { throw new IllegalArgumentException("结算基价最多保留 12 位小数和 12 位整数，不能静默舍入", invalid); }
    return normalized.stripTrailingZeros();
  }

  private OptimisticLockingFailureException conflict() {
    return new OptimisticLockingFailureException("结算基价已被其他操作修改，请刷新修订号后重试");
  }
}
