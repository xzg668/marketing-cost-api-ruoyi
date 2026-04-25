package com.sanhua.marketingcost.formula.registry;

import java.util.List;

/**
 * 公式变量循环依赖异常 —— FormulaRefResolver 在 DFS 中检测到环时抛出。
 *
 * <p>持有完整环路径（从循环起点到再次访问该点），便于在 controller 层
 * 把环用 "→" 拼成一条人类可读的提示。
 */
public class CircularFormulaException extends RuntimeException {

  private final List<String> cyclePath;

  public CircularFormulaException(List<String> cyclePath) {
    super("公式变量存在循环引用: " + String.join(" → ", cyclePath));
    this.cyclePath = List.copyOf(cyclePath);
  }

  public List<String> getCyclePath() {
    return cyclePath;
  }
}
