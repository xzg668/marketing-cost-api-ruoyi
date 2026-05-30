package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;
import com.sanhua.marketingcost.dto.MonthlyRepriceLinkedPricePrepareResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.service.MonthlyRepriceBatchService;
import com.sanhua.marketingcost.service.MonthlyRepriceLinkedPricePrepareService;
import com.sanhua.marketingcost.service.MonthlyRepriceObjectExpandService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("月度调价发起服务")
class MonthlyRepriceStartServiceImplTest {

  private final MonthlyRepriceBatchService batchService = mock(MonthlyRepriceBatchService.class);
  private final MonthlyRepriceObjectExpandService expandService =
      mock(MonthlyRepriceObjectExpandService.class);
  private final MonthlyRepriceLinkedPricePrepareService linkedPricePrepareService =
      mock(MonthlyRepriceLinkedPricePrepareService.class);
  private final MonthlyRepriceStartServiceImpl service =
      new MonthlyRepriceStartServiceImpl(batchService, expandService, linkedPricePrepareService);

  @Test
  @DisplayName("start：创建批次后立即展开任务，并把任务数量回填到响应")
  void startCreatesBatchAndExpandsTasks() {
    MonthlyRepriceBatchCreateRequest request = new MonthlyRepriceBatchCreateRequest();
    request.setPricingMonth("2026-05");
    MonthlyRepriceBatchCreateResponse response = new MonthlyRepriceBatchCreateResponse();
    response.setRepriceNo("MRP-001");
    when(batchService.createBatch(eq(request), eq("alice"))).thenReturn(response);
    when(expandService.expand("MRP-001", "alice"))
        .thenReturn(MonthlyRepriceObjectExpandResult.of("MRP-001", 3, 2, 1));
    when(linkedPricePrepareService.prepare("MRP-001", "alice"))
        .thenReturn(new MonthlyRepriceLinkedPricePrepareResult());

    MonthlyRepriceBatchCreateResponse result = service.start(request, "alice");

    assertThat(result.getRepriceNo()).isEqualTo("MRP-001");
    assertThat(result.getTotalCount()).isEqualTo(3);
    assertThat(result.getSuccessCount()).isZero();
    assertThat(result.getFailedCount()).isZero();
    assertThat(result.getSkippedCount()).isEqualTo(1);
    verify(batchService).createBatch(request, "alice");
    verify(expandService).expand("MRP-001", "alice");
    verify(linkedPricePrepareService).prepare("MRP-001", "alice");
  }

  @Test
  @DisplayName("start：创建和展开必须处于同一个事务边界")
  void startIsTransactional() throws Exception {
    Method method = MonthlyRepriceStartServiceImpl.class.getMethod(
        "start", MonthlyRepriceBatchCreateRequest.class, String.class);

    assertThat(method.getAnnotation(Transactional.class).rollbackFor())
        .contains(Exception.class);
  }
}
