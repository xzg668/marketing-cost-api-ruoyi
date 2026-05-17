package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskRequest;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse;
import com.sanhua.marketingcost.service.ingest.BomSupplementTaskService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BomSupplementTaskControllerTest {
  private BomSupplementTaskService service;
  private BomSupplementTaskController controller;

  @BeforeEach
  void setUp() {
    service = mock(BomSupplementTaskService.class);
    controller = new BomSupplementTaskController(service);
  }

  @Test
  void batchCreateOaTaskReturnsServiceResponse() {
    BomSupplementBatchOaTaskRequest request = new BomSupplementBatchOaTaskRequest();
    request.setQuoteBomStatusIds(List.of(3001L));
    BomSupplementBatchOaTaskResponse response = new BomSupplementBatchOaTaskResponse();
    response.setCreatedTaskCount(1);
    when(service.batchCreateAndMockPush(request)).thenReturn(response);

    CommonResult<BomSupplementBatchOaTaskResponse> result = controller.batchCreateOaTask(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getCreatedTaskCount()).isEqualTo(1);
    verify(service).batchCreateAndMockPush(request);
  }

  @Test
  void batchCreateOaTaskReturnsBadRequestForBusinessException() {
    BomSupplementBatchOaTaskRequest request = new BomSupplementBatchOaTaskRequest();
    when(service.batchCreateAndMockPush(request)).thenThrow(new QuoteIngestException("请选择产品行"));

    CommonResult<BomSupplementBatchOaTaskResponse> result = controller.batchCreateOaTask(request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("请选择产品行");
  }
}
