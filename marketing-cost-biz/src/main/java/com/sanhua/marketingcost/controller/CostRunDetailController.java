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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // T12：批量查主档把 cost_element 灌进 partItems（前端 T13 用来分组/染色）
    enrichPartsWithCostElement(filteredParts);
    List<CostRunCostItemDto> costItems =
        costRunCostItemService.listStoredByOaNo(oaNo, productCodeValue);
    CostRunDetailDto dto = new CostRunDetailDto();
    dto.setPartItems(filteredParts);
    dto.setCostItems(costItems);
    // T12：从 costItems 抽 TOTAL 给顶层 total，省得前端扫数组
    dto.setTotal(extractTotal(costItems));
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

  /**
   * T12：批量给 partItems 灌 cost_element。一次 IN 查询代替逐行 N+1。
   * 主档查不到的部品 cost_element 留 null（前端按"未分类"显示即可）。
   */
  private void enrichPartsWithCostElement(List<CostRunPartItemDto> parts) {
    if (parts == null || parts.isEmpty()) {
      return;
    }
    Set<String> partCodes = new LinkedHashSet<>();
    for (CostRunPartItemDto p : parts) {
      if (StringUtils.hasText(p.getPartCode())) {
        partCodes.add(p.getPartCode().trim());
      }
    }
    if (partCodes.isEmpty()) {
      return;
    }
    List<MaterialMaster> rows =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .in(MaterialMaster::getMaterialCode, partCodes));
    Map<String, String> codeToElement = new HashMap<>();
    for (MaterialMaster m : rows) {
      if (m.getMaterialCode() != null) {
        codeToElement.put(m.getMaterialCode(), m.getCostElement());
      }
    }
    for (CostRunPartItemDto p : parts) {
      if (StringUtils.hasText(p.getPartCode())) {
        p.setCostElement(codeToElement.get(p.getPartCode().trim()));
      }
    }
  }

  /** T12：从 costItems 抽 cost_code='TOTAL' 行的 amount 作为顶层 total；找不到返 null。 */
  private BigDecimal extractTotal(List<CostRunCostItemDto> costItems) {
    if (costItems == null) {
      return null;
    }
    for (CostRunCostItemDto c : costItems) {
      if ("TOTAL".equals(c.getCostCode())) {
        return c.getAmount();
      }
    }
    return null;
  }
}
