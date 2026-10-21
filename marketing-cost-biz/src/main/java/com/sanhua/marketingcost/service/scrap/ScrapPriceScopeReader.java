package com.sanhua.marketingcost.service.scrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.ScrapPriceMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 核算请求共享一次废料月份视图；只读检查可投影未落库月份，不产生业务写入。 */
@Service
public class ScrapPriceScopeReader {
  private final ScrapPriceMapper prices;
  private final ScrapPriceGenerationService generation;
  private final FinanceFactorPriceReader finance;
  private final ScrapPriceCalculator calculator;
  private final ThreadLocal<Map<Key, Scope>> batchScopes = new ThreadLocal<>();

  public ScrapPriceScopeReader(
      ScrapPriceMapper prices,
      ScrapPriceGenerationService generation,
      FinanceFactorPriceReader finance,
      ScrapPriceCalculator calculator) {
    this.prices = prices;
    this.generation = generation;
    this.finance = finance;
    this.calculator = calculator;
  }

  public record Scope(Set<String> managedMaterials, Map<String, List<ScrapPrice>> byMaterial) {
    public Scope {
      managedMaterials = Set.copyOf(managedMaterials);
      var rows = new TreeMap<String, List<ScrapPrice>>();
      byMaterial.forEach((code, candidates) -> rows.put(code, List.copyOf(candidates)));
      byMaterial = java.util.Collections.unmodifiableMap(rows);
    }
  }

  private record Key(String businessUnit, String month, boolean prepare) {}

  /** 独立制造件批量入口没有外层事务，限定本批次复用，结束后必须释放。 */
  public Batch openBatch() {
    if (batchScopes.get() != null) return () -> {};
    batchScopes.set(new HashMap<>());
    return batchScopes::remove;
  }

  @FunctionalInterface
  public interface Batch extends AutoCloseable {
    @Override
    void close();
  }

  public Scope read(String businessUnit, String month, boolean prepare) {
    var key =
        new Key(
            ScrapPriceIdentity.businessUnit(businessUnit), FactorPriceScope.month(month), prepare);
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      var batch = batchScopes.get();
      return batch == null ? load(key) : batch.computeIfAbsent(key, this::load);
    }
    // 实际事务继续使用自己的视图，REQUIRES_NEW 不得借用批次外层的旧视图。
    @SuppressWarnings("unchecked")
    var cached = (Map<Key, Scope>) TransactionSynchronizationManager.getResource(this);
    if (cached == null) {
      cached = new HashMap<>();
      var transactionScopes = cached;
      TransactionSynchronizationManager.bindResource(this, transactionScopes);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void suspend() {
              TransactionSynchronizationManager.unbindResource(ScrapPriceScopeReader.this);
            }

            @Override
            public void resume() {
              TransactionSynchronizationManager.bindResource(
                  ScrapPriceScopeReader.this, transactionScopes);
            }

            @Override
            public void afterCompletion(int status) {
              TransactionSynchronizationManager.unbindResourceIfPossible(
                  ScrapPriceScopeReader.this);
            }
          });
    }
    return cached.computeIfAbsent(key, this::load);
  }

  private Scope load(Key key) {
    if (key.prepare()) {
      var prepared = generation.prepareMonth(key.businessUnit(), key.month(), "PRICE_PREPARE");
      return scope(prepared.managedMaterials(), prepared.requestedPrices());
    }
    var all =
        prices.selectList(
            Wrappers.lambdaQuery(ScrapPrice.class)
                .eq(ScrapPrice::getBusinessUnitType, key.businessUnit()));
    var managed = all.stream().map(ScrapPrice::getMaterialCode).collect(Collectors.toSet());
    var selected = ScrapRuleMonthPolicy.select(all, key.businessUnit(), key.month());
    var current = new HashMap<String, ScrapPrice>();
    all.stream()
        .filter(row -> row.getPriceMonth().equals(key.month()))
        .forEach(row -> current.put(ScrapRuleMonthPolicy.identity(row).materialSupplierKey(), row));
    FactorPriceSnapshot financial = null;
    if (selected.values().stream().anyMatch(row -> row.getPriceType().equals("LINKED"))) {
      try {
        financial =
            finance
                .readDefault(
                    new FinanceFactorImportContext(
                        key.businessUnit(), "210", key.month(), null, null),
                    "SCRAP_PREVIEW")
                .financial();
      } catch (FinanceFactorPriceReader.MissingPublicationException waiting) {
        // 只有完全没有适用发布可等待，已发布但缺少因素仍由计算器明确报错。
      }
    }
    var result = new ArrayList<ScrapPrice>();
    for (var entry : selected.entrySet()) {
      ScrapPrice source = entry.getValue();
      ScrapPrice existing = current.get(entry.getKey());
      ScrapPrice row = new ScrapPrice();
      boolean validCopy =
          existing != null
              && (existing.getSourceRuleId() == null
                  || (source.getId().equals(existing.getSourceRuleId())
                      && source.getRuleRevision().equals(existing.getSourceRuleRevision())));
      BeanUtils.copyProperties(validCopy ? existing : source, row);
      if (!validCopy && !source.getPriceMonth().equals(key.month())) {
        row.setId(null); // 只读投影没有月价记录ID；真实规则ID单独保留。
        row.setPriceMonth(key.month());
        row.setOriginType("CARRY_FORWARD");
        row.setRuleSourceMonth(source.getPriceMonth());
        row.setSourceRuleId(source.getId());
        row.setSourceRuleRevision(source.getRuleRevision());
      }
      if (financial == null && row.getPriceType().equals("LINKED")) {
        row.setUnitPrice(null);
        row.setCalcStatus("WAITING_BASE");
        row.setCalcMessage("未发布该月份适用的商用财务基价");
        row.setFinanceMonth(null);
        row.setFinanceBatchId(null);
        row.setFinanceRevision(null);
        row.setSchemeCode(null);
        row.setCalculationJson(null);
        row.setInputFingerprint(null);
      } else {
        var input = calculator.prepare(row, financial);
        if (!input.fingerprint().equals(row.getInputFingerprint())
            || !"READY".equals(row.getCalcStatus())) {
          row.setUnitPrice(calculator.calculate(input));
          row.setInputFingerprint(input.fingerprint());
          row.setCalculationJson(input.evidenceJson());
          row.setCalcStatus("READY");
          row.setCalcMessage(null);
          var basis = input.factors() == null ? null : input.factors().financial();
          row.setFinanceMonth(basis == null ? null : basis.scope().priceMonth());
          row.setFinanceBatchId(basis == null ? null : basis.batchId());
          row.setFinanceRevision(basis == null ? null : basis.revision());
          row.setSchemeCode(basis == null ? null : basis.scope().schemeCode());
        }
      }
      result.add(row);
    }
    return scope(managed, result);
  }

  private Scope scope(Set<String> managed, List<ScrapPrice> rows) {
    return new Scope(
        managed, rows.stream().collect(Collectors.groupingBy(ScrapPrice::getMaterialCode)));
  }
}
