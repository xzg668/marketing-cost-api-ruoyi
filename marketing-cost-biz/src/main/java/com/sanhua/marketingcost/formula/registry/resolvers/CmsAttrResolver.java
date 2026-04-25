package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CMS 物料属性变量解析器 —— 骨架实现。
 *
 * <p>预期数据源：CMS 物料主数据 (lp_cms_attr_*)，提供材质系数、规格属性等。
 * 接入前返回 null。
 */
@Component
public class CmsAttrResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(CmsAttrResolver.class);

  @Override
  public String sourceType() {
    return "CMS_ATTR";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    log.warn("CmsAttrResolver 尚未接入 CMS 物料主数据: variable={}, materialCode={}",
        variable == null ? null : variable.getVariableCode(),
        ctx == null ? null : ctx.getMaterialCode());
    return null;
  }
}
