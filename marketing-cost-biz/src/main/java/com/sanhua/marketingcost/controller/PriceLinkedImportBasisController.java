package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisResponse;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 类型 2 联动价公式版本的“查看导入依据”只读接口。 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedImportBasisController {

  private final PriceLinkedImportBasisService service;

  public PriceLinkedImportBasisController(PriceLinkedImportBasisService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items/{id}/import-basis")
  public CommonResult<PriceLinkedImportBasisResponse> getImportBasis(
      @PathVariable Long id) {
    PriceLinkedImportBasisResponse response = service.getImportBasis(id);
    if (response == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "linked item not found or not accessible");
    }
    return CommonResult.success(response);
  }
}
