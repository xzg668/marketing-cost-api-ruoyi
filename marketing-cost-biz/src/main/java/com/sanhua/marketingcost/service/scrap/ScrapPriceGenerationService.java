package com.sanhua.marketingcost.service.scrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.ScrapPriceMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 按每个废料/供应商选择最近显式规则；财务发布、规则维护和报价准备共用此写入口。 */
@Service
public class ScrapPriceGenerationService {
  private final ScrapPriceMapper prices;
  private final FactorIdentityMapper identities;
  private final FactorUploadBatchMapper batches;
  private final FinanceFactorPriceReader finance;
  private final ScrapPriceRuleCodec rules;
  private final ScrapPriceCalculator calculator;
  private final TransactionTemplate preparation;

  public ScrapPriceGenerationService(
      ScrapPriceMapper prices,
      FactorIdentityMapper identities,
      FactorUploadBatchMapper batches,
      FinanceFactorPriceReader finance,
      ScrapPriceRuleCodec rules,
      ScrapPriceCalculator calculator,
      PlatformTransactionManager transactions) {
    this.prices = prices;
    this.identities = identities;
    this.batches = batches;
    this.finance = finance;
    this.rules = rules;
    this.calculator = calculator;
    this.preparation = new TransactionTemplate(transactions);
    // 报价可能已经建立只读快照；独立准备事务必须先拿目录锁，再读取当前财务修订。
    this.preparation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public record Generation(
      int generated,
      int reused,
      int waiting,
      List<ScrapPrice> requestedPrices,
      java.util.Set<String> managedMaterials) {}

  public Generation prepareMonth(String businessUnit, String month, String actor) {
    return preparation.execute(status -> refreshInTransaction(businessUnit, month, actor));
  }

  /** 发布/导入事务在任何业务读写前持有财务目录锁；此处沿用相同锁顺序。 */
  public Generation refreshInTransaction(String businessUnit, String requestedMonth, String actor) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("废料月价必须在事务内准备");
    String unit = ScrapPriceIdentity.businessUnit(businessUnit);
    String month = FactorPriceScope.month(requestedMonth);
    String operator = ScrapPriceRule.required(actor, "价格准备人", 64);
    identities.lockForFinancePublication(unit);
    var existing = new ArrayList<>(prices.lockScope(unit));
    if (existing.isEmpty()) return new Generation(0, 0, 0, List.of(), java.util.Set.of());
    var publications = latestPublications(unit);
    var months = new TreeSet<String>(publications.keySet());
    months.add(month);
    existing.forEach(row -> months.add(row.getPriceMonth()));
    var snapshots = new HashMap<Long, FactorPriceSnapshot>();
    int generated = 0, reused = 0, waiting = 0;
    LocalDateTime now = LocalDateTime.now();
    for (String priceMonth : months) {
      var selected = ScrapRuleMonthPolicy.select(existing, unit, priceMonth);
      var current = new TreeMap<String, ScrapPrice>();
      existing.stream()
          .filter(row -> row.getPriceMonth().equals(priceMonth))
          .forEach(
              row -> current.put(ScrapRuleMonthPolicy.identity(row).materialSupplierKey(), row));
      // 补供应商代码后，旧身份的自动副本不能成为独立新规则；仅清理已无来源的自动行。
      for (var row : List.copyOf(current.values())) {
        String key = ScrapRuleMonthPolicy.identity(row).materialSupplierKey();
        if ("CARRY_FORWARD".equals(row.getOriginType()) && !selected.containsKey(key)) {
          prices.deleteById(row.getId());
          existing.remove(row);
          current.remove(key);
        }
      }
      FactorPriceSnapshot snapshot = null;
      var published = publications.floorEntry(priceMonth);
      if (published != null
          && selected.values().stream().anyMatch(r -> "LINKED".equals(r.getPriceType()))) {
        var batch = published.getValue();
        // 指定刚选定的批次修订读取；不复用报价请求的事务缓存，避免发布后仍拿到旧修订。
        snapshot =
            snapshots.computeIfAbsent(
                batch.getId(),
                ignored ->
                    finance.read(
                        new FinanceFactorImportContext(
                            unit, "210", batch.getPriceMonth(), null, batch.getDefaultSchemeCode()),
                        FactorPriceScope.Condition.GENERAL,
                        batch.getPublicationRevision()));
      }
      for (var entry : selected.entrySet()) {
        ScrapPrice source = entry.getValue();
        ScrapPrice row = current.get(entry.getKey());
        boolean created = row == null;
        if (created) {
          row = new ScrapPrice();
          row.setBusinessUnitType(unit);
          row.setPriceMonth(priceMonth);
          row.setRowVersion(0L);
          row.setRuleRevision(0);
          row.setCreatedBy(operator);
          row.setCreatedAt(now);
        }
        if (!priceMonth.equals(source.getPriceMonth())) inherit(row, source);
        if (snapshot == null && "LINKED".equals(row.getPriceType())) {
          waiting++;
          String pending =
              ScrapPriceIdentity.hash(
                  "WAITING:"
                      + row.getPriceMonth()
                      + ":"
                      + row.getContentHash()
                      + ":"
                      + row.getRuleRevision()
                      + ":"
                      + row.getSourceRuleId()
                      + ":"
                      + row.getSourceRuleRevision());
          if (!created && pending.equals(row.getInputFingerprint())) {
            reused++;
            continue;
          }
          row.setUnitPrice(null);
          row.setCalcStatus("WAITING_BASE");
          row.setCalcMessage("未发布该月份适用的商用财务基价");
          row.setInputFingerprint(pending);
          row.setCalculationJson(null);
          row.setFinanceMonth(null);
          row.setFinanceBatchId(null);
          row.setFinanceRevision(null);
          row.setSchemeCode(null);
          row.setCalculatedAt(null);
        } else {
          var input = calculator.prepare(row, snapshot);
          if (!created && input.fingerprint().equals(row.getInputFingerprint())) {
            reused++;
            continue;
          }
          row.setUnitPrice(calculator.calculate(input));
          row.setCalcStatus("READY");
          row.setCalcMessage(null);
          row.setInputFingerprint(input.fingerprint());
          row.setCalculationJson(input.evidenceJson());
          row.setCalculatedAt(now);
          var basis = input.factors() == null ? null : input.factors().financial();
          row.setFinanceMonth(basis == null ? null : basis.scope().priceMonth());
          row.setFinanceBatchId(basis == null ? null : basis.batchId());
          row.setFinanceRevision(basis == null ? null : basis.revision());
          row.setSchemeCode(basis == null ? null : basis.scope().schemeCode());
        }
        row.setFinanceOrgCode("210");
        row.setUpdatedBy(operator);
        row.setUpdatedAt(now);
        row.setRowVersion(row.getRowVersion() + 1);
        if (created) {
          prices.insert(row);
          existing.add(row);
        } else if (prices.updateById(row) != 1) throw new IllegalStateException("废料月价更新失败");
        generated++;
      }
    }
    // 重新批量读请求月，使自动生成的供应商键和数据库金额精度也进入返回快照。
    var requested =
        prices.selectList(
            Wrappers.lambdaQuery(ScrapPrice.class)
                .eq(ScrapPrice::getBusinessUnitType, unit)
                .eq(ScrapPrice::getPriceMonth, month)
                .orderByAsc(ScrapPrice::getMaterialCode, ScrapPrice::getSupplierKey));
    return new Generation(
        generated,
        reused,
        waiting,
        List.copyOf(requested),
        existing.stream()
            .map(ScrapPrice::getMaterialCode)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }

  private TreeMap<String, FactorUploadBatch> latestPublications(String unit) {
    var latest = new TreeMap<String, FactorUploadBatch>();
    for (var batch :
        batches.selectList(
            Wrappers.lambdaQuery(FactorUploadBatch.class)
                .eq(FactorUploadBatch::getBusinessUnitType, unit)
                .eq(FactorUploadBatch::getPriceOrgCode, "210")
                .eq(FactorUploadBatch::getImportType, "FINANCE_BASE")
                .eq(FactorUploadBatch::getStatus, "PUBLISHED")
                .isNotNull(FactorUploadBatch::getPublicationRevision)
                .isNotNull(FactorUploadBatch::getPublishedAt))) {
      latest.merge(
          batch.getPriceMonth(),
          batch,
          (left, right) ->
              left.getPublicationRevision() >= right.getPublicationRevision() ? left : right);
    }
    return latest;
  }

  private void inherit(ScrapPrice row, ScrapPrice source) {
    if (Objects.equals(row.getSourceRuleId(), source.getId())
        && Objects.equals(row.getSourceRuleRevision(), source.getRuleRevision())
        && Objects.equals(row.getContentHash(), source.getContentHash())) return;
    rules.write(rules.read(source), row);
    row.setOriginType("CARRY_FORWARD");
    row.setSupersededIdentityKeysJson(null);
    row.setRuleSourceMonth(source.getPriceMonth());
    row.setSourceRuleId(source.getId());
    row.setSourceRuleRevision(source.getRuleRevision());
    row.setRuleRevision(row.getRuleRevision() + 1);
    row.setImportBatchNo(source.getImportBatchNo());
    row.setSourceFileName(source.getSourceFileName());
    row.setSourceFileSha256(source.getSourceFileSha256());
    row.setSourceSheet(source.getSourceSheet());
    row.setSourceRow(source.getSourceRow());
    row.setSourceJson(source.getSourceJson());
    row.setImportedBy(source.getImportedBy());
    row.setImportedAt(source.getImportedAt());
  }
}
