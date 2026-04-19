package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.OaFormDetailDto;
import com.sanhua.marketingcost.dto.OaFormListItemDto;
import com.sanhua.marketingcost.service.OaFormService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OA表单控制器 - 查询OA表单列表与详情
 */
@RestController
@RequestMapping("/api/v1/oa-forms")
public class OaFormController {
  private final OaFormService oaFormService;

  public OaFormController(OaFormService oaFormService) {
    this.oaFormService = oaFormService;
  }

  /** 查询OA表单列表 */
  @PreAuthorize("@ss.hasAnyPermi('cost:oa-form:list','ingest:oa:list','ingest:oa-form:list')")
  @GetMapping
  public CommonResult<List<OaFormListItemDto>> list(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String formType,
      @RequestParam(required = false) String customer,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate endDate) {
    return CommonResult.success(
        oaFormService.listForms(oaNo, formType, customer, status, startDate, endDate));
  }

  /** 查询OA表单详情 */
  @PreAuthorize("@ss.hasAnyPermi('cost:oa-form:query','cost:oa-form:list','ingest:oa:query','ingest:oa:list','ingest:oa-form:query','ingest:oa-form:list')")
  @GetMapping("/{oaNo}")
  public CommonResult<OaFormDetailDto> detail(@PathVariable String oaNo) {
    OaFormDetailDto detail = oaFormService.getDetailByOaNo(oaNo);
    if (detail == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oa form not found");
    }
    return CommonResult.success(detail);
  }
}
