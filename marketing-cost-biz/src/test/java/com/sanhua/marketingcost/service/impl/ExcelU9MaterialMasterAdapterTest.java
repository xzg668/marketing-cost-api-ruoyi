package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExcelU9MaterialMasterAdapter")
class ExcelU9MaterialMasterAdapterTest {

  @Test
  @DisplayName("sourceType：Excel adapter 固定写入 EXCEL source_type")
  void sourceTypeIsExcel() {
    assertThat(new ExcelU9MaterialMasterAdapter().sourceType())
        .isEqualTo(U9MaterialMasterSourceType.EXCEL);
  }

  @Test
  @DisplayName("字段契约：Excel adapter 复用 20260519 的 63 列表头映射和旧别名")
  void fieldContractSupportsCurrentTemplateAndAliases() {
    assertThat(U9MaterialMasterFieldContract.fieldMappings()).hasSize(63);
    assertThat(U9MaterialMasterFieldContract.headerToField())
        .containsEntry("默认主供应商", "default_supplier")
        .containsEntry("全局段3(理论净重)", "global_seg_3_theoretical_net_weight");
    assertThat(U9MaterialMasterFieldContract.canonicalHeader("料品采购相关信息.收货原则"))
        .isEqualTo("收货原则");
    assertThat(U9MaterialMasterFieldContract.canonicalHeader("料品MRP相关信息.采购预处理提前期(天)"))
        .isEqualTo("采购预处理提前期(天)");
  }

  @Test
  @DisplayName("API/MQ/SCHEDULE adapter 是明确占位，不会误写 raw 表")
  void futureAdaptersAreExplicitPlaceholders() {
    assertThatThrownBy(() -> new ApiU9MaterialMasterAdapter().ingest(null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("API 接入尚未实现");
    assertThatThrownBy(() -> new MqU9MaterialMasterAdapter().ingest(null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("MQ 接入尚未实现");
    assertThatThrownBy(() -> new ScheduleU9MaterialMasterAdapter().ingest(null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("定时同步尚未实现");
  }
}
