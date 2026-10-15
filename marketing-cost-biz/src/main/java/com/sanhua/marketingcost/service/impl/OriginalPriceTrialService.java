package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原稿列表/详情读取已发布有效价；明确提供客户条件时仅试算必要的客户侧结果。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OriginalPriceTrialService {
  private final PriceLinkedItemMapper linkedItems;
  private final PriceRangeFactorRuleMapper rangeRules;
  private final OriginalRangeRuleSnapshot rangeSnapshots;
  private final OriginalLinkedRuleCalculator linkedCalculator;
  private final OriginalRangeRuleCalculator rangeCalculator;
  private final FinanceFactorPriceReader finance;
  private final FactorUploadBatchMapper batches;
  private final FinanceEffectivePriceService effectivePrices;

  public OriginalPriceTrialService(
      PriceLinkedItemMapper linkedItems,
      PriceRangeFactorRuleMapper rangeRules,
      OriginalRangeRuleSnapshot rangeSnapshots,
      OriginalLinkedRuleCalculator linkedCalculator,
      OriginalRangeRuleCalculator rangeCalculator,
      FinanceFactorPriceReader finance,
      FactorUploadBatchMapper batches,
      FinanceEffectivePriceService effectivePrices) {
    this.linkedItems = linkedItems;
    this.rangeRules = rangeRules;
    this.rangeSnapshots = rangeSnapshots;
    this.linkedCalculator = linkedCalculator;
    this.rangeCalculator = rangeCalculator;
    this.finance = finance;
    this.batches = batches;
    this.effectivePrices = effectivePrices;
  }

  public LinkedTrial linked(Long id, Request request) {
    var item = linkedItems.selectById(id);
    if (item == null
        || !"PUBLIC".equals(item.getSourceKind())
        || !"PUBLISHED".equals(item.getRuleStatus())
        || Integer.valueOf(1).equals(item.getDeleted())
        || !OriginalLinkedRuleSnapshot.VERSION.equals(item.getExpressionVersion()))
      throw new IllegalArgumentException("已发布联动原稿不存在");
    requireBusinessUnit(item.getBusinessUnitType());
    var source = FinanceEffectivePriceService.SourceRule.linked(item);
    var context = context(source.key(), item.getPricingMonth(), request);
    var saved = effectivePrices.calculation(effectivePrices.require(item, context.financial()));
    context = savedContext(saved.context(), request.pricingMonth());
    var rule = saved.linkedRule();
    if (rule == null || saved.linked() == null) throw new IllegalArgumentException("财务有效价缺少联动计算依据");
    var customerContext =
        request.customerCopperPerTon() == null
            ? context
            : context.withCustomerCopperPerTon(request.customerCopperPerTon());
    var p0 = saved.linked();
    var p1 =
        OriginalLinkedPriceRuntime.changedInputs(rule, p0, customerContext)
            ? linkedCalculator.calculate(rule, customerContext)
            : p0;
    return new LinkedTrial(
        summary(
            rule.identity(),
            item.getPricingMonth(),
            context,
            request,
            p0.priceExcludingTax(),
            p1.priceExcludingTax()),
        rule,
        p0,
        p1,
        context,
        sourceFile(item.getSourceUploadBatchId()));
  }

  public RangeTrial range(Long id, Request request) {
    var item = rangeRules.selectById(id);
    if (!OriginalRangeRuleSnapshot.isOriginal(item))
      throw new IllegalArgumentException("已发布区间原稿不存在");
    requireBusinessUnit(item.getBusinessUnitType());
    var saved = rangeSnapshots.read(item);
    var rule = saved.rule();
    var context = context(rule.identity(), item.getPricingMonth(), request);
    var savedPrice =
        effectivePrices.calculation(effectivePrices.require(item, context.financial()));
    context = savedContext(savedPrice.context(), request.pricingMonth());
    var customerContext =
        request.customerCopperPerTon() == null
            ? context
            : context.withCustomerCopperPerTon(request.customerCopperPerTon());
    var p0 = savedPrice.range();
    if (p0 == null) throw new IllegalArgumentException("财务有效价缺少区间计算依据");
    var p1 =
        customerContext
                    .requiredPrice(rule.factorKey(), rule.factorUnit(), true)
                    .compareTo(p0.factorValue())
                == 0
            ? p0
            : rangeCalculator.calculate(rule, customerContext);
    return new RangeTrial(
        summary(
            rule.identity(),
            item.getPricingMonth(),
            context,
            request,
            p0.priceExcludingTax(),
            p1.priceExcludingTax()),
        saved,
        p0,
        p1,
        context,
        item.getSourceFile());
  }

  public record BatchRequest(java.util.List<Long> originalIds, String pricingMonth) {}

  public record EffectiveRow(
      Long originalId,
      String status,
      String message,
      BigDecimal unitPrice,
      Long effectivePriceId,
      String originalMonth,
      String financeMonth,
      String schemeCode,
      String conditionCode,
      OriginalRangeWorkbookParser.Bounds selectedBounds) {}

  /** 同页一次读取全部供应商结果，保持缺价、待基价和已就绪可区分，不在读取中补算。 */
  public java.util.List<EffectiveRow> effective(String type, BatchRequest request) {
    if (request == null
        || request.originalIds() == null
        || request.originalIds().size() > 200
        || request.originalIds().stream().anyMatch(id -> id == null || id <= 0))
      throw new IllegalArgumentException("原稿ID必填且一次最多200条");
    String month = FactorPriceScope.month(request.pricingMonth());
    if (request.originalIds().isEmpty()) return java.util.List.of();
    var ids = new java.util.LinkedHashSet<>(request.originalIds());
    var rules = new java.util.ArrayList<FinanceEffectivePriceService.SourceRule>();
    if ("LINKED".equals(type)) {
      for (var row : linkedItems.selectBatchIds(ids)) {
        requireBusinessUnit(row.getBusinessUnitType());
        if (!"PUBLIC".equals(row.getSourceKind())
            || !"PUBLISHED".equals(row.getRuleStatus())
            || !OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion())
            || Integer.valueOf(1).equals(row.getDeleted()))
          throw new IllegalArgumentException("只能读取正式公共联动原稿的有效价");
        rules.add(FinanceEffectivePriceService.SourceRule.linked(row));
      }
    } else if ("RANGE".equals(type)) {
      for (var row : rangeRules.selectBatchIds(ids)) {
        requireBusinessUnit(row.getBusinessUnitType());
        if (!OriginalRangeRuleSnapshot.isOriginal(row))
          throw new IllegalArgumentException("只能读取正式区间原稿的有效价");
        rules.add(FinanceEffectivePriceService.SourceRule.range(row));
      }
    } else throw new IllegalArgumentException("不支持的有效价类型");
    if (rules.size() != ids.size()) throw new IllegalArgumentException("部分原稿不存在或不在可访问范围");
    record Scope(String businessUnit, String organization) {}
    var result = new java.util.ArrayList<EffectiveRow>();
    rules.stream()
        .filter(r -> r.month().compareTo(month) > 0)
        .forEach(r -> result.add(unavailable(r, "NOT_APPLICABLE", "原稿月份晚于查询月份")));
    var groups =
        rules.stream()
            .filter(r -> r.month().compareTo(month) <= 0)
            .collect(
                java.util.stream.Collectors.groupingBy(
                    r -> new Scope(r.key().businessUnitType(), r.key().orgCode()),
                    java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.toList()));
    for (var group : groups.values()) {
      java.util.Map<Long, FinanceEffectivePrice> prices;
      try {
        var first = group.getFirst();
        var financial =
            finance.readDefault(
                new FinanceFactorImportContext(
                    first.key().businessUnitType(), first.key().orgCode(), month, null, null),
                first.key().materialCode());
        prices =
            effectivePrices.read(
                type, group.stream().map(r -> r.id()).toList(), financial.financial());
      } catch (FinanceFactorPriceReader.MissingPublicationException missing) {
        group.forEach(r -> result.add(unavailable(r, "WAIT_FINANCE_BASE", "原稿已保存，待发布适用财务基价")));
        continue;
      } catch (IllegalArgumentException invalid) {
        group.forEach(r -> result.add(unavailable(r, "ERROR", invalid.getMessage())));
        continue;
      }
      for (var rule : group) {
        try {
          var price = effectivePrices.require(rule, prices);
          var saved = "RANGE".equals(type) ? effectivePrices.calculation(price).range() : null;
          result.add(
              new EffectiveRow(
                  rule.id(),
                  "READY",
                  null,
                  price.getUnitPrice(),
                  price.getId(),
                  rule.month(),
                  price.getFinanceMonth(),
                  price.getSchemeCode(),
                  price.getConditionCode(),
                  saved == null ? null : saved.selectedBounds()));
        } catch (IllegalArgumentException invalid) {
          result.add(unavailable(rule, "ERROR", invalid.getMessage()));
        }
      }
    }
    return java.util.List.copyOf(result);
  }

  private EffectiveRow unavailable(
      FinanceEffectivePriceService.SourceRule rule, String status, String message) {
    return new EffectiveRow(
        rule.id(), status, message, null, null, rule.month(), null, null, null, null);
  }

  private String sourceFile(Long batchId) {
    var batch = batchId == null ? null : batches.selectById(batchId);
    if (batch == null) throw new IllegalArgumentException("原稿来源批次缺失");
    return batch.getFileName();
  }

  private PriceFactorContext savedContext(PriceFactorContext saved, String month) {
    return new PriceFactorContext(
        saved.materialCode(),
        new com.sanhua.marketingcost.service.pricing.PriceMonthSelection(
            month, saved.months().sourceMonth()),
        saved.financial(),
        null,
        saved.boardInputs());
  }

  private PriceFactorContext context(MaterialPriceRuleKey key, String ruleMonth, Request request) {
    if (request == null) throw new IllegalArgumentException("请选择试算月份");
    var month = FactorPriceScope.month(request.pricingMonth());
    if (month.compareTo(ruleMonth) < 0) throw new IllegalArgumentException("试算月份不能早于原稿月份");
    return finance.readDefault(
        new FinanceFactorImportContext(key.businessUnitType(), key.orgCode(), month, null, null),
        key.materialCode());
  }

  private Summary summary(
      MaterialPriceRuleKey key,
      String ruleMonth,
      PriceFactorContext context,
      Request request,
      BigDecimal p0,
      BigDecimal p1) {
    BigDecimal copper =
        context.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_TON, true);
    return new Summary(
        key.materialCode(),
        key.unit(),
        ruleMonth,
        context.months().requestedMonth(),
        context.months().sourceMonth(),
        copper,
        request.customerCopperPerTon() == null ? copper : request.customerCopperPerTon(),
        p0,
        p1,
        p1.subtract(p0));
  }

  private void requireBusinessUnit(String businessUnit) {
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!BusinessUnitContext.isAdmin() && (current == null || !current.equals(businessUnit)))
      throw new IllegalArgumentException("不能读取其他业务单元的原稿");
  }

  public record Request(String pricingMonth, BigDecimal customerCopperPerTon) {}

  public record Summary(
      String materialCode,
      String unit,
      String ruleMonth,
      String pricingMonth,
      String financialSourceMonth,
      BigDecimal financeCopperPerTon,
      BigDecimal customerCopperPerTon,
      BigDecimal financePrice,
      BigDecimal customerPrice,
      BigDecimal difference) {}

  public record LinkedTrial(
      Summary summary,
      OriginalLinkedWorkbookParser.Rule rule,
      OriginalLinkedRuleCalculator.Result finance,
      OriginalLinkedRuleCalculator.Result customer,
      PriceFactorContext context,
      String sourceFile) {}

  public record RangeTrial(
      Summary summary,
      OriginalRangeRuleSnapshot.SavedRule saved,
      OriginalRangeRuleCalculator.Result finance,
      OriginalRangeRuleCalculator.Result customer,
      PriceFactorContext context,
      String sourceFile) {}
}
