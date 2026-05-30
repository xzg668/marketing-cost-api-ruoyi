package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceTaskService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceTaskServiceImpl implements MonthlyRepriceTaskService {

  private final CostRunTaskSubmissionService costRunTaskSubmissionService;

  @Autowired
  public MonthlyRepriceTaskServiceImpl(CostRunTaskSubmissionService costRunTaskSubmissionService) {
    this.costRunTaskSubmissionService = costRunTaskSubmissionService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceObjectExpandResult createTasks(
      MonthlyRepriceBatch batch, List<MonthlyRepriceCalcObject> calcObjects) {
    if (batch == null || !StringUtils.hasText(batch.getRepriceNo())) {
      throw new IllegalArgumentException("月度调价批次不存在");
    }
    List<MonthlyRepriceCalcObject> objects =
        calcObjects == null ? List.of() : calcObjects;
    CostRunTaskSubmissionResult submission = submitCostRunTasks(batch, objects);
    return MonthlyRepriceObjectExpandResult.of(
        batch.getRepriceNo(), objects.size(), submission.getTaskCount(), submission.getSkippedCount());
  }

  private CostRunTaskSubmissionResult submitCostRunTasks(
      MonthlyRepriceBatch batch, List<MonthlyRepriceCalcObject> calcObjects) {
    if (costRunTaskSubmissionService == null) {
      throw new IllegalStateException("通用成本核算任务提交服务未初始化");
    }
    CostRunMonthlyRepriceSubmitRequest request = new CostRunMonthlyRepriceSubmitRequest();
    request.setRepriceNo(batch.getRepriceNo());
    request.setPricingMonth(batch.getPricingMonth());
    request.setPriceAsOfTime(batch.getPriceAsOfTime());
    request.setBusinessUnitType(batch.getBusinessUnitType());
    request.setAdjustBatchId(batch.getAdjustBatchId());
    request.setBomSourcePolicy(batch.getBomSourcePolicy());
    request.setCreatedBy(batch.getCreatedBy());
    request.setCreatedName(batch.getCreatedName());
    request.setCalcObjects(calcObjects);
    return costRunTaskSubmissionService.submitMonthlyReprice(request);
  }

  private String normalizeToNull(String value) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
