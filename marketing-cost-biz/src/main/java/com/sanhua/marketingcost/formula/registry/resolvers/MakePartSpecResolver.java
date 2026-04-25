package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 制造件工艺参数变量解析器 —— 骨架实现，等 Task #8 建表 {@code lp_make_part_spec} 后填充。
 *
 * <p>预期字段：净重 / 下料重 / 废品率 / 加工费 / formula_id。
 */
@Component
public class MakePartSpecResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(MakePartSpecResolver.class);

  @Override
  public String sourceType() {
    return "MAKE_PART_SPEC";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    log.warn("MakePartSpecResolver 尚未接入 lp_make_part_spec (Task #8): variable={}",
        variable == null ? null : variable.getVariableCode());
    return null;
  }
}
