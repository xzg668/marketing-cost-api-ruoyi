package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CostRunDetailDto;
import com.sanhua.marketingcost.service.jjb.JjbReadService;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunDetailController {
  private final JjbReadService reader;
  public CostRunDetailController(JjbReadService reader) {this.reader=reader;}
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  @GetMapping("/detail")
  public CommonResult<CostRunDetailDto> getDetail(@RequestParam String oaNo,@RequestParam String productCode,
      @RequestParam(required=false) String costRunNo,@RequestParam(required=false) Long oaFormItemId,
      @RequestParam(required=false) Long versionId) {
    var view=reader.resolve(oaNo,productCode,oaFormItemId,versionId,costRunNo);var sheet=view.sheet();
    var dto=new CostRunDetailDto();dto.setSheet(sheet);dto.setPartItems(view.parts());dto.setCostItems(view.costs());
    dto.setCostRunNo(sheet.identity().costRunNo());dto.setVersionNo(sheet.identity().versionNo());
    dto.setProductName(sheet.header().productName());dto.setProductModel(sheet.header().productModel());dto.setProductAttr(sheet.header().productProperty());
    dto.setTotal(number(sheet.totals().totalCost()));dto.setCuMaterialAdjustment(number(sheet.totals().cuMaterialAdjustment()));
    dto.setFinalQuoteAmount(number(sheet.totals().finalQuoteAmount()));dto.setCopperPrice(number(sheet.header().customerCuPrice()));
    dto.setFinanceCuPrice(number(sheet.header().financeCuPrice()));
    return CommonResult.success(dto);
  }
  private static BigDecimal number(String value){return value==null?null:new BigDecimal(value);}
}
