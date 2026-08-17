package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationResult;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.OutboxPage;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.PublicationFailures;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.Reconciliation;
import com.sanhua.marketingcost.service.collaboration.CollaborationOperationsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/collaboration")
public class CollaborationOperationsController {
  private final CollaborationOperationsService service;

  public CollaborationOperationsController(CollaborationOperationsService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('collaboration:operations:read')")
  @GetMapping("/reconciliation")
  public CommonResult<Reconciliation> reconciliation() {
    return CommonResult.success(service.reconcile());
  }

  @PreAuthorize("@ss.hasPermi('collaboration:operations:read')")
  @GetMapping("/outbox")
  public CommonResult<OutboxPage> outbox(@RequestParam(defaultValue = "HOLD") String status) {
    return CommonResult.success(service.outbox(status));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:operations:read')")
  @GetMapping("/publication-failures")
  public CommonResult<PublicationFailures> publicationFailures() {
    return CommonResult.success(service.publicationFailures());
  }

  @PreAuthorize("@ss.hasPermi('collaboration:operations:compensate')")
  @PostMapping("/outbox/{id}/release")
  public CommonResult<CompensationResult> releaseOutbox(
      @PathVariable Long id, @RequestBody CompensationRequest request) {
    return CommonResult.success(service.releaseOutbox(id, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:operations:compensate')")
  @PostMapping("/approved-results/{id}/invalidate")
  public CommonResult<CompensationResult> invalidateApprovedResult(
      @PathVariable Long id, @RequestBody CompensationRequest request) {
    return CommonResult.success(service.invalidateApprovedResult(id, request));
  }
}
