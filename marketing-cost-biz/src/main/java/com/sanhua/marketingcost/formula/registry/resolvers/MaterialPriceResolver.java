package com.sanhua.marketingcost.formula.registry.resolvers;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

/**
 * 物料价表变量解析器 —— 读 {@code lp_price_linked_item} 当前 ctx 物料的对应字段。
 *
 * <p>支持字段：blank_weight / net_weight / process_fee / agent_fee / manual_price 等；
 * 重量字段 (blank_weight/net_weight) 自动除以 1000（克→千克对齐）。
 *
 * <p>命中顺序：优先用 {@link VariableContext#getLinkedItem()} 已加载的对象（避免 N+1）；
 * 回退到 mapper 按 materialCode 查最新一条。
 */
@Component
public class MaterialPriceResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(MaterialPriceResolver.class);
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");

  private final PriceLinkedItemMapper mapper;

  public MaterialPriceResolver(PriceLinkedItemMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String sourceType() {
    return "LINKED_ITEM";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    if (variable == null || variable.getSourceField() == null) {
      return null;
    }
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null && ctx != null && ctx.getMaterialCode() != null) {
      List<PriceLinkedItem> rows = mapper.selectList(
          Wrappers.lambdaQuery(PriceLinkedItem.class)
              .eq(PriceLinkedItem::getMaterialCode, ctx.getMaterialCode().trim())
              .orderByDesc(PriceLinkedItem::getUpdatedAt)
              .orderByDesc(PriceLinkedItem::getId)
              .last("LIMIT 1"));
      if (!rows.isEmpty()) {
        item = rows.get(0);
      }
    }
    if (item == null) {
      log.debug("MaterialPriceResolver 未取到 PriceLinkedItem: variable={}, mat={}",
          variable.getVariableCode(),
          ctx == null ? null : ctx.getMaterialCode());
      return null;
    }
    BigDecimal raw = readDecimal(item, variable.getSourceField().trim());
    return adjustWeight(variable.getSourceField().trim(), raw);
  }

  private BigDecimal readDecimal(PriceLinkedItem item, String field) {
    BeanWrapperImpl wrapper = new BeanWrapperImpl(item);
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

  /** 重量字段统一除 1000 (克→千克) */
  private BigDecimal adjustWeight(String field, BigDecimal value) {
    if (value == null) {
      return null;
    }
    String normalized = field.toLowerCase().replace("_", "");
    if ("blankweight".equals(normalized) || "netweight".equals(normalized)) {
      return value.divide(WEIGHT_DIVISOR, 8, java.math.RoundingMode.HALF_UP);
    }
    return value;
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
