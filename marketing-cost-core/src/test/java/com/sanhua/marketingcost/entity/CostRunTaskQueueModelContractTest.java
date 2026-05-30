package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.enums.CostRunBatchStatus;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.enums.CostRunTaskStatus;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通用成本核算任务模型契约")
class CostRunTaskQueueModelContractTest {

  @Test
  @DisplayName("entity 绑定通用任务表")
  void entitiesBindTables() {
    assertThat(CostRunBatch.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_cost_run_batch");
    assertThat(CostRunTask.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_cost_run_task");
  }

  @Test
  @DisplayName("mapper 使用 MyBatis Plus 基础 CRUD")
  void mappersExtendBaseMapper() {
    assertThat(BaseMapper.class).isAssignableFrom(CostRunBatchMapper.class);
    assertThat(BaseMapper.class).isAssignableFrom(CostRunTaskMapper.class);
  }

  @Test
  @DisplayName("状态枚举覆盖 T28 设计口径")
  void enumsMatchTaskDesign() {
    assertThat(CostRunTaskScene.values())
        .extracting(Enum::name)
        .containsExactly("QUOTE", "MONTHLY_REPRICE");
    assertThat(CostRunBatchStatus.values())
        .extracting(Enum::name)
        .containsExactly("PENDING", "RUNNING", "SUCCESS", "PARTIAL_FAILED", "FAILED", "CANCELED");
    assertThat(CostRunTaskStatus.values())
        .extracting(Enum::name)
        .containsExactly("PENDING", "RUNNING", "SUCCESS", "FAILED", "RETRYABLE", "CANCELED");
  }

  @Test
  @DisplayName("枚举解析支持大小写并拒绝未知状态")
  void enumParsingIsStrict() {
    assertThat(CostRunTaskScene.fromCode("quote")).isEqualTo(CostRunTaskScene.QUOTE);
    assertThat(CostRunBatchStatus.fromCode("partial_failed"))
        .isEqualTo(CostRunBatchStatus.PARTIAL_FAILED);
    assertThat(CostRunTaskStatus.fromCode("retryable")).isEqualTo(CostRunTaskStatus.RETRYABLE);
    assertThatThrownBy(() -> CostRunTaskStatus.fromCode("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的成本核算任务状态");
  }
}
