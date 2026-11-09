package com.sanhua.marketingcost.security;

import java.util.function.LongPredicate;

/** HTTP请求内的报价范围；后台任务不继承浏览器范围，公共价格来源不受此处过滤。 */
public final class QuoteVisibilityContext {
  public enum Scope { ALL, COMMERCIAL, PLATE, NONE }
  private record State(Scope scope, LongPredicate formAccess) {}
  private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
  private QuoteVisibilityContext() {}
  public static Scope scope() { return CURRENT.get() == null ? Scope.ALL : CURRENT.get().scope(); }
  public static boolean restricted() { return scope() != Scope.ALL; }
  public static boolean canAccessForm(Long id) {
    return !restricted() || id != null && CURRENT.get().formAccess().test(id);
  }
  static void bind(Scope scope, LongPredicate access) { CURRENT.set(new State(scope, access)); }
  static void clear() { CURRENT.remove(); }
  public static String formCondition(String alias) {
    if (!alias.matches("[a-zA-Z_][a-zA-Z_0-9]*")) throw new IllegalArgumentException("Invalid form alias");
    return switch (scope()) {
      case ALL -> "1=1";
      case NONE -> "1=0";
      case PLATE -> QuoteProductVisibilitySql.plateForm(alias);
      case COMMERCIAL -> QuoteProductVisibilitySql.commercialForm(alias);
    };
  }
  /** 有正式单据时以单据为准；尚未生成单据的失败接入按已识别的流程范围显示。 */
  public static String ingestLogCondition(String alias) {
    formCondition(alias); // 校验内部SQL别名
    String logScope = switch (scope()) {
      case ALL -> "1=1";
      case NONE -> "1=0";
      case PLATE -> QuoteProductVisibilitySql.plateProcess(alias);
      case COMMERCIAL -> "NOT " + QuoteProductVisibilitySql.plateProcess(alias);
    };
    String related = "SELECT 1 FROM oa_form qvlog WHERE qvlog.deleted=0 AND (CAST(qvlog.oa_no AS BINARY)=CAST("
        + alias + ".oa_no AS BINARY) OR qvlog.ingest_log_id=" + alias + ".id)";
    return "(EXISTS (" + related + " AND " + formCondition("qvlog") + ") OR (NOT EXISTS ("
        + related + ") AND " + logScope + "))";
  }

}
