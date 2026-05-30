package com.sanhua.marketingcost.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MonthlyRepriceExecutionBackendTest {

  @Test
  void reservesEasyDataButOnlyLocalWorkerIsSupportedInCurrentPhase() {
    assertThat(MonthlyRepriceExecutionBackend.LOCAL_WORKER.isSupportedInCurrentPhase()).isTrue();
    assertThat(MonthlyRepriceExecutionBackend.EASYDATA.isSupportedInCurrentPhase()).isFalse();
  }

  @Test
  void parsesConfiguredBackendByCode() {
    assertThat(MonthlyRepriceExecutionBackend.fromCode(" easydata "))
        .isEqualTo(MonthlyRepriceExecutionBackend.EASYDATA);
  }

  @Test
  void rejectsUnknownBackend() {
    assertThatThrownBy(() -> MonthlyRepriceExecutionBackend.fromCode("REMOTE_PLATFORM"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的月度调价执行后端");
  }
}
