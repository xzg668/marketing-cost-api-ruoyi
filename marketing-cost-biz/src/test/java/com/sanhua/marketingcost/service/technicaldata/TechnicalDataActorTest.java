package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.List;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import org.junit.jupiter.api.Test;

class TechnicalDataActorTest {
  @Test void newGapIsNotImplicitlyAssignedToPreviousTechnicianBeforeOaAcceptsDispatch() {
    var tech=new TechnicalDataActor(101L,"技术员",Set.of("technical:data:task:edit"));
    var task=new QuoteTechTask(); task.setAssigneeUserId(101L); task.setOaAssignmentVersion(1);
    task.setExternalTaskStatus("PUBLISHED"); task.setActiveFlag(1); task.setTaskStatus("PENDING");
    var salary=new QuoteTechModule(); salary.setModuleType("SALARY"); salary.setRequiredFlag(1);
    salary.setAssigneeUserId(101L); salary.setModuleStatus("SUBMITTED");
    var price=new QuoteTechModule(); price.setModuleType("PRICE"); price.setRequiredFlag(1);
    price.setOaEditAllowed(0); price.setModuleStatus("PENDING");
    assertThat(tech.assignedModules(task,List.of(salary,price))).containsExactly("SALARY");
    assertThat(tech.canEditModule(task,price)).isFalse();
    price.setAssigneeUserId(101L); price.setOaEditAllowed(1);
    assertThat(tech.assignedModules(task,List.of(salary,price))).containsExactly("SALARY","PRICE");
    assertThat(tech.canEditModule(task,price)).isTrue();
    assertThat(tech.canEditModule(task,salary)).isFalse();
  }
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
