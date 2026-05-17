package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.EnabledUpdateRequest;
import com.sanhua.marketingcost.dto.FactorQuoteBaseMappingPageResponse;
import com.sanhua.marketingcost.dto.FactorQuoteBaseMappingRequest;
import com.sanhua.marketingcost.dto.QuoteBasePriceMappingRulePageResponse;
import com.sanhua.marketingcost.dto.QuoteBasePriceMappingRuleRequest;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import com.sanhua.marketingcost.service.QuoteBasePriceMappingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/price-linked")
public class QuoteBasePriceMappingController {

  private final QuoteBasePriceMappingService service;
  private final ObjectMapper objectMapper;

  public QuoteBasePriceMappingController(
      QuoteBasePriceMappingService service, ObjectMapper objectMapper) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:list')"
      + " or @ss.hasPermi('price:linked-item:list')"
      + " or @ss.hasPermi('price:finance-base:list')")
  @GetMapping("/quote-base-mapping-rules")
  public CommonResult<QuoteBasePriceMappingRulePageResponse> listRules(
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String quoteFieldCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    Page<QuoteBasePriceMappingRule> result = service.pageRules(
        businessUnitType, quoteFieldCode, keyword, enabled, page, pageSize);
    return CommonResult.success(
        new QuoteBasePriceMappingRulePageResponse(result.getTotal(), result.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:add')"
      + " or @ss.hasPermi('price:quote-base-mapping:edit')"
      + " or @ss.hasPermi('price:finance-base:edit')")
  @PostMapping("/quote-base-mapping-rules")
  public CommonResult<QuoteBasePriceMappingRule> createRule(
      @RequestBody QuoteBasePriceMappingRuleRequest request,
      Authentication authentication) {
    try {
      return CommonResult.success(
          service.createRule(request.toEntity(objectMapper), currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:edit')"
      + " or @ss.hasPermi('price:finance-base:edit')")
  @PutMapping("/quote-base-mapping-rules/{id}")
  public CommonResult<QuoteBasePriceMappingRule> updateRule(
      @PathVariable Long id,
      @RequestBody QuoteBasePriceMappingRuleRequest request,
      Authentication authentication) {
    try {
      return CommonResult.success(
          service.updateRule(id, request.toEntity(objectMapper), currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:edit')"
      + " or @ss.hasPermi('price:finance-base:edit')")
  @PutMapping("/quote-base-mapping-rules/{id}/enabled")
  public CommonResult<Void> setRuleEnabled(
      @PathVariable Long id,
      @RequestBody EnabledUpdateRequest request,
      Authentication authentication) {
    if (request == null || request.getEnabled() == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "enabled 不能为空");
    }
    try {
      service.setRuleEnabled(id, request.getEnabled(), currentUsername(authentication));
      return CommonResult.success(null);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:remove')"
      + " or @ss.hasPermi('price:finance-base:edit')")
  @DeleteMapping("/quote-base-mapping-rules/{id}")
  public CommonResult<Void> deleteRule(@PathVariable Long id) {
    try {
      service.deleteRule(id);
      return CommonResult.success(null);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:list')"
      + " or @ss.hasPermi('price:linked-item:list')"
      + " or @ss.hasPermi('price:finance-base:list')")
  @GetMapping("/factor-quote-base-mappings")
  public CommonResult<FactorQuoteBaseMappingPageResponse> listMappings(
      @RequestParam(required = false) Long factorIdentityId,
      @RequestParam(required = false) String quoteFieldCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    Page<FactorQuoteBaseMapping> result = service.pageMappings(
        factorIdentityId, quoteFieldCode, keyword, enabled, page, pageSize);
    return CommonResult.success(
        new FactorQuoteBaseMappingPageResponse(result.getTotal(), result.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:edit')"
      + " or @ss.hasPermi('price:finance-base:edit')")
  @PutMapping("/factor-quote-base-mappings/{id}")
  public CommonResult<FactorQuoteBaseMapping> updateMapping(
      @PathVariable Long id,
      @RequestBody FactorQuoteBaseMappingRequest request,
      Authentication authentication) {
    try {
      return CommonResult.success(
          service.saveMapping(request.toEntity(id), currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('price:quote-base-mapping:edit')"
      + " or @ss.hasPermi('price:finance-base:edit')")
  @PutMapping("/factor-quote-base-mappings/{id}/enabled")
  public CommonResult<Void> setMappingEnabled(
      @PathVariable Long id,
      @RequestBody EnabledUpdateRequest request,
      Authentication authentication) {
    if (request == null || request.getEnabled() == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "enabled 不能为空");
    }
    try {
      service.setMappingEnabled(id, request.getEnabled(), currentUsername(authentication));
      return CommonResult.success(null);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  private <T> CommonResult<T> badRequest(IllegalArgumentException ex) {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }
}
