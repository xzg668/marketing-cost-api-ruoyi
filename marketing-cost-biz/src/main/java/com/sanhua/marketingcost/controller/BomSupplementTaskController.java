package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskRequest;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse;
import com.sanhua.marketingcost.service.ingest.BomSupplementTaskService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bom-supplement/tasks")
public class BomSupplementTaskController {
  private final BomSupplementTaskService bomSupplementTaskService;

  public BomSupplementTaskController(BomSupplementTaskService bomSupplementTaskService) {
    this.bomSupplementTaskService = bomSupplementTaskService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:bom-check')")
  @PostMapping("/batch-oa-task")
  public CommonResult<BomSupplementBatchOaTaskResponse> batchCreateOaTask(
      @RequestBody BomSupplementBatchOaTaskRequest request) {
    try {
      return CommonResult.success(bomSupplementTaskService.batchCreateAndMockPush(request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
