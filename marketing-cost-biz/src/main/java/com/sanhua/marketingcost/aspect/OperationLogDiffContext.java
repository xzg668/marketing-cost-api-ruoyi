package com.sanhua.marketingcost.aspect;

/**
 * 给 {@link OperationLogAspect} 传递一次写操作的真实修改前、修改后快照。
 *
 * <p>Controller 在业务成功后登记快照，切面落库后立即清理。没有登记快照的旧接口继续沿用原有
 * request 参数作为 after_data，保证全局兼容。
 */
public final class OperationLogDiffContext {

  private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

  private OperationLogDiffContext() {}

  public static void record(Object before, Object after) {
    HOLDER.set(new Snapshot(before, after));
  }

  public static Snapshot current() {
    return HOLDER.get();
  }

  public static void clear() {
    HOLDER.remove();
  }

  public record Snapshot(Object before, Object after) {}
}
