package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalDispatchRequest;
import com.sanhua.marketingcost.service.oa.OaTechnicalDispatchDebugService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "integration.oa-workflow", name = "debug-enabled", havingValue = "true")
@RequestMapping("/api/v1/integration/oa/debug/technical-dispatch")
@PreAuthorize("hasAnyAuthority('ROLE_admin', 'ROLE_ADMIN')")
public class OaTechnicalDispatchDebugController {
  private final OaTechnicalDispatchDebugService service;

  public OaTechnicalDispatchDebugController(OaTechnicalDispatchDebugService service) {
    this.service = service;
  }

  @PostMapping("/preview")
  public CommonResult<OaTechnicalDispatchDebugService.Preview> preview(@Valid @RequestBody OaTechnicalDispatchRequest request) {
    return CommonResult.success(service.preview(request));
  }

  @PostMapping("/send")
  @OperationLog(module = "OA技术分派调试", operationType = OperationType.OTHER)
  public CommonResult<OaTechnicalDispatchDebugService.Sent> send(@Valid @RequestBody OaTechnicalDispatchRequest request) {
    // code=0 表示调试接口执行完成；OA 是否办理成功必须读取 data.result.status。
    return CommonResult.success(service.send(request));
  }
}
