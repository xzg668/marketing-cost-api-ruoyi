package com.sanhua.marketingcost.service.pricing.inquiry;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All three triggers use this whole-source refresh. It never adopts or rewrites a monthly price.
 */
@Service
public final class InquiryPriceRefreshService {
  public record Result(int ready, int waiting, int invalid) {}

  public record Calculation(
      String priceType,
      String expression,
      Map<String, BigDecimal> inputs,
      Map<String, String> factors,
      Long financeBatchId,
      Integer financeRevision,
      String financeMonth,
      String financeScheme,
      Map<String, FactorPriceSnapshot.FactorValue> financeFactors, FactorPriceSnapshot financial) {}

  private final InquiryPriceRepository repository;
  private final FinanceFactorPriceReader finance;
  private final TransactionTemplate transaction;

  public InquiryPriceRefreshService(
      InquiryPriceRepository repository,
      FinanceFactorPriceReader finance,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.finance = finance;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    transaction.setTimeout(90);
  }

  public Result refresh(String unit, String month, String trigger, String actor) {
    InquiryPriceService.scope(unit, month);
    for (int attempt = 0; ; attempt++) {
      try {
        return Objects.requireNonNull(
            transaction.execute(tx -> refreshInTransaction(unit, month, trigger, actor)));
      } catch (org.springframework.dao.TransientDataAccessException conflict) {
        if (attempt >= 2) throw conflict;
      }
    }
  }

  public Result refreshInTransaction(String unit, String month, String trigger, String actor) {
    InquiryPriceService.scope(unit, month);
    repository.lock(unit);
    var sources = repository.current(unit, month, true);
    if (sources.isEmpty()) return new Result(0, 0, 0);
    FactorPriceSnapshot financial = null;
    String financialError = null;
    if (sources.stream().anyMatch(source -> source.rule().priceType().equals("联动价"))) {
      try {
        financial =
            finance.read(
                new FinanceFactorImportContext(unit, "210", month, null, null),
                FactorPriceScope.Condition.GENERAL,
                null);
      } catch (FinanceFactorPriceReader.MissingPublicationException missing) {
        financialError = missing.getMessage();
      }
    }
    int ready = 0, waiting = 0, invalid = 0;
    for (var source : sources) {
      var rule = source.rule();
      boolean linked = rule.priceType().equals("联动价");
      String state = "READY", message = null;
      BigDecimal price = null;
      var inputs = new LinkedHashMap<String, BigDecimal>();
      var factors = new LinkedHashMap<String, FactorPriceSnapshot.FactorValue>();
      try {
        if (linked && financial == null) {
          state = "WAITING_BASE";
          message = financialError;
        } else if (linked) {
          for (var binding : rule.factors().entrySet()) {
            var value = financial.required(binding.getValue());
            inputs.put(binding.getKey(), value.price());
            factors.put(binding.getValue(), value);
          }
          price = ExpressionEvaluator.evaluate(rule.compiled().expression(), inputs);
        } else price = rule.originalPrice();
        if (price != null) {
          InquiryWorkbookParser.amount(price);
          price = price.setScale(18, RoundingMode.HALF_UP);
        }
      } catch (IllegalArgumentException arithmetic) {
        state = "INVALID";
        message = arithmetic.getMessage();
      }
      var basis =
          new Calculation(
              rule.priceType(),
              linked ? rule.compiled().expression() : null,
              Map.copyOf(inputs),
              rule.factors(),
              linked && financial != null ? financial.batchId() : null,
              linked && financial != null ? financial.revision() : null,
              linked && financial != null ? financial.scope().priceMonth() : null,
              linked && financial != null ? financial.scope().schemeCode() : null,
              Map.copyOf(factors), linked ? financial : null);
      long batch = linked && financial != null ? financial.batchId() : 0;
      String hash =
          MonthlyPriceDocumentCodec.sha256(
              repository.encode(List.of(source.id(), month, batch, state, basis)));
      repository.saveEffective(
          unit,
          month,
          source.id(),
          batch,
          hash,
          state,
          price,
          message == null ? null : message.substring(0, Math.min(message.length(), 1000)),
          basis,
          trigger,
          actor);
      switch (state) {
        case "READY" -> ready++;
        case "WAITING_BASE" -> waiting++;
        default -> invalid++;
      }
    }
    return new Result(ready, waiting, invalid);
  }
}
