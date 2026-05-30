package com.sanhua.marketingcost.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.CostRunExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class CostRunExecutionPropertiesTest {

  @Test
  void defaultsToTaskWorker() {
    CostRunExecutionProperties properties = new CostRunExecutionProperties();

    assertThat(properties.resolveMode("alice", "COMMERCIAL"))
        .isEqualTo(CostRunExecutionMode.TASK_WORKER);
    assertThat(properties.getGrayUsers()).isEmpty();
    assertThat(properties.getGrayBusinessUnits()).isEmpty();
  }

  @Test
  void taskWorkerWithoutGrayFiltersAppliesToAllRequests() {
    CostRunExecutionProperties properties = new CostRunExecutionProperties();
    properties.setMode("TASK_WORKER");

    assertThat(properties.resolveMode("alice", "HOUSEHOLD"))
        .isEqualTo(CostRunExecutionMode.TASK_WORKER);
  }

  @Test
  void grayFiltersLimitTaskWorkerToMatchingUserOrBusinessUnit() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("cost.run.execution.mode", "TASK_WORKER")
        .withProperty("cost.run.execution.gray-users", " alice, BOB ")
        .withProperty("cost.run.execution.gray-business-units", " commercial ");
    CostRunExecutionProperties properties = new CostRunExecutionProperties();

    Binder.get(environment)
        .bind("cost.run.execution", Bindable.ofInstance(properties));

    assertThat(properties.resolveMode("ALICE", "HOUSEHOLD"))
        .isEqualTo(CostRunExecutionMode.TASK_WORKER);
    assertThat(properties.resolveMode("charlie", "COMMERCIAL"))
        .isEqualTo(CostRunExecutionMode.TASK_WORKER);
    assertThat(properties.resolveMode("charlie", "HOUSEHOLD"))
        .isEqualTo(CostRunExecutionMode.API_SYNC);
  }

  @Test
  void apiSyncIsAlwaysRollbackEvenWhenGrayFiltersRemainConfigured() {
    CostRunExecutionProperties properties = new CostRunExecutionProperties();
    properties.setMode("API_SYNC");
    properties.setGrayUsers(java.util.Set.of("alice"));
    properties.setGrayBusinessUnits(java.util.Set.of("COMMERCIAL"));

    assertThat(properties.resolveMode("alice", "COMMERCIAL"))
        .isEqualTo(CostRunExecutionMode.API_SYNC);
  }
}
