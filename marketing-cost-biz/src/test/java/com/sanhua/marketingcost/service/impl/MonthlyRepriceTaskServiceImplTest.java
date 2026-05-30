package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("月度调价任务生成服务")
class MonthlyRepriceTaskServiceImplTest {

  @Test
  @DisplayName("月度调价任务只提交通用 CostRunTask")
  void createsMonthlyRepriceTasksThroughGenericCostRunQueue() {
    CostRunTaskSubmissionService submissionService = mock(CostRunTaskSubmissionService.class);
    when(submissionService.submitMonthlyReprice(org.mockito.ArgumentMatchers.any()))
        .thenReturn(CostRunTaskSubmissionResult.of(
            "CRM-001", "MONTHLY_REPRICE", "MRP202605260001", "PENDING", 2, 1, 1, false));
    MonthlyRepriceTaskServiceImpl service = new MonthlyRepriceTaskServiceImpl(submissionService);
    MonthlyRepriceBatch batch = batch();

    var result = service.createTasks(batch, List.of(
        calcObject("OA-700", "P-001", "箱装", "客户A"),
        calcObject("OA-700", " ", "箱装", "客户A")));

    assertThat(result.getRepriceNo()).isEqualTo("MRP202605260001");
    assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getTaskCount()).isEqualTo(1);
    assertThat(result.getSkippedCount()).isEqualTo(1);
    ArgumentCaptor<CostRunMonthlyRepriceSubmitRequest> captor =
        ArgumentCaptor.forClass(CostRunMonthlyRepriceSubmitRequest.class);
    verify(submissionService).submitMonthlyReprice(captor.capture());
    CostRunMonthlyRepriceSubmitRequest request = captor.getValue();
    assertThat(request.getRepriceNo()).isEqualTo("MRP202605260001");
    assertThat(request.getPricingMonth()).isEqualTo("2026-05");
    assertThat(request.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(request.getPriceAsOfTime()).isEqualTo(LocalDateTime.of(2026, 5, 27, 10, 0));
    assertThat(request.getAdjustBatchId()).isEqualTo(100L);
    assertThat(request.getBomSourcePolicy()).isEqualTo("HISTORICAL_OA_BOM");
    assertThat(request.getCreatedBy()).isEqualTo("operator-1");
    assertThat(request.getCalcObjects()).hasSize(2);
  }

  private MonthlyRepriceBatch batch() {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setRepriceNo("MRP202605260001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setPriceAsOfTime(LocalDateTime.of(2026, 5, 27, 10, 0));
    batch.setAdjustBatchId(100L);
    batch.setBomSourcePolicy("HISTORICAL_OA_BOM");
    batch.setCreatedBy("operator-1");
    batch.setCreatedName("张三");
    return batch;
  }

  private MonthlyRepriceCalcObject calcObject(
      String oaNo, String productCode, String packageMethod, String customerName) {
    MonthlyRepriceCalcObject object = new MonthlyRepriceCalcObject();
    object.setOaNo(oaNo);
    object.setOaFormItemId(1L);
    object.setProductCode(productCode);
    object.setPackageMethod(packageMethod);
    object.setCustomerName(customerName);
    object.setSourceOaCalcStatus("已核算");
    return object;
  }
}
