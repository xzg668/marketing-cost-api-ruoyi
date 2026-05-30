package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CostRunTaskExecutorRegistry {

  private final Map<CostRunTaskScene, CostRunTaskExecutor> executors;

  public CostRunTaskExecutorRegistry(List<CostRunTaskExecutor> executors) {
    Map<CostRunTaskScene, CostRunTaskExecutor> mapped = new EnumMap<>(CostRunTaskScene.class);
    for (CostRunTaskExecutor executor : executors) {
      CostRunTaskExecutor previous = mapped.putIfAbsent(executor.scene(), executor);
      if (previous != null) {
        throw new IllegalStateException("重复的成本核算任务执行器：" + executor.scene());
      }
    }
    this.executors = Map.copyOf(mapped);
  }

  public CostRunTaskExecutor get(CostRunTaskScene scene) {
    CostRunTaskExecutor executor = executors.get(scene);
    if (executor == null) {
      throw new IllegalArgumentException("未注册成本核算任务执行器：" + scene);
    }
    return executor;
  }
}
