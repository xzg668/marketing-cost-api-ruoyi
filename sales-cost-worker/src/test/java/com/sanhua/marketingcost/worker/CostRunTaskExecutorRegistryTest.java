package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunTaskExecutorRegistryTest {

  @Test
  void resolvesExecutorByScene() {
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    CostRunTaskExecutorRegistry registry = new CostRunTaskExecutorRegistry(List.of(executor));

    assertThat(registry.get(CostRunTaskScene.QUOTE)).isSameAs(executor);
  }

  @Test
  void rejectsDuplicateSceneExecutors() {
    FakeExecutor first = new FakeExecutor(CostRunTaskScene.QUOTE);
    FakeExecutor second = new FakeExecutor(CostRunTaskScene.QUOTE);

    assertThatThrownBy(() -> new CostRunTaskExecutorRegistry(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void rejectsMissingExecutor() {
    CostRunTaskExecutorRegistry registry = new CostRunTaskExecutorRegistry(List.of());

    assertThatThrownBy(() -> registry.get(CostRunTaskScene.QUOTE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未注册");
  }

  private record FakeExecutor(CostRunTaskScene scene) implements CostRunTaskExecutor {

    @Override
    public CostRunTaskExecutionResult execute(CostRunTask task, String workerId) {
      return CostRunTaskExecutionResult.empty();
    }
  }
}
