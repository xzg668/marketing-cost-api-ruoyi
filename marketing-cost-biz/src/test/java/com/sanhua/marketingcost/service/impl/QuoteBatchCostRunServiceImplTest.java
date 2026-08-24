package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBatchCostRunServiceImplTest {

  private OaFormMapper formMapper;
  private CostRunBatchMapper batchMapper;
  private CostRunTaskMapper taskMapper;
  private CostRunTaskSubmissionService submissionService;
  private CostRunTaskProgressService progressService;
  private BusinessUnitRepriceLockGuard lockGuard;
  private QuoteBatchCostRunServiceImpl service;

  @BeforeEach
  void setUp() {
    formMapper = mock(OaFormMapper.class);
    batchMapper = mock(CostRunBatchMapper.class);
    taskMapper = mock(CostRunTaskMapper.class);
    submissionService = mock(CostRunTaskSubmissionService.class);
    progressService = mock(CostRunTaskProgressService.class);
    lockGuard = mock(BusinessUnitRepriceLockGuard.class);
    service =
        new QuoteBatchCostRunServiceImpl(
            formMapper, batchMapper, taskMapper, submissionService, progressService, lockGuard);
  }

  @Test
  void submitReturnsPersistedBatchProgress() {
    String month = CostPricingPeriodUtils.currentPricingMonth();
    CostRunTaskSubmissionResult submitted =
        CostRunTaskSubmissionResult.of(
            "CRQ-1", "QUOTE", "OA-1", "PENDING", 47, 30, 17, false);
    when(submissionService.submitQuote("OA-1", null, month, "tester"))
        .thenReturn(submitted);
    when(progressService.refreshBatchProgress("CRQ-1"))
        .thenReturn(progress("CRQ-1", "RUNNING", 47, 4, 20, 10, 3, 10, 42));
    QuoteBatchCostRunRequest request = new QuoteBatchCostRunRequest();
    request.setPeriodMonth(month);

    var response = service.submit(" OA-1 ", request, "tester");

    assertThat(response.getBatchNo()).isEqualTo("CRQ-1");
    assertThat(response.getTotalCount()).isEqualTo(47);
    assertThat(response.getQueuedCount()).isEqualTo(20);
    assertThat(response.getRunningCount()).isEqualTo(4);
    assertThat(response.getSuccessCount()).isEqualTo(10);
    assertThat(response.getCollaborationCount()).isEqualTo(3);
    assertThat(response.getSkippedCurrentCount()).isEqualTo(10);
    assertThat(response.isActive()).isTrue();
    verify(lockGuard).assertCostRunAllowed("OA-1");
  }

  @Test
  void currentProgressIsReadOnly() {
    String month = CostPricingPeriodUtils.currentPricingMonth();
    OaForm form = new OaForm();
    form.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    CostRunBatch batch = new CostRunBatch();
    batch.setBatchNo("CRQ-2");
    when(batchMapper.selectCurrentQuoteBatch("OA-1", month, "COMMERCIAL"))
        .thenReturn(batch);
    when(progressService.getBatchProgress("CRQ-2"))
        .thenReturn(progress("CRQ-2", "SUCCESS", 3, 0, 0, 2, 1, 0, 100));

    var response = service.getCurrent("OA-1", null);

    assertThat(response.getStatus()).isEqualTo("SUCCESS");
    assertThat(response.getCollaborationCount()).isEqualTo(1);
    assertThat(response.isActive()).isFalse();
    verify(progressService).getBatchProgress("CRQ-2");
  }

  @Test
  void prerequisiteFailureIsVisibleAndRetryableInsteadOfPendingForever() {
    String month = CostPricingPeriodUtils.currentPricingMonth();
    OaForm form = new OaForm();
    form.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    CostRunBatch batch = new CostRunBatch();
    batch.setBatchNo("CRQ-FAILED");
    batch.setPrerequisiteStatus("FAILED");
    batch.setStatus("FAILED");
    batch.setFailedCount(20);
    batch.setErrorMessage("主档同步失败: 数据库连接失败");
    when(batchMapper.selectCurrentQuoteBatch("OA-FAIL", month, "COMMERCIAL"))
        .thenReturn(batch);
    when(progressService.getBatchProgress("CRQ-FAILED"))
        .thenReturn(progress("CRQ-FAILED", "PENDING", 20, 0, 20, 0, 0, 0, 0));

    var response = service.getCurrent("OA-FAIL", month);

    assertThat(response.getStatus()).isEqualTo("FAILED");
    assertThat(response.getPrerequisiteStatus()).isEqualTo("FAILED");
    assertThat(response.getMessage()).contains("数据库连接失败");
    assertThat(response.getQueuedCount()).isZero();
    assertThat(response.getFailedCount()).isEqualTo(20);
    assertThat(response.getProgress()).isEqualTo(100);
    assertThat(response.isActive()).isFalse();
  }

  @Test
  void currentItemReturnsTaskMessage() {
    String month = CostPricingPeriodUtils.currentPricingMonth();
    CostRunTask task = new CostRunTask();
    task.setBatchNo("CRQ-3");
    task.setStatus("COLLABORATION");
    task.setProgress(100);
    task.setErrorMessage("缺少 BOM");
    when(taskMapper.selectCurrentQuoteTask("OA-1", 11L, month)).thenReturn(task);

    var response = service.getCurrentItem("OA-1", 11L, null);

    assertThat(response.getStatus()).isEqualTo("COLLABORATION");
    assertThat(response.getMessage()).isEqualTo("缺少 BOM");
  }

  private CostRunBatchProgressSnapshot progress(
      String batchNo,
      String status,
      int total,
      int running,
      int pending,
      int success,
      int collaboration,
      int skippedCurrent,
      int percentage) {
    CostRunBatchProgressSnapshot result = new CostRunBatchProgressSnapshot();
    result.setBatchNo(batchNo);
    result.setStatus(status);
    result.setTotalCount(total);
    result.setRunningCount(running);
    result.setPendingCount(pending);
    result.setSuccessCount(success);
    result.setCollaborationCount(collaboration);
    result.setSkippedCurrentCount(skippedCurrent);
    result.setProgress(percentage);
    return result;
  }
}
