package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationContextResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSaveResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.service.QuoteBomSupplementCollaborationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collaborate/bom-supplement")
public class BomSupplementCollaborationApiController {

  private final QuoteBomSupplementCollaborationService collaborationService;

  public BomSupplementCollaborationApiController(
      QuoteBomSupplementCollaborationService collaborationService) {
    this.collaborationService = collaborationService;
  }

  @GetMapping("/context")
  public CommonResult<BomSupplementCollaborationContextResponse> context(
      @RequestParam("token") String token) {
    try {
      return CommonResult.success(collaborationService.getContext(token));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), ex.getMessage());
    }
  }

  @GetMapping("/package-reference")
  public CommonResult<PackageComponentStructureReadResult> packageReference(
      @RequestParam("token") String token,
      @RequestParam("taskId") Long taskId,
      @RequestParam("referenceFinishedCode") String referenceFinishedCode,
      @RequestParam(value = "sourceTopProductCode", required = false) String sourceTopProductCode,
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(
          collaborationService.readPackageReference(
              token, taskId, referenceFinishedCode, sourceTopProductCode, periodMonth));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PostMapping("/{taskId}/save-draft")
  public CommonResult<BomSupplementCollaborationSaveResponse> saveDraft(
      @PathVariable Long taskId,
      @RequestParam("token") String token,
      @RequestBody BomSupplementCollaborationSubmitRequest request) {
    try {
      return CommonResult.success(collaborationService.saveDraft(token, taskId, request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PostMapping("/{taskId}/submit")
  public CommonResult<BomSupplementCollaborationSaveResponse> submit(
      @PathVariable Long taskId,
      @RequestParam("token") String token,
      @RequestBody BomSupplementCollaborationSubmitRequest request) {
    try {
      return CommonResult.success(collaborationService.submit(token, taskId, request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
