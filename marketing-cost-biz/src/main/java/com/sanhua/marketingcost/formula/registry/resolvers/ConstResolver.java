package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 常量解析器 —— 直接返回 {@link PriceVariable#getDefaultValue()}。
 *
 * <p>用途：vat_rate（增值税）、固定费率系数、配置开关阈值等"非业务表"的取值。
 */
@Component
public class ConstResolver implements VariableResolver {

  @Override
  public String sourceType() {
    return "CONST";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    return variable == null ? null : variable.getDefaultValue();
  }
}
