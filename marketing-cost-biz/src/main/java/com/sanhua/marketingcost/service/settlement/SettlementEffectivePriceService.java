package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

/** 只在发布事务中生成月度结果；按原稿/基价边界追加，不重写历史。 */
@Service
public class SettlementEffectivePriceService {
  public record Generation(int generated, int reused, int pending) {}
  private final SettlementPriceRepository rules;
  private final SettlementFinanceBaseReader bases;
  private final SettlementEffectivePriceRepository results;
  private final SettlementPriceCalculator calculator;
  private final SettlementPriceEvidence evidence;
  private final SettlementRuleRevisionFactory hashes;

  public SettlementEffectivePriceService(SettlementPriceRepository rules, SettlementFinanceBaseReader bases,
      SettlementEffectivePriceRepository results, SettlementPriceCalculator calculator,
      SettlementPriceEvidence evidence, SettlementRuleRevisionFactory hashes) {
    this.rules = rules; this.bases = bases; this.results = results;
    this.calculator = calculator; this.evidence = evidence; this.hashes = hashes;
  }

  public void lock(String unit) { results.lock(unit); }

  public Generation generate(String unit, String actor) {
    String operator = SettlementScope.required(actor, "发布人", 64);
    lock(unit);
    var source = rules.generationRules(unit);
    if (source.isEmpty()) return new Generation(0, 0, 0);
    var months = new TreeSet<>(bases.publishedMonths(unit));
    source.forEach(row -> months.add(row.getPriceMonth()));
    var existing = results.existing(unit);
    Map<SettlementPriceRepository.RuleKey, SettlementPriceRecord> current = new LinkedHashMap<>();
    List<MapSqlParameterSource> writes = new ArrayList<>();
    int next = 0, reused = 0, pending = 0;
    for (String month : months) {
      while (next < source.size() && source.get(next).getPriceMonth().compareTo(month) <= 0) {
        var rule = source.get(next++);
        current.put(new SettlementPriceRepository.RuleKey(rule.getMaterialCode(), rule.getPriceType()), rule);
      }
      if (current.isEmpty()) continue;
      boolean needsBase = current.values().stream().anyMatch(row -> calculator.mode(row) == SettlementPriceMode.LINKED_FORMULA);
      var base = needsBase ? bases.effective(new SettlementScope(unit, month)).orElse(null) : null;
      for (var rule : current.values()) {
        var mode = calculator.mode(rule);
        var selected = mode == SettlementPriceMode.LINKED_FORMULA ? base : null;
        // 未提供完整重量/尚无财务发布时保留原稿，读取端明确提示补齐来源，禁止生成替代价。
        if (mode == SettlementPriceMode.LINKED_FORMULA && (selected == null || !rule.weights().completeAndConfirmed())) {
          pending++; continue;
        }
        String fingerprint = hashes.hash(List.of(unit, month, rule.getId(), rule.getContentHash(),
            selected == null ? 0L : selected.id(), SettlementPriceCalculator.CALCULATOR_VERSION));
        if (!existing.fingerprints().add(fingerprint)) { reused++; continue; }
        var value = calculator.calculate(rule, selected);
        var revisionKey = new SettlementEffectivePriceRepository.RevisionKey(rule.getMaterialCode(), rule.getPriceType().name(), month);
        int revision = existing.revisions().merge(revisionKey, 1, Integer::sum);
        writes.add(new MapSqlParameterSource("unit", unit).addValue("material", rule.getMaterialCode())
            .addValue("type", rule.getPriceType().name()).addValue("month", month).addValue("revision", revision)
            .addValue("ruleId", rule.getId()).addValue("ruleMonth", rule.getPriceMonth()).addValue("ruleRevision", rule.getRevisionNo())
            .addValue("financeId", selected == null ? 0 : selected.id()).addValue("financeMonth", selected == null ? null : selected.priceMonth())
            .addValue("financeRevision", selected == null ? null : selected.revisionNo()).addValue("mode", value.mode().name())
            .addValue("price", value.unitPrice()).addValue("calculator", SettlementPriceCalculator.CALCULATOR_VERSION)
            .addValue("fingerprint", fingerprint).addValue("evidence", evidence.capture(rule, selected, value)).addValue("actor", operator));
      }
    }
    results.insert(writes);
    return new Generation(writes.size(), reused, pending);
  }
}
