package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.collaboration.CollaborationPortalAccessLinkResponse;
import com.sanhua.marketingcost.service.collaboration.CollaborationDomainException;
import com.sanhua.marketingcost.service.collaboration.CollaborationPortalLinkService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部报价员生成 OA 外部协作入口；外部协作者不能调用本接口扩大自己的权限。 */
@RestController
@RequestMapping("/api/v1/collaboration/product-tasks")
public class CollaborationPortalAccessController {
  private final CollaborationPortalLinkService linkService;

  public CollaborationPortalAccessController(CollaborationPortalLinkService linkService) {
    this.linkService = linkService;
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:create')")
  @PostMapping("/{taskId}/access-link")
  public CommonResult<CollaborationPortalAccessLinkResponse> issue(@PathVariable Long taskId) {
    try {
      return CommonResult.success(linkService.issue(taskId));
    } catch (CollaborationDomainException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(),
          exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
