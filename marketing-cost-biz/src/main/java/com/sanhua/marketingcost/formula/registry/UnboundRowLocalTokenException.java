package com.sanhua.marketingcost.formula.registry;

/**
 * 行局部 token（如 {@code [__material]} / {@code [__scrap]}）在
 * {@code lp_price_variable_binding} 表里查不到有效绑定时抛出。
 *
 * <p>与"变量不存在"（lookup 返回 null，记 WARN + Optional.empty）的区别：
 * 行局部 token 只有在公式里显式使用后才会触发查询，公式里既然写了就说明使用者
 * 意图是按行绑定。此时查不到是"供管部映射没给全"或"数据导入缺失"，
 * 属于 fail-fast 场景，不静默吃掉。
 *
 * <p>UI 侧（T6）捕获此异常后在待绑定徽章 + 行详情里显示，不会把整张表算崩。
 */
public class UnboundRowLocalTokenException extends RuntimeException {

  private final Long linkedItemId;
  private final String placeholder;

  public UnboundRowLocalTokenException(Long linkedItemId, String placeholder, String message) {
    super(message);
    this.linkedItemId = linkedItemId;
    this.placeholder = placeholder;
  }

  public Long getLinkedItemId() {
    return linkedItemId;
  }

  public String getPlaceholder() {
    return placeholder;
  }
}
