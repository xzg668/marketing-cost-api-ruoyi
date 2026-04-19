package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class CostRunProgressStore {
  private final Map<String, ProgressState> progressByOaNo = new ConcurrentHashMap<>();

  /**
   * 尝试启动试算，如果该 OA 单号已在运行中则返回 false（防重）。
   * 使用 compute() 保证原子性，消除 TOCTOU 竞态。
   */
  public boolean start(String oaNo) {
    if (oaNo == null) {
      return false;
    }
    AtomicBoolean started = new AtomicBoolean(false);
    progressByOaNo.compute(oaNo, (key, prev) -> {
      if (prev != null && "RUNNING".equals(prev.status)) {
        return prev;
      }
      started.set(true);
      return new ProgressState(0, "RUNNING", null);
    });
    return started.get();
  }

  public void update(String oaNo, int percent) {
    if (oaNo == null) {
      return;
    }
    progressByOaNo.put(oaNo, new ProgressState(clamp(percent), "RUNNING", null));
  }

  public void complete(String oaNo) {
    if (oaNo == null) {
      return;
    }
    progressByOaNo.put(oaNo, new ProgressState(100, "DONE", null));
  }

  public void fail(String oaNo, String message) {
    if (oaNo == null) {
      return;
    }
    progressByOaNo.put(oaNo, new ProgressState(0, "ERROR", message));
  }

  /**
   * 清除已完成/失败的进度记录，释放内存。
   */
  public void remove(String oaNo) {
    if (oaNo != null) {
      progressByOaNo.remove(oaNo);
    }
  }

  public CostRunProgressResponse get(String oaNo) {
    CostRunProgressResponse response = new CostRunProgressResponse();
    if (oaNo == null) {
      response.setPercent(0);
      response.setStatus("IDLE");
      return response;
    }
    ProgressState state = progressByOaNo.get(oaNo);
    if (state == null) {
      response.setPercent(0);
      response.setStatus("IDLE");
      return response;
    }
    response.setPercent(state.percent);
    response.setStatus(state.status);
    response.setMessage(state.message);
    return response;
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
