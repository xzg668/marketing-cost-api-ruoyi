package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PART_CONTEXT DERIVED 派生策略实现（T10）—— 被
 * {@link FactorVariableRegistryImpl#resolvePartContext} 委托。
 *
 * <p>四种策略（与 V24 seed 一一对应）：
 * <ol>
 *   <li>{@code MAIN_MATERIAL_FINANCE}（材料含税价格）：用 {@code linkedItem.materialCode}
 *       作为 finance 表 short_name（未命中再回退 factor_code）查当期或最新价格</li>
 *   <li>{@code SCRAP_REF}（废料含税价格）：{@code linkedItem.materialCode} →
 *       {@code lp_material_scrap_ref.scrap_code} → finance 表，派生值再乘 {@code ratio}</li>
 *   <li>{@code FORMULA_REF}（铜沫价格）：取 binding 中 {@code formulaRef} 字符串，
 *       用 {@link ExpressionEvaluator} 求值；子变量递归走完整注册表</li>
 *   <li>{@code FINANCE_FACTOR}（直指派生）：按 binding 中 {@code factorCode} 查 finance 表</li>
 * </ol>
 *
 * <p>任何派生缺数据（materialCode 为空、映射未配、finance 表无记录）都以
 * {@link Optional#empty()} 返回，调用方按 0 或跳过处理；真正的解析错误（如 formula 语法）
 * 让 {@link ExpressionEvaluator} 原样抛异常，由上层 trace 捕获。
 */
@Component
public class DerivedResolver implements FactorVariableRegistryImpl.DerivedContextResolver {

  private static final Logger log = LoggerFactory.getLogger(DerivedResolver.class);

  static final String STRATEGY_MAIN_MATERIAL = "MAIN_MATERIAL_FINANCE";
  static final String STRATEGY_SCRAP_REF = "SCRAP_REF";
  static final String STRATEGY_FORMULA_REF = "FORMULA_REF";
  static final String STRATEGY_FINANCE_FACTOR = "FINANCE_FACTOR";

  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final MaterialScrapRefMapper materialScrapRefMapper;

  public DerivedResolver(
      FinanceBasePriceMapper financeBasePriceMapper,
      MaterialScrapRefMapper materialScrapRefMapper) {
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.materialScrapRefMapper = materialScrapRefMapper;
  }

  @Override
  public Optional<BigDecimal> resolve(
      PriceVariable variable, VariableContext ctx, Map<String, Object> binding,
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

  /** 主材料派生：linkedItem.materialCode → finance 表按 short_name / factor_code 命中 */
  Optional<BigDecimal> resolveMainMaterialFinance(PriceVariable variable, VariableContext ctx) {
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null || isBlank(item.getMaterialCode())) {
      log.debug("MAIN_MATERIAL_FINANCE 变量 {} 缺 linkedItem.materialCode",
          variable.getVariableCode());
      return Optional.empty();
    }
    String materialCode = item.getMaterialCode().trim();
    String month = ctx.getPricingMonth();
    Optional<BigDecimal> hit = financeByShortName(materialCode, month);
    if (hit.isPresent()) {
      return hit;
    }
    // 回退：按 factor_code 再查一次（部分老数据 short_name 留空）
    return financeByFactorCode(materialCode, month);
  }

  /** 废料派生：materialCode → scrap_ref.scrap_code → finance，最后乘 ratio */
  Optional<BigDecimal> resolveScrapRef(PriceVariable variable, VariableContext ctx) {
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null || isBlank(item.getMaterialCode())) {
      log.debug("SCRAP_REF 变量 {} 缺 linkedItem.materialCode", variable.getVariableCode());
      return Optional.empty();
    }
    String materialCode = item.getMaterialCode().trim();
    MaterialScrapRef ref = materialScrapRefMapper.selectOne(
        Wrappers.lambdaQuery(MaterialScrapRef.class)
            .eq(MaterialScrapRef::getMaterialCode, materialCode)
            .orderByDesc(MaterialScrapRef::getId)
            .last("LIMIT 1"));
    if (ref == null || isBlank(ref.getScrapCode())) {
      log.debug("SCRAP_REF 变量 {} 未在 lp_material_scrap_ref 找到 {} 的映射",
          variable.getVariableCode(), materialCode);
      return Optional.empty();
    }
    Optional<BigDecimal> priceOpt = financeByShortName(ref.getScrapCode().trim(), ctx.getPricingMonth());
    if (priceOpt.isEmpty()) {
      priceOpt = financeByFactorCode(ref.getScrapCode().trim(), ctx.getPricingMonth());
    }
    if (priceOpt.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal ratio = ref.getRatio();
    BigDecimal base = priceOpt.get();
    if (ratio == null) {
      return Optional.of(base);
    }
    return Optional.of(base.multiply(ratio));
  }

  /**
   * 公式派生：binding.formulaRef 是一个含 {@code [变量]} 的表达式，如 {@code [Cu]*0.59+[Zn]*0.41}。
   *
   * <p>子变量值优先查 {@code ctx.overrides}（手工兜底）；其次走 {@code subResolver} 递归回到
   * 主 registry 完整解析，让派生公式里的 Cu/Zn 等能自动命中财务表；仍取不到按 0 处理。
   */
  Optional<BigDecimal> resolveFormulaRef(
      PriceVariable variable, VariableContext ctx, Map<String, Object> binding,
      FactorVariableRegistryImpl.DerivedContextResolver.SubResolver subResolver) {
    String expr = asString(binding.get("formulaRef"));
    if (isBlank(expr)) {
      log.warn("FORMULA_REF DERIVED 变量 {} 缺 formulaRef", variable.getVariableCode());
      return Optional.empty();
    }
    var tokens = ExpressionEvaluator.extractVariables(expr);
    Map<String, BigDecimal> values = new HashMap<>();
    Map<String, BigDecimal> overrides =
        ctx == null ? Map.of() : ctx.getOverrides();
    for (String token : tokens) {
      BigDecimal v = overrides.get(token);
      if (v == null && subResolver != null) {
        v = subResolver.resolve(token).orElse(null);
      }
      values.put(token, v == null ? BigDecimal.ZERO : v);
    }
    try {
      BigDecimal result = ExpressionEvaluator.evaluate(expr, values);
      return Optional.ofNullable(result);
    } catch (RuntimeException e) {
      log.warn("DERIVED 变量 {} 公式 {} 求值失败: {}",
          variable.getVariableCode(), expr, e.getMessage());
      return Optional.empty();
    }
  }

  /** 直指派生：binding.factorCode 直接作为 finance 表 short_name / factor_code 查询 */
  Optional<BigDecimal> resolveFinanceFactorDirect(
      PriceVariable variable, VariableContext ctx, Map<String, Object> binding) {
    String factorCode = asString(binding.get("factorCode"));
    if (isBlank(factorCode)) {
      log.warn("FINANCE_FACTOR DERIVED 变量 {} 缺 factorCode", variable.getVariableCode());
      return Optional.empty();
    }
    String month = ctx == null ? null : ctx.getPricingMonth();
    Optional<BigDecimal> hit = financeByShortName(factorCode.trim(), month);
    if (hit.isPresent()) {
      return hit;
    }
    return financeByFactorCode(factorCode.trim(), month);
  }

  /** finance 表按 short_name 查询：精确月 → 最新月回退 */
  private Optional<BigDecimal> financeByShortName(String shortName, String month) {
    if (month != null && !month.isBlank()) {
      FinanceBasePrice exact = financeBasePriceMapper.selectOne(
          Wrappers.lambdaQuery(FinanceBasePrice.class)
              .eq(FinanceBasePrice::getShortName, shortName)
              .eq(FinanceBasePrice::getPriceMonth, month.trim())
              .orderByDesc(FinanceBasePrice::getId)
              .last("LIMIT 1"));
      if (exact != null) {
        return Optional.ofNullable(exact.getPrice());
      }
    }
    List<FinanceBasePrice> rows = financeBasePriceMapper.selectList(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getShortName, shortName)
            .orderByDesc(FinanceBasePrice::getPriceMonth)
            .orderByDesc(FinanceBasePrice::getId)
            .last("LIMIT 1"));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(rows.get(0).getPrice());
  }

  /** finance 表按 factor_code 查询（short_name 未命中时的回退路径） */
  private Optional<BigDecimal> financeByFactorCode(String factorCode, String month) {
    if (month != null && !month.isBlank()) {
      FinanceBasePrice exact = financeBasePriceMapper.selectOne(
          Wrappers.lambdaQuery(FinanceBasePrice.class)
              .eq(FinanceBasePrice::getFactorCode, factorCode)
              .eq(FinanceBasePrice::getPriceMonth, month.trim())
              .orderByDesc(FinanceBasePrice::getId)
              .last("LIMIT 1"));
      if (exact != null) {
        return Optional.ofNullable(exact.getPrice());
      }
    }
    List<FinanceBasePrice> rows = financeBasePriceMapper.selectList(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getFactorCode, factorCode)
            .orderByDesc(FinanceBasePrice::getPriceMonth)
            .orderByDesc(FinanceBasePrice::getId)
            .last("LIMIT 1"));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(rows.get(0).getPrice());
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
