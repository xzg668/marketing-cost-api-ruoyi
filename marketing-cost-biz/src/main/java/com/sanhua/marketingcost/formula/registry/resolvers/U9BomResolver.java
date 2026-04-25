package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * U9 ERP 物料 BOM 变量解析器 —— 骨架实现。
 *
 * <p>预期数据源：U9 接口同步进 {@code lp_u9_bom_*} 表（用量、单耗等）。
 * 接入前返回 null，避免阻塞公式注册流程。
 */
@Component
public class U9BomResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(U9BomResolver.class);

  @Override
  public String sourceType() {
    return "U9_BOM";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    log.warn("U9BomResolver 尚未接入 U9 接口: variable={}, materialCode={}",
        variable == null ? null : variable.getVariableCode(),
        ctx == null ? null : ctx.getMaterialCode());
    return null;
  }
}
