package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunResultServiceImpl implements CostRunResultService {
  private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

  private final CostRunResultMapper costRunResultMapper;
  private final ProductPropertyMapper productPropertyMapper;
  private final MaterialMasterMapper materialMasterMapper;

  public CostRunResultServiceImpl(
      CostRunResultMapper costRunResultMapper,
      ProductPropertyMapper productPropertyMapper,
      MaterialMasterMapper materialMasterMapper) {
    this.costRunResultMapper = costRunResultMapper;
    this.productPropertyMapper = productPropertyMapper;
    this.materialMasterMapper = materialMasterMapper;
  }

  @Override
  public CostRunResultDto getResult(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return null;
    }
    String oaNoValue = oaNo.trim();
    String productCodeValue = StringUtils.hasText(productCode) ? productCode.trim() : null;

    CostRunResult result;
    if (StringUtils.hasText(productCodeValue)) {
      result =
          costRunResultMapper.selectOne(
              Wrappers.lambdaQuery(CostRunResult.class)
                  .eq(CostRunResult::getOaNo, oaNoValue)
                  .eq(CostRunResult::getProductCode, productCodeValue)
                  .last("LIMIT 1"));
    } else {
      List<CostRunResult> list =
          costRunResultMapper.selectList(
              Wrappers.lambdaQuery(CostRunResult.class)
                  .eq(CostRunResult::getOaNo, oaNoValue)
                  .orderByAsc(CostRunResult::getId)
                  .last("LIMIT 1"));
      result = list.isEmpty() ? null : list.get(0);
    }

    if (result == null) {
      return null;
    }
    MaterialMaster materialMaster = findMaterialMaster(result.getProductCode());
    String productName =
        materialMaster == null ? null : trimToNull(materialMaster.getMaterialName());
    String productModel =
        materialMaster == null ? null : trimToNull(materialMaster.getItemModel());
    if (!StringUtils.hasText(productName)) {
      productName = trimToNull(result.getProductName());
    }
    if (!StringUtils.hasText(productModel)) {
      productModel = trimToNull(result.getProductModel());
    }
    CostRunResultDto dto = new CostRunResultDto();
    dto.setOaNo(result.getOaNo());
    dto.setProductCode(result.getProductCode());
    dto.setProductName(productName);
    dto.setProductModel(productModel);
    dto.setCustomerName(result.getCustomerName());
    dto.setBusinessUnit(result.getBusinessUnit());
    dto.setDepartment(result.getDepartment());
    dto.setPeriod(result.getPeriod());
    dto.setCurrency(result.getCurrency());
    dto.setUnit(result.getUnit());
    dto.setTotalCost(result.getTotalCost());
    dto.setCalcStatus(result.getCalcStatus());
    dto.setProductAttr(result.getProductAttr());
    return dto;
  }

  @Override
  public void saveOrUpdate(OaForm form, OaFormItem item) {
    if (form == null || item == null || !StringUtils.hasText(form.getOaNo())) {
      return;
    }
    String oaNo = form.getOaNo().trim();
    String productCode = item.getMaterialNo();
    if (!StringUtils.hasText(productCode)) {
      return;
    }
    String productCodeValue = productCode.trim();

    CostRunResult existing =
        costRunResultMapper.selectOne(
            Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getOaNo, oaNo)
                .eq(CostRunResult::getProductCode, productCodeValue)
                .last("LIMIT 1"));
    if (existing == null) {
      existing = new CostRunResult();
      existing.setOaNo(oaNo);
      existing.setProductCode(productCodeValue);
    }

    MaterialMaster materialMaster = findMaterialMaster(productCodeValue);
    String productName =
        materialMaster == null ? null : trimToNull(materialMaster.getMaterialName());
    String productModel =
        materialMaster == null ? null : trimToNull(materialMaster.getItemModel());
    if (!StringUtils.hasText(productName)) {
      productName = trimToNull(item.getProductName());
    }
    if (!StringUtils.hasText(productModel)) {
      productModel = trimToNull(item.getSunlModel());
    }
    existing.setProductName(productName);
    existing.setProductModel(productModel);
    existing.setCustomerName(trimToNull(form.getCustomer()));
    String calcStatus = trimToNull(form.getCalcStatus());
    existing.setCalcStatus(calcStatus == null ? "未核算" : calcStatus);
    existing.setCalcAt(LocalDateTime.now());
    existing.setPeriod(buildPeriod(form.getApplyDate()));
    existing.setProductAttr(findProductAttr(productCodeValue));

    if (existing.getId() == null) {
      costRunResultMapper.insert(existing);
    } else {
      costRunResultMapper.updateById(existing);
    }
  }

  @Override
  public void updateTotalCost(String oaNo, String productCode, java.math.BigDecimal totalCost) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return;
    }
    CostRunResult existing =
        costRunResultMapper.selectOne(
            Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getOaNo, oaNo.trim())
                .eq(CostRunResult::getProductCode, productCode.trim())
                .last("LIMIT 1"));
    if (existing == null) {
      // T7 修复：新建时要补齐 NOT NULL 字段（period / calc_status），否则 INSERT 失败。
      // period 取当月（applyDate 缺失时兜底为今天），与 ensureResult 口径对齐。
      existing = new CostRunResult();
      existing.setOaNo(oaNo.trim());
      existing.setProductCode(productCode.trim());
      existing.setTotalCost(totalCost);
      existing.setPeriod(LocalDate.now().format(PERIOD_FORMAT));
      existing.setCalcStatus("未核算");
      costRunResultMapper.insert(existing);
      return;
    }
    existing.setTotalCost(totalCost);
    costRunResultMapper.updateById(existing);
  }

  private String buildPeriod(LocalDate applyDate) {
    LocalDate date = applyDate == null ? LocalDate.now() : applyDate;
    return date.format(PERIOD_FORMAT);
  }

  private String findProductAttr(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    String period = LocalDate.now().format(PERIOD_FORMAT);
    ProductProperty property =
        productPropertyMapper.selectOne(
            Wrappers.lambdaQuery(ProductProperty.class)
                .eq(ProductProperty::getParentCode, productCode.trim())
                .eq(ProductProperty::getPeriod, period)
                .orderByDesc(ProductProperty::getId)
                .last("LIMIT 1"));
    return property == null ? null : property.getProductAttr();
  }

  private MaterialMaster findMaterialMaster(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    return materialMasterMapper.selectOne(
        Wrappers.lambdaQuery(MaterialMaster.class)
            .eq(MaterialMaster::getMaterialCode, productCode.trim())
            .last("LIMIT 1"));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
