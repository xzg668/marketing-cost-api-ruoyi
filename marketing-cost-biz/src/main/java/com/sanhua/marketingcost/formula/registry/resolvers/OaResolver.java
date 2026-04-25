package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

/**
 * OA 表单变量解析器 —— 读取 {@link OaForm} 中字段（铜价/锌价/铝价等）。
 *
 * <p>OA 表单价格字段以"元/吨"录入；返回时按惯例除以 1000 转为"元/克"，
 * 与 Excel 联动公式中的金属基价口径保持一致。
 */
@Component
public class OaResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(OaResolver.class);
  /** OA 录入单位 元/吨，公式按 元/克 计 */
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");

  @Override
  public String sourceType() {
    return "OA_FORM";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    OaForm form = ctx == null ? null : ctx.getOaForm();
    if (form == null || variable == null || variable.getSourceField() == null) {
      return null;
    }
    BigDecimal raw = readDecimal(form, variable.getSourceField().trim());
    if (raw == null) {
      log.debug("OaResolver 未找到字段值: variable={}, field={}",
          variable.getVariableCode(), variable.getSourceField());
      return null;
    }
    return raw.divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
  }

  /** 反射读取 OaForm 字段值，支持下划线和驼峰两种命名 */
  private BigDecimal readDecimal(OaForm form, String field) {
    BeanWrapperImpl wrapper = new BeanWrapperImpl(form);
    Object raw = null;
    if (wrapper.isReadableProperty(field)) {
      raw = wrapper.getPropertyValue(field);
    } else {
      String camel = toCamel(field);
      if (wrapper.isReadableProperty(camel)) {
        raw = wrapper.getPropertyValue(camel);
      }
    }
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return null;
  }

  private String toCamel(String snake) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (char ch : snake.toCharArray()) {
      if (ch == '_') {
        upper = true;
      } else {
        sb.append(upper ? Character.toUpperCase(ch) : ch);
        upper = false;
      }
    }
    return sb.toString();
  }
}
