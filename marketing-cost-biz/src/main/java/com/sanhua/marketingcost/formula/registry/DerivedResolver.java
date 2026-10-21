package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 技术公式的主材、废料及公式派生规则。财务因素统一读取本次范围内的已发布方案。 */
@Component
public class DerivedResolver implements FactorVariableRegistryImpl.DerivedContextResolver {

  private static final Logger log = LoggerFactory.getLogger(DerivedResolver.class);

  static final String STRATEGY_MAIN_MATERIAL = "MAIN_MATERIAL_FINANCE";
  static final String STRATEGY_SCRAP_REF = "SCRAP_REF";
  static final String STRATEGY_FORMULA_REF = "FORMULA_REF";
  static final String STRATEGY_FINANCE_FACTOR = "FINANCE_FACTOR";

  private final FinanceFactorVariableQuery financeFactors;
  private final MaterialScrapRefMapper materialScrapRefMapper;
  private final ScrapPriceSelectionService scrapPrices;

  public DerivedResolver(
      FinanceFactorVariableQuery financeFactors,
      MaterialScrapRefMapper materialScrapRefMapper,
      ScrapPriceSelectionService scrapPrices) {
    this.financeFactors = financeFactors;
    this.materialScrapRefMapper = materialScrapRefMapper;
    this.scrapPrices = scrapPrices;
  }

  @Override
  public Optional<BigDecimal> resolve(
      PriceVariable variable,
      VariableContext ctx,
      Map<String, Object> binding,
      SubResolver subResolver) {
    String strategy = asString(binding.get("strategy"));
    if (strategy == null) {
      log.warn("DERIVED 变量 {} 未声明 strategy", variable.getVariableCode());
      return Optional.empty();
    }
    switch (strategy.toUpperCase()) {
      case STRATEGY_MAIN_MATERIAL:
        return resolveMainMaterialFinance(variable, ctx);
      case STRATEGY_SCRAP_REF:
        return resolveScrapRef(variable, ctx, subResolver);
      case STRATEGY_FORMULA_REF:
        return resolveFormulaRef(variable, ctx, binding, subResolver);
      case STRATEGY_FINANCE_FACTOR:
        return resolveFinanceFactorDirect(variable, ctx, binding);
      default:
        log.warn("DERIVED 变量 {} 未知 strategy={}", variable.getVariableCode(), strategy);
        return Optional.empty();
    }
  }

  /** 主材料派生：linkedItem.materialCode → 已发布财务因素唯一身份 */
  Optional<BigDecimal> resolveMainMaterialFinance(PriceVariable variable, VariableContext ctx) {
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null || isBlank(item.getMaterialCode())) {
      log.debug(
          "MAIN_MATERIAL_FINANCE 变量 {} 缺 linkedItem.materialCode", variable.getVariableCode());
      return Optional.empty();
    }
    return financeFactors.queryName(item.getMaterialCode().trim(), ctx);
  }

  /** 映射只提供废料身份和比例；价格与税价依据来自独立废料快照。 */
  Optional<BigDecimal> resolveScrapRef(
      PriceVariable variable, VariableContext ctx, SubResolver subResolver) {
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null || isBlank(item.getMaterialCode())) {
      log.debug("SCRAP_REF 变量 {} 缺 linkedItem.materialCode", variable.getVariableCode());
      return Optional.empty();
    }
    String materialCode = item.getMaterialCode().trim();
    String businessUnit = item.getBusinessUnitType();
    String month = ctx.getPricingMonth();
    var date =
        ctx.getQuoteDate() == null ? java.time.YearMonth.parse(month).atDay(1) : ctx.getQuoteDate();
    var references =
        materialScrapRefMapper.selectList(
            Wrappers.lambdaQuery(MaterialScrapRef.class)
                .eq(MaterialScrapRef::getMaterialCode, materialCode)
                .eq(MaterialScrapRef::getBusinessUnitType, businessUnit)
                .and(
                    q ->
                        q.isNull(MaterialScrapRef::getEffectiveFrom)
                            .or()
                            .le(MaterialScrapRef::getEffectiveFrom, date))
                .and(
                    q ->
                        q.isNull(MaterialScrapRef::getEffectiveTo)
                            .or()
                            .ge(MaterialScrapRef::getEffectiveTo, date))
                .orderByAsc(MaterialScrapRef::getId));
    if (references.isEmpty()) {
      log.debug(
          "SCRAP_REF 变量 {} 未在 lp_material_scrap_ref 找到 {} 的映射",
          variable.getVariableCode(),
          materialCode);
      return Optional.empty();
    }
    MaterialScrapRef ref = references.getFirst();
    BigDecimal ratio = ref.getRatio() == null ? BigDecimal.ONE : ref.getRatio();
    if (isBlank(ref.getScrapCode()) || ratio.signum() < 0)
      throw new IllegalArgumentException("废料映射缺料号或抵减比例无效：" + materialCode);
    for (var other : references) {
      BigDecimal otherRatio = other.getRatio() == null ? BigDecimal.ONE : other.getRatio();
      if (!ref.getScrapCode().equals(other.getScrapCode()) || ratio.compareTo(otherRatio) != 0)
        throw new IllegalArgumentException("公式废料变量存在多个有效映射，请明确废料：" + materialCode);
    }
    String oaNo =
        ctx.getOaNo() == null && ctx.getOaForm() != null
            ? ctx.getOaForm().getOaNo()
            : ctx.getOaNo();
    var selection =
        scrapPrices.selectForFormula(
            businessUnit,
            month,
            ref.getScrapCode().trim(),
            item.getOrgCode(),
            oaNo,
            date,
            ctx.isFinanceQuoteBase(),
            ctx.getSupplierReviews());
    BigDecimal taxMultiplier = BigDecimal.ONE;
    if ("INCL".equals(variable.getTaxMode())) {
      BigDecimal vat = subResolver == null ? null : subResolver.resolve("vat_rate").orElse(null);
      if (vat == null || vat.signum() < 0 || vat.compareTo(BigDecimal.ONE) > 0)
        throw new IllegalArgumentException("废料含税变量缺少有效vat_rate：" + variable.getVariableCode());
      taxMultiplier = BigDecimal.ONE.add(vat);
    } else if (!"EXCL".equals(variable.getTaxMode())) {
      throw new IllegalArgumentException("废料价格变量必须声明INCL或EXCL税价口径：" + variable.getVariableCode());
    }
    ctx.getPrimitiveInputs().put(variable.getVariableCode() + ":ratio", ratio);
    ctx.getPrimitiveInputs().put(variable.getVariableCode() + ":taxMultiplier", taxMultiplier);
    ctx.getScrapPriceInputs()
        .put(variable.getVariableCode(), selection.evidence().inputSnapshotJson());
    ctx.resolvedSource(variable.getVariableCode(), "SCRAP_PRICE");
    return Optional.of(selection.unitPrice().multiply(taxMultiplier).multiply(ratio));
  }

  /**
   * 公式派生：binding.formulaRef 是一个含 {@code [变量]} 的表达式，如 {@code [Cu]*0.59+[Zn]*0.41}。
   *
   * <p>子变量值优先查 {@code ctx.overrides}（手工兜底）；其次走 {@code subResolver} 递归回到 主 registry 完整解析，让派生公式里的
   * Cu/Zn 等能自动命中财务表；缺失依赖明确失败。
   */
  Optional<BigDecimal> resolveFormulaRef(
      PriceVariable variable,
      VariableContext ctx,
      Map<String, Object> binding,
      FactorVariableRegistryImpl.DerivedContextResolver.SubResolver subResolver) {
    String expr = asString(binding.get("formulaRef"));
    if (isBlank(expr)) {
      log.warn("FORMULA_REF DERIVED 变量 {} 缺 formulaRef", variable.getVariableCode());
      return Optional.empty();
    }
    var tokens = ExpressionEvaluator.extractVariables(expr);
    Map<String, BigDecimal> values = new HashMap<>();
    Map<String, BigDecimal> overrides = ctx == null ? Map.of() : ctx.getOverrides();
    for (String token : tokens) {
      BigDecimal v =
          subResolver == null ? overrides.get(token) : subResolver.resolve(token).orElse(null);
      if (v == null) {
        throw new IllegalArgumentException("派生变量=" + variable.getVariableCode() + "，缺少依赖=" + token);
      }
      values.put(token, v);
    }
    try {
      BigDecimal result = ExpressionEvaluator.evaluate(expr, values);
      return Optional.ofNullable(result);
    } catch (RuntimeException e) {
      log.warn("DERIVED 变量 {} 公式 {} 求值失败: {}", variable.getVariableCode(), expr, e.getMessage());
      return Optional.empty();
    }
  }

  /** 直指派生：binding.factorCode 直接作为 已发布财务因素查询 */
  Optional<BigDecimal> resolveFinanceFactorDirect(
      PriceVariable variable, VariableContext ctx, Map<String, Object> binding) {
    String factorCode = asString(binding.get("factorCode"));
    if (isBlank(factorCode)) {
      log.warn("FINANCE_FACTOR DERIVED 变量 {} 缺 factorCode", variable.getVariableCode());
      return Optional.empty();
    }
    return financeFactors.queryName(factorCode.trim(), ctx);
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
