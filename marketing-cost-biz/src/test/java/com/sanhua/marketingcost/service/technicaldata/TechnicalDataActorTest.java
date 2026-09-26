package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TechnicalDataActorTest {
  @Test
  void oaApprovalPermissionDoesNotGrantLocalTaskAccess() {
    TechnicalDataActor technician = actor("technical:data:task:list");
    TechnicalDataActor approver = actor("technical:data:oa:approve");
    TechnicalDataActor publisher = actor("ingest:quote:cost-run:execute");
    TechnicalDataActor admin = actor("technical:data:admin:operate");

    assertThat(technician.canReadTasks()).isTrue();
    assertThat(technician.technician()).isTrue();
    assertThat(approver.canReadTasks()).isFalse();
    assertThat(approver.technician()).isFalse();
    assertThat(approver.has("technical:data:oa:approve")).isTrue();
    assertThat(publisher.canPublish()).isTrue();
    assertThat(admin.admin()).isTrue();
    assertThat(admin.canReadTasks()).isTrue();
    assertThat(admin.hasDirect("technical:data:oa:approve")).isFalse();
  }

  private TechnicalDataActor actor(String authority) {
    return new TechnicalDataActor(1L, "用户", Set.of(authority));
  }
}
