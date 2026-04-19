package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunDetailDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本试算明细控制器 - 查询试算明细数据
 */
@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunDetailController {
  private final CostRunPartItemService costRunPartItemService;
  private final CostRunCostItemService costRunCostItemService;
  private final CostRunResultService costRunResultService;
  private final MaterialMasterMapper materialMasterMapper;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;

  public CostRunDetailController(
      CostRunPartItemService costRunPartItemService,
      CostRunCostItemService costRunCostItemService,
      CostRunResultService costRunResultService,
      MaterialMasterMapper materialMasterMapper,
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper) {
    this.costRunPartItemService = costRunPartItemService;
    this.costRunCostItemService = costRunCostItemService;
    this.costRunResultService = costRunResultService;
    this.materialMasterMapper = materialMasterMapper;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
  }

  /** 查询试算明细列表 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/detail")
  public CommonResult<CostRunDetailDto> getDetail(
      @RequestParam String oaNo, @RequestParam String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    if (!StringUtils.hasText(productCode)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"productCode is required");
    }
    String productCodeValue = productCode.trim();
    List<CostRunPartItemDto> partItems = costRunPartItemService.listStoredByOaNo(oaNo);
    List<CostRunPartItemDto> filteredParts = new ArrayList<>();
    for (CostRunPartItemDto item : partItems) {
      if (productCodeValue.equals(item.getProductCode())) {
        filteredParts.add(item);
      }
    }
    List<CostRunCostItemDto> costItems =
        costRunCostItemService.listStoredByOaNo(oaNo, productCodeValue);
    CostRunDetailDto dto = new CostRunDetailDto();
    dto.setPartItems(filteredParts);
    dto.setCostItems(costItems);
    MaterialMaster materialMaster =
        materialMasterMapper.selectOne(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .eq(MaterialMaster::getMaterialCode, productCodeValue)
                .last("LIMIT 1"));
    String productName =
        materialMaster == null ? null : trimToNull(materialMaster.getMaterialName());
    String productModel =
        materialMaster == null ? null : trimToNull(materialMaster.getItemModel());
    var result = costRunResultService.getResult(oaNo, productCodeValue);
    if (result != null) {
      dto.setProductAttr(result.getProductAttr());
    }
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo.trim()).last("LIMIT 1"));
    if (form != null) {
      dto.setCopperPrice(form.getCopperPrice());
      dto.setZincPrice(form.getZincPrice());
      OaFormItem formItem =
          oaFormItemMapper.selectOne(
              Wrappers.lambdaQuery(OaFormItem.class)
                  .eq(OaFormItem::getOaFormId, form.getId())
                  .eq(OaFormItem::getMaterialNo, productCodeValue)
                  .last("LIMIT 1"));
      if (formItem != null) {
        if (!StringUtils.hasText(productName)) {
          productName = trimToNull(formItem.getProductName());
        }
        if (!StringUtils.hasText(productModel)) {
          productModel = trimToNull(formItem.getSunlModel());
        }
      }
    }
    dto.setProductName(productName);
    dto.setProductModel(productModel);
    return CommonResult.success(dto);
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
