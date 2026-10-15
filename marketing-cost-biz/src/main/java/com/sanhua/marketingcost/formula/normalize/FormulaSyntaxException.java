package com.sanhua.marketingcost.formula.normalize;

/**
 * 公式语法异常 —— 规范化或求值前的结构错误（括号不平衡、非法字符等）。
 *
 * <p>作为运行期异常交由调用方展示具体公式错误，不回退其他算法；
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
