package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 称重系统变量解析器 —— 骨架实现，等称重源表 (lp_weighing_record) 接入后填充查询逻辑。
 *
 * <p>当前阶段：始终返回 null + WARN 日志；保证路由表完整、无 NPE。
 * 真实接入由"称重数据归集"工单触发后再实现。
 */
@Component
public class WeighingResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(WeighingResolver.class);

  @Override
  public String sourceType() {
    return "WEIGHING";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    log.warn("WeighingResolver 尚未接入称重数据源: variable={}, materialCode={}",
        variable == null ? null : variable.getVariableCode(),
        ctx == null ? null : ctx.getMaterialCode());
    return null;
  }
}
