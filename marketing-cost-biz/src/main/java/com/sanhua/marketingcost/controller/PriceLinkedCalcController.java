package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedCalcPageResponse;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

/**
 * 联动价格计算控制器 - 查询联动价格计算结果与刷新
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedCalcController {
  private final PriceLinkedCalcService priceLinkedCalcService;

  public PriceLinkedCalcController(PriceLinkedCalcService priceLinkedCalcService) {
    this.priceLinkedCalcService = priceLinkedCalcService;
  }

  /** 查询联动价格计算列表 */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/calc")
  public CommonResult<PriceLinkedCalcPageResponse> list(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String itemCode,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<com.sanhua.marketingcost.dto.PriceLinkedCalcRow> pager =
        priceLinkedCalcService.page(oaNo, itemCode, shapeAttr, current, size);
    return CommonResult.success(new PriceLinkedCalcPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 刷新联动价格计算数据 */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:edit')")
  @PostMapping("/calc/refresh")
  public CommonResult<Integer> refresh(@RequestBody(required = false) java.util.Map<String, String> body) {
    String oaNo = body == null ? null : body.get("oaNo");
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"missing oaNo");
    }
    return CommonResult.success(priceLinkedCalcService.refresh(oaNo));
  }
}
