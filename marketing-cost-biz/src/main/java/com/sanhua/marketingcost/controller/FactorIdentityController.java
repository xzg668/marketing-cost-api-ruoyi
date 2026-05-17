package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FactorLinkedItemReferenceDto;
import com.sanhua.marketingcost.service.FactorLinkedItemReferenceService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 影响因素身份相关只读查询。 */
@RestController
@RequestMapping("/api/v1/factor-identities")
public class FactorIdentityController {

  private final FactorLinkedItemReferenceService referenceService;

  public FactorIdentityController(FactorLinkedItemReferenceService referenceService) {
    this.referenceService = referenceService;
  }

  /** 查看某个影响因素身份当前被哪些联动价公式引用。 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:list') or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/{id}/linked-items")
  public CommonResult<List<FactorLinkedItemReferenceDto>> listLinkedItems(
      @PathVariable Long id,
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String businessUnitType) {
    try {
      return CommonResult.success(
          referenceService.listLinkedItems(id, pricingMonth, businessUnitType));
    } catch (IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
