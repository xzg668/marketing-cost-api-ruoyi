package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class CostRunProgressStore {

  /** T17：状态机 QUEUED → RUNNING → DONE/ERROR */
  private static final String QUEUED = "QUEUED";
  private static final String RUNNING = "RUNNING";
  private static final String DONE = "DONE";
  private static final String ERROR = "ERROR";
  private static final String IDLE = "IDLE";

  private final Map<String, ProgressState> progressByOaNo = new ConcurrentHashMap<>();
  /**
   * T17：FIFO 入队顺序记录。所有 enqueue 进的 oaNo 都按时序在这里。
   * 每次 markRunning / complete / fail / remove 时不删除（保留顺序），
   * 计算 queuePos 时按 status==QUEUED 过滤。完成后 cleanup 时一并 remove。
   */
  private final ConcurrentLinkedQueue<String> queueOrder = new ConcurrentLinkedQueue<>();

  /**
   * T17：把 OA 加入排队，立即标 QUEUED。controller 在 @Async submit 前调一次。
   *
   * <p>同一 OA 已在 QUEUED/RUNNING 时返回 false（防重，等价旧 {@link #start} 的防重职责）。
   */
  public boolean enqueue(String oaNo) {
    if (oaNo == null) {
      return false;
    }
    AtomicBoolean inserted = new AtomicBoolean(false);
    progressByOaNo.compute(oaNo, (key, prev) -> {
      if (prev != null && (QUEUED.equals(prev.status) || RUNNING.equals(prev.status))) {
        return prev;
      }
      inserted.set(true);
      return new ProgressState(0, QUEUED, null);
    });
    if (inserted.get()) {
      queueOrder.offer(oaNo);
    }
    return inserted.get();
  }

  /**
   * T17：worker 接到 task 后第一行调用，QUEUED → RUNNING。
   * 即使之前不在 QUEUED 状态（罕见 race）也强行写 RUNNING，保证 worker 真正开跑时状态一致。
   */
  public void markRunning(String oaNo) {
    if (oaNo == null) {
      return;
    }
    progressByOaNo.put(oaNo, new ProgressState(0, RUNNING, null));
  }

  /**
   * 兼容 API：等价 {@link #enqueue} + {@link #markRunning}。
   *
   * <p>单元测试用（backward-compat），生产路径走 enqueue / markRunning 分两步以
   * 显式区分排队 / 执行阶段。
   *
   * @deprecated 新代码用 {@link #enqueue} + {@link #markRunning} 分阶段
   */
  @Deprecated
  public boolean start(String oaNo) {
    boolean inserted = enqueue(oaNo);
    if (inserted) {
      markRunning(oaNo);
    }
    return inserted;
  }

  /**
   * T18(g)：进度只推不退。新值 < 当前值（且当前 RUNNING）→ 忽略，避免业务逻辑或并发
   * 写入造成进度条回退抖动。状态切换（markRunning/complete/fail）走专用方法不受此约束。
   */
  public void update(String oaNo, int percent) {
    if (oaNo == null) {
      return;
    }
    int target = clamp(percent);
    progressByOaNo.merge(
        oaNo,
        new ProgressState(target, RUNNING, null),
        (prev, neu) -> {
          if (RUNNING.equals(prev.status) && neu.percent < prev.percent) {
            return prev; // 防退
          }
          return neu;
        });
  }

  public void complete(String oaNo) {
    if (oaNo == null) {
      return;
    }
    progressByOaNo.put(oaNo, new ProgressState(100, DONE, null));
  }

  /**
   * T18(c)：fail 保留当前 percent。比如主档同步 5% 时失败 → ERROR + 5%（前端进度条卡 5% 红字），
   * 而不是回退到 0%（用户分不清"失败"还是"还没开始"）。
   */
  public void fail(String oaNo, String message) {
    if (oaNo == null) {
      return;
    }
    progressByOaNo.compute(oaNo, (key, prev) -> {
      int keepPercent = prev == null ? 0 : prev.percent;
      return new ProgressState(keepPercent, ERROR, message);
    });
  }

  /** 清除已完成/失败的进度记录，释放内存。 */
  public void remove(String oaNo) {
    if (oaNo != null) {
      progressByOaNo.remove(oaNo);
      queueOrder.remove(oaNo);
    }
  }

  public CostRunProgressResponse get(String oaNo) {
    CostRunProgressResponse response = new CostRunProgressResponse();
    if (oaNo == null) {
      response.setPercent(0);
      response.setStatus(IDLE);
      response.setQueuePos(0);
      return response;
    }
    ProgressState state = progressByOaNo.get(oaNo);
    if (state == null) {
      response.setPercent(0);
      response.setStatus(IDLE);
      response.setQueuePos(0);
      return response;
    }
    response.setPercent(state.percent);
    response.setStatus(state.status);
    response.setMessage(state.message);
    response.setQueuePos(getQueuePos(oaNo));
    return response;
  }

  /**
   * T17：返排队位置。
   * <ul>
   *   <li>RUNNING / DONE / ERROR → 0（前面没有等待）</li>
   *   <li>QUEUED → N（前面还有 N-1 个 QUEUED，本身是第 N 个）</li>
   *   <li>不存在 → 0</li>
   * </ul>
   */
  public int getQueuePos(String oaNo) {
    if (oaNo == null) {
      return 0;
    }
    ProgressState s = progressByOaNo.get(oaNo);
    if (s == null || !QUEUED.equals(s.status)) {
      return 0;
    }
    int pos = 0;
    for (String name : queueOrder) {
      ProgressState ps = progressByOaNo.get(name);
      if (ps == null || !QUEUED.equals(ps.status)) {
        continue;
      }
      pos++;
      if (oaNo.equals(name)) {
        return pos;
      }
    }
    return pos;
  }

  /** T17：当前 QUEUED 总数，前端 dashboard 可显示"系统中 N 个试算等待"。 */
  public int getQueueDepth() {
    int depth = 0;
    for (String name : queueOrder) {
      ProgressState ps = progressByOaNo.get(name);
      if (ps != null && QUEUED.equals(ps.status)) {
        depth++;
      }
    }
    return depth;
  }

  private int clamp(int percent) {
    if (percent < 0) {
      return 0;
    }
    if (percent > 100) {
      return 100;
    }
    return percent;
  }

  /** 不可变状态对象，避免 torn reads。 */
  private static final class ProgressState {
    final int percent;
    final String status;
    final String message;

    ProgressState(int percent, String status, String message) {
      this.percent = percent;
      this.status = status;
      this.message = message;
    }
  }
}
