package com.sanhua.marketingcost.formula.registry.resolvers;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 财务基准价解析器 —— 读取 {@code lp_finance_base_price}。
 *
 * <p>变量元数据约定：
 * <ul>
 *   <li>{@code variable.sourceTable = "lp_finance_base_price"}（用于校验）</li>
 *   <li>{@code variable.variableName} = 财务表中的 short_name（如"美国柜装黄铜"）</li>
 * </ul>
 *
 * <p>取数策略：按 {@code ctx.pricingMonth} 精确匹配；缺月份则取最新月（按 price_month desc）。
 */
@Component
public class FinancePriceResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(FinancePriceResolver.class);

  private final FinanceBasePriceMapper mapper;

  public FinancePriceResolver(FinanceBasePriceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String sourceType() {
    return "FACTOR";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    if (variable == null || variable.getVariableName() == null) {
      return null;
    }
    String shortName = variable.getVariableName().trim();
    String month = ctx == null ? null : ctx.getPricingMonth();
    var query = Wrappers.lambdaQuery(FinanceBasePrice.class)
        .eq(FinanceBasePrice::getShortName, shortName)
        .orderByDesc(FinanceBasePrice::getPriceMonth)
        .orderByDesc(FinanceBasePrice::getId);
    if (month != null && !month.isBlank()) {
      // 优先精确月：直接取该月唯一记录
      var exact = Wrappers.lambdaQuery(FinanceBasePrice.class)
          .eq(FinanceBasePrice::getShortName, shortName)
          .eq(FinanceBasePrice::getPriceMonth, month.trim())
          .orderByDesc(FinanceBasePrice::getId).last("LIMIT 1");
      FinanceBasePrice hit = mapper.selectOne(exact);
      if (hit != null) {
        return hit.getPrice();
      }
      log.debug("FinancePriceResolver 月份 {} 无 {} 记录，回退最新", month, shortName);
    }
    List<FinanceBasePrice> rows = mapper.selectList(query.last("LIMIT 1"));
    if (rows.isEmpty()) {
      return null;
    }
    return rows.get(0).getPrice();
  }
}
