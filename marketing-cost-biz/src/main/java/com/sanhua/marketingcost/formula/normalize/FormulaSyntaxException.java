package com.sanhua.marketingcost.formula.normalize;

/**
 * 公式语法异常 —— 规范化或求值前的结构错误（括号不平衡、非法字符等）。
 *
 * <p>设计为运行期异常，让上层主动捕获决定是否回落 legacy 路径；
 * 不继承业务异常框架避免和通用错误码耦合。
 */
public class FormulaSyntaxException extends RuntimeException {

  public FormulaSyntaxException(String message) {
    super(message);
  }

  public FormulaSyntaxException(String message, Throwable cause) {
    super(message, cause);
  }
}
