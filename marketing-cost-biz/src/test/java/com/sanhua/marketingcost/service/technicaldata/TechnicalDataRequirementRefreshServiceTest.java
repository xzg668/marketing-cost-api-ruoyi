package com.sanhua.marketingcost.service.technicaldata;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TechnicalDataRequirementRefreshServiceTest {
  @Test void registeredPostBomGapDoesNotUnlockOrReplaceSubmittedModules() {
    var mapper = mock(QuoteTechModuleMapper.class);
    var tasks = mock(QuoteTechTaskMapper.class);
    var jdbc = mock(JdbcTemplate.class);
    var task = new QuoteTechTask(); task.setId(6L); task.setTaskStatus("SUBMITTED");
    task.setTaskVersion(3); task.setApplicableOrgCode("210");
    task.setOaAssignmentVersion(1); task.setExternalTaskStatus("PUBLISHED");
    var submitted = new QuoteTechModule(); submitted.setId(7L); submitted.setModuleType("SALARY");
    submitted.setRequiredFlag(1); submitted.setAssigneeUserId(101L); submitted.setCurrentVersionId(9L);
    submitted.setModuleStatus("SUBMITTED"); submitted.setSourceAvailability("MISSING"); submitted.setSourceReference("same");
    var price = new QuoteTechModule(); price.setId(8L); price.setModuleType("PRICE");
    price.setRequiredFlag(0); price.setModuleStatus("PENDING"); price.setRowVersion(0);
    price.setSourceAvailability("UNCONFIRMED");
    var gap = new TechnicalDataModuleRequirement("PRICE", true, "PRICE_SOURCE_MISSING", "缺固定价",
        TechnicalDataAvailability.MISSING, "known-bom", LocalDateTime.now());
    when(tasks.selectActiveForUpdate(168L,"2026-10")).thenReturn(task);
    when(mapper.selectByTaskId(6L)).thenReturn(List.of(submitted,price));
    when(mapper.refreshRequirement(8L,0,gap,"PENDING")).thenReturn(1);
    var service=new TechnicalDataRequirementRefreshService(mapper,jdbc,tasks,new OaMessageCodec(new ObjectMapper()));
    assertThat(service.reconcile(168L,"2026-10","210",List.of(gap))).contains("新增", "原已提交资料保留");
    verify(mapper).refreshRequirement(8L,0,gap,"PENDING");
    verify(mapper,never()).refreshRequirement(eq(7L),anyInt(),any(),anyString());
    verify(jdbc).update(contains("oa_edit_allowed=0"),eq(8L));
    var order = inOrder(jdbc, mapper);
    order.verify(jdbc).update(contains("task_status='PENDING'"), eq(6L));
    order.verify(mapper).refreshRequirement(8L,0,gap,"PENDING");
    assertThat(task.getTaskVersion()).isEqualTo(4);
    assertThat(submitted.getCurrentVersionId()).isEqualTo(9);
    assertThat(submitted.getModuleStatus()).isEqualTo("SUBMITTED");
  }

  @Test void unknownSourceOrAlreadyOwnedModuleDoesNotBecomeAnAdditionalDispatch() {
    var mapper=mock(QuoteTechModuleMapper.class); var tasks=mock(QuoteTechTaskMapper.class);
    var jdbc=mock(JdbcTemplate.class);
    var task=new QuoteTechTask(); task.setId(6L); task.setTaskStatus("SUBMITTED"); task.setApplicableOrgCode("210");
    task.setTaskVersion(3); task.setOaAssignmentVersion(1); task.setExternalTaskStatus("PUBLISHED");
    var owned=new QuoteTechModule(); owned.setId(7L); owned.setModuleType("MANUFACTURING");
    owned.setAssigneeUserId(101L); owned.setCurrentVersionId(9L); owned.setModuleStatus("SUBMITTED");
    var unknown=new QuoteTechModule(); unknown.setId(8L); unknown.setModuleType("PRICE"); unknown.setModuleStatus("PENDING");
    when(tasks.selectActiveForUpdate(168L,"2026-10")).thenReturn(task);
    when(mapper.selectByTaskId(6L)).thenReturn(List.of(owned,unknown));
    var service=new TechnicalDataRequirementRefreshService(mapper,jdbc,tasks,new OaMessageCodec(new ObjectMapper()));
    var facts=List.of(new TechnicalDataModuleRequirement("MANUFACTURING",true,"MISSING","原模块",
        TechnicalDataAvailability.MISSING,"changed",LocalDateTime.now()),new TechnicalDataModuleRequirement("PRICE",false,
        "WAIT","待检查",TechnicalDataAvailability.UNCONFIRMED,null,LocalDateTime.now()));
    service.reconcile(168L,"2026-10","210",facts);
    verify(mapper,never()).refreshRequirement(anyLong(),anyInt(),any(),anyString());
    verify(jdbc,never()).update(anyString(),anyLong());
  }

  @Test void correctedOrganizationRetiresOnlyAnEmptyUnassignedTask() {
    var modules = mock(QuoteTechModuleMapper.class);
    var tasks = mock(QuoteTechTaskMapper.class);
    var jdbc = mock(JdbcTemplate.class);
    var task = new QuoteTechTask(); task.setId(6L); task.setTaskStatus("UNASSIGNED");
    task.setApplicableOrgCode("210"); task.setOaAssignmentVersion(0);
    when(tasks.selectActiveForUpdate(168L, "2026-09")).thenReturn(task);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(6L), eq(6L), eq(6L))).thenReturn(false);
    var service = new TechnicalDataRequirementRefreshService(modules, jdbc, tasks, new OaMessageCodec(new ObjectMapper()));

    assertThat(service.reconcile(168L, "2026-09", "220", List.of())).contains("210", "220", "旧空任务已作废");
    verify(jdbc).update(contains("UPDATE lp_quote_tech_product SET active_flag=0"), eq(6L));
    verify(jdbc).update(contains("task_status='CANCELLED'"), eq(6L));
    verifyNoInteractions(modules);
  }

  @Test void correctedOrganizationPreservesSavedDataAndAssignments() {
    var modules = mock(QuoteTechModuleMapper.class);
    var tasks = mock(QuoteTechTaskMapper.class);
    var jdbc = mock(JdbcTemplate.class);
    var task = new QuoteTechTask(); task.setId(6L); task.setTaskStatus("UNASSIGNED");
    task.setApplicableOrgCode("210");
    when(tasks.selectActiveForUpdate(168L, "2026-09")).thenReturn(task);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(6L), eq(6L), eq(6L))).thenReturn(true);
    var service = new TechnicalDataRequirementRefreshService(modules, jdbc, tasks, new OaMessageCodec(new ObjectMapper()));
    assertThat(service.reconcile(168L, "2026-09", "220", List.of())).contains("已保留");
    task.setTaskStatus("APPROVED");
    assertThat(service.reconcile(168L, "2026-09", "220", List.of())).contains("已保留");
    task.setTaskStatus("UNASSIGNED"); task.setOaAssignmentVersion(1);
    assertThat(service.reconcile(168L, "2026-09", "220", List.of())).contains("已保留");
    verify(jdbc, never()).update(anyString(), anyLong());
    verifyNoInteractions(modules);
  }

  @Test void reorderingJsonSourceFieldsDoesNotResetCompletedDraft() {
    var modules = mock(QuoteTechModuleMapper.class);
    var task = new QuoteTechTask(); task.setId(1L); task.setTaskStatus("IN_PROGRESS");
    var module = new QuoteTechModule(); module.setId(2L); module.setRowVersion(3);
    module.setModuleType("PROFILE"); module.setCurrentVersionId(4L); module.setModuleStatus("READY");
    module.setSourceAvailability("MISSING"); module.setSourceReference("{\"source\":\"U9\",\"snapshotId\":1}");
    when(modules.selectByTaskId(1L)).thenReturn(List.of(module));
    var service = new TechnicalDataRequirementRefreshService(modules, mock(JdbcTemplate.class),
        mock(QuoteTechTaskMapper.class), new OaMessageCodec(new ObjectMapper()));
    service.refresh(task, List.of(new TechnicalDataModuleRequirement("PROFILE", true, "MISSING", "缺少",
        TechnicalDataAvailability.MISSING, "{\"snapshotId\":1,\"source\":\"U9\"}", LocalDateTime.now())));
    verify(modules, never()).refreshRequirement(anyLong(), anyInt(), any(), anyString());

    var changed = new TechnicalDataModuleRequirement("PROFILE", true, "MISSING", "缺少",
        TechnicalDataAvailability.MISSING, "{\"source\":\"U9\",\"snapshotId\":2}", LocalDateTime.now());
    when(modules.refreshRequirement(2L, 3, changed, "EDITING")).thenReturn(1);
    service.refresh(task, List.of(changed));
    verify(modules).refreshRequirement(2L, 3, changed, "EDITING");
  }
}
