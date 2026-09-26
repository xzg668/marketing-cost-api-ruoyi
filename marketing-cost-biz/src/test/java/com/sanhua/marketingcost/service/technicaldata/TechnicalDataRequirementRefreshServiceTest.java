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
