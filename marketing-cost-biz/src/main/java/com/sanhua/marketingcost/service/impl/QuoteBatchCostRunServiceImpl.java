package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteProductCostRunTaskResponse;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import com.sanhua.marketingcost.service.QuoteBatchCostRunService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteBatchCostRunServiceImpl implements QuoteBatchCostRunService {

  private final OaFormMapper oaFormMapper;
  private final CostRunBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final CostRunTaskSubmissionService submissionService;
  private final CostRunTaskProgressService progressService;
  private final BusinessUnitRepriceLockGuard repriceLockGuard;

  public QuoteBatchCostRunServiceImpl(
      OaFormMapper oaFormMapper,
      CostRunBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      CostRunTaskSubmissionService submissionService,
      CostRunTaskProgressService progressService,
      BusinessUnitRepriceLockGuard repriceLockGuard) {
    this.oaFormMapper = oaFormMapper;
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.submissionService = submissionService;
    this.progressService = progressService;
    this.repriceLockGuard = repriceLockGuard;
  }

  @Override
  public QuoteBatchCostRunResponse submit(
      String oaNo, QuoteBatchCostRunRequest request, String submittedBy) {
    String normalizedOaNo = required(oaNo, "OA单号");
    if (request != null
        && StringUtils.hasText(request.getMode())
        && !"ALL".equalsIgnoreCase(request.getMode().trim())) {
      throw new IllegalArgumentException("整单核算仅支持 ALL 模式");
    }
    String periodMonth = currentMonth(request == null ? null : request.getPeriodMonth());
    repriceLockGuard.assertCostRunAllowed(normalizedOaNo);
    CostRunTaskSubmissionResult submitted =
        submissionService.submitQuote(normalizedOaNo, null, periodMonth, submittedBy);
    CostRunBatchProgressSnapshot progress =
        progressService.refreshBatchProgress(submitted.getBatchNo());
    CostRunBatch batch = loadBatch(submitted.getBatchNo());
    return response(
        normalizedOaNo, periodMonth, batch, progress, submitted.isExistingBatch());
  }

  @Override
  public QuoteBatchCostRunResponse getCurrent(String oaNo, String periodMonth) {
    String normalizedOaNo = required(oaNo, "OA单号");
    String normalizedMonth = currentMonth(periodMonth);
    OaForm form = loadForm(normalizedOaNo);
    CostRunBatch batch =
        batchMapper.selectCurrentQuoteBatch(
            normalizedOaNo,
            normalizedMonth,
            required(form.getBusinessUnitType(), "业务单元"));
    if (batch == null) {
      QuoteBatchCostRunResponse response = new QuoteBatchCostRunResponse();
      response.setOaNo(normalizedOaNo);
      response.setPeriodMonth(normalizedMonth);
      response.setStatus("NOT_STARTED");
      return response;
    }
    return response(
        normalizedOaNo,
        normalizedMonth,
        batch,
        progressService.getBatchProgress(batch.getBatchNo()),
        true);
  }

  @Override
  public QuoteProductCostRunTaskResponse getCurrentItem(
      String oaNo, Long oaFormItemId, String periodMonth) {
    String normalizedOaNo = required(oaNo, "OA单号");
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("报价产品行 ID 必须大于0");
    }
    String normalizedMonth = currentMonth(periodMonth);
    CostRunTask task =
        taskMapper.selectCurrentQuoteTask(normalizedOaNo, oaFormItemId, normalizedMonth);
    QuoteProductCostRunTaskResponse response = new QuoteProductCostRunTaskResponse();
    response.setOaNo(normalizedOaNo);
    response.setOaFormItemId(oaFormItemId);
    response.setPeriodMonth(normalizedMonth);
    if (task == null) {
      response.setStatus("NOT_STARTED");
      response.setProgress(0);
      return response;
    }
    response.setBatchNo(task.getBatchNo());
    response.setStatus(task.getStatus());
    response.setProgress(task.getProgress());
    response.setMessage(task.getErrorMessage());
    return response;
  }

  private QuoteBatchCostRunResponse response(
      String oaNo,
      String periodMonth,
      CostRunBatch batch,
      CostRunBatchProgressSnapshot progress,
      boolean existingBatch) {
    boolean prerequisiteFailed =
        batch != null && "FAILED".equals(batch.getPrerequisiteStatus());
    String status = prerequisiteFailed ? "FAILED" : progress.getStatus();
    int failedCount =
        prerequisiteFailed
            ? Math.max(
                Math.max(progress.getFailedCount(), value(batch.getFailedCount())),
                Math.max(
                    progress.getTotalCount()
                        - progress.getSuccessCount()
                        - progress.getCollaborationCount()
                        - progress.getSkippedCurrentCount(),
                    0))
            : progress.getFailedCount();
    QuoteBatchCostRunResponse response = new QuoteBatchCostRunResponse();
    response.setOaNo(oaNo);
    response.setPeriodMonth(periodMonth);
    response.setBatchNo(progress.getBatchNo());
    response.setStatus(status);
    response.setPrerequisiteStatus(batch == null ? null : batch.getPrerequisiteStatus());
    response.setMessage(batch == null ? null : batch.getErrorMessage());
    response.setTotalCount(progress.getTotalCount());
    response.setQueuedCount(
        prerequisiteFailed ? 0 : progress.getPendingCount() + progress.getRetryableCount());
    response.setRunningCount(prerequisiteFailed ? 0 : progress.getRunningCount());
    response.setSuccessCount(progress.getSuccessCount());
    response.setCollaborationCount(progress.getCollaborationCount());
    response.setFailedCount(failedCount);
    response.setSkippedCurrentCount(progress.getSkippedCurrentCount());
    response.setProgress(prerequisiteFailed ? 100 : progress.getProgress());
    response.setActive(
        "PENDING".equals(status) || "RUNNING".equals(status));
    response.setExistingBatch(existingBatch);
    return response;
  }

  private CostRunBatch loadBatch(String batchNo) {
    return batchMapper.selectOne(
        Wrappers.lambdaQuery(CostRunBatch.class)
            .eq(CostRunBatch::getBatchNo, batchNo)
            .last("LIMIT 1"));
  }

  private int value(Integer number) {
    return number == null ? 0 : number;
  }

  private OaForm loadForm(String oaNo) {
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo).last("LIMIT 1"));
    if (form == null) {
      throw new IllegalArgumentException("OA 单不存在：" + oaNo);
    }
    return form;
  }

  private String currentMonth(String value) {
    return CostPricingPeriodUtils.requireCurrentPricingMonth(value);
  }

  private String required(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return value.trim();
  }
}
