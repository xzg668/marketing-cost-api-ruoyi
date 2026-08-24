package com.sanhua.marketingcost.formula.registry.resolvers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.FinanceBasePriceQuery;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 金属基价解析器（T26c）—— 按权威源从 {@code lp_finance_base_price} 精确查价。
 *
 * <p>语义定位：OA 锁价路径（由 Calc/Preview 服务将 oaForm 展开到 {@code ctx.overrides}
 * 处理，优先级高于任何 resolver）之下，金属基价变量（Cu/Zn/Sn/Al/Cn）的正式数据源。
 *
 * <p>查询键与沿用规则：
 * <ol>
 *   <li>{@code factor_code} —— 来自 variable 的 {@code context_binding_json.factorCode}</li>
 *   <li>{@code price_month <= ctx.pricingMonth}，取最近一条正式价</li>
 *   <li>{@code price_source} —— 来自 variable 的 {@code context_binding_json.priceSource}
 *       （如 "长江现货平均价" / "SMM平均价"）</li>
 *   <li>{@code business_unit_type} —— 来自 {@link BusinessUnitContext}（Mapper 层未挂
 *       {@code @DataScope}，在此显式 eq 保证租户隔离）</li>
 * </ol>
 *
 * <p>严格身份、允许历史月份沿用：
 * <ul>
 *   <li>因素、价源和业务单元严格匹配，不允许降级到其他价源</li>
 *   <li>核算月无新审批价时沿用最近正式价，保证报价不中断</li>
 *   <li>严格身份范围内仍查不到 → 返回 null，由上层标记 MISSING</li>
 *   <li>priceMonth 缺失、factorCode 缺失、BU 缺失任何一项 → 直接返回 null 并记 warn</li>
 * </ul>
 */
@Component
public class FinanceBaseResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(FinanceBaseResolver.class);

  private static final TypeReference<Map<String, Object>> BINDING_TYPE =
      new TypeReference<>() {};

  private final FinanceBasePriceQuery financeBasePriceQuery;
  private final ObjectMapper objectMapper;

  public FinanceBaseResolver(
      FinanceBasePriceQuery financeBasePriceQuery, ObjectMapper objectMapper) {
    this.financeBasePriceQuery = financeBasePriceQuery;
    this.objectMapper = objectMapper;
  }

  @Override
  public String sourceType() {
    return "FINANCE_BASE";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    if (variable == null || ctx == null) {
      return null;
    }
    // 1) 解析 context_binding_json，拿到 factorCode + priceSource
    Map<String, Object> binding = parseBinding(variable);
    if (binding == null) {
      log.warn("FINANCE_BASE 变量 {} 缺 context_binding_json，无法路由基价表",
          variable.getVariableCode());
      return null;
    }
    String factorCode = asString(binding.get("factorCode"));
    String priceSource = asString(binding.get("priceSource"));
    if (isBlank(factorCode) || isBlank(priceSource)) {
      log.warn("FINANCE_BASE 变量 {} context_binding_json 缺 factorCode 或 priceSource: {}",
          variable.getVariableCode(), binding);
      return null;
    }

    // 2) 拿 pricingMonth（必填）
    String priceMonth = ctx.getPricingMonth();
    if (isBlank(priceMonth)) {
      log.warn("FINANCE_BASE 变量 {} 缺 ctx.pricingMonth，无法精确到月份",
          variable.getVariableCode());
      return null;
    }

    // 3) 拿当前租户 BU（必填，避免跨 BU 污染）
    String bu = BusinessUnitContext.getCurrentBusinessUnitType();
    if (isBlank(bu)) {
      log.warn("FINANCE_BASE 变量 {} 当前请求无 businessUnitType（系统级调用？），拒绝查询",
          variable.getVariableCode());
      return null;
    }

    // 4) 与 FactorVariableRegistry 共用同一查询：身份严格，月份取 <= 核算月的最近正式价。
    return financeBasePriceQuery.queryLatestBasePrice(
            factorCode,
            null,
            priceSource,
            true,
            priceMonth,
            bu,
            variable.getVariableCode())
        .map(FinanceBasePrice::getPrice)
        .orElse(null);
  }

  /** 解析 PriceVariable.context_binding_json 为 Map；null/空字符串/非法 JSON 返回 null */
  private Map<String, Object> parseBinding(PriceVariable variable) {
    String json = variable.getContextBindingJson();
    if (isBlank(json)) {
      return null;
    }
    try {
      return objectMapper.readValue(json, BINDING_TYPE);
    } catch (Exception e) {
      log.warn("FINANCE_BASE 变量 {} 的 context_binding_json 解析失败: {} —— {}",
          variable.getVariableCode(), json, e.getMessage());
      return null;
    }
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
