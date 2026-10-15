package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
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

  public DerivedResolver(
      FinanceFactorVariableQuery financeFactors, MaterialScrapRefMapper materialScrapRefMapper) {
    this.financeFactors = financeFactors;
    this.materialScrapRefMapper = materialScrapRefMapper;
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
        return resolveScrapRef(variable, ctx);
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

  /** 废料派生：materialCode → scrap_ref.scrap_code → 财务因素，最后乘 ratio */
  Optional<BigDecimal> resolveScrapRef(PriceVariable variable, VariableContext ctx) {
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null || isBlank(item.getMaterialCode())) {
      log.debug("SCRAP_REF 变量 {} 缺 linkedItem.materialCode", variable.getVariableCode());
      return Optional.empty();
    }
    String materialCode = item.getMaterialCode().trim();
    MaterialScrapRef ref =
        materialScrapRefMapper.selectOne(
            Wrappers.lambdaQuery(MaterialScrapRef.class)
                .eq(MaterialScrapRef::getMaterialCode, materialCode)
                .orderByDesc(MaterialScrapRef::getId)
                .last("LIMIT 1"));
    if (ref == null || isBlank(ref.getScrapCode())) {
      log.debug(
          "SCRAP_REF 变量 {} 未在 lp_material_scrap_ref 找到 {} 的映射",
          variable.getVariableCode(),
          materialCode);
      return Optional.empty();
    }
    Optional<BigDecimal> priceOpt = financeFactors.queryName(ref.getScrapCode().trim(), ctx);
    if (priceOpt.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal ratio = ref.getRatio();
    BigDecimal base = priceOpt.get();
    if (ratio != null) ctx.getPrimitiveInputs().put(variable.getVariableCode() + ":ratio", ratio);
    if (ratio == null) {
      return Optional.of(base);
    }
    return Optional.of(base.multiply(ratio));
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
