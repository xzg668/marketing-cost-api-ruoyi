package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从唯一成本版本主表组装结果头，产品和客户名称直接读取主数据，避免重复存储。 */
@Service
public class CostRunResultServiceImpl implements CostRunResultService {

  private final QuoteCostRunVersionMapper versionMapper;
  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;
  private final MaterialMasterMapper materialMasterMapper;
  private final com.sanhua.marketingcost.mapper.CostRunCostItemMapper costItems;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public CostRunResultServiceImpl(
      QuoteCostRunVersionMapper versionMapper,
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      MaterialMasterMapper materialMasterMapper,
      com.sanhua.marketingcost.mapper.CostRunCostItemMapper costItems, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.costItems = costItems;
    this.json = json;
    this.versionMapper = versionMapper;
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.materialMasterMapper = materialMasterMapper;
  }

  @Override
  public CostRunResultDto getResult(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return null;
    }
    var query =
        Wrappers.lambdaQuery(QuoteCostRunVersion.class)
            .eq(QuoteCostRunVersion::getOaNo, oaNo.trim())
            .eq(
                StringUtils.hasText(productCode),
                QuoteCostRunVersion::getProductCode,
                trimToNull(productCode))
            .orderByDesc(QuoteCostRunVersion::getTrialFinishedAt)
            .orderByDesc(QuoteCostRunVersion::getCreatedAt)
            .orderByDesc(QuoteCostRunVersion::getId);
    List<QuoteCostRunVersion> versions = versionMapper.selectList(query);
    if (versions == null || versions.isEmpty()) {
      return null;
    }
    QuoteCostRunVersion selected =
        versions.stream()
            .filter(
                version ->
                    QuoteCostRunStatus.isCurrentSuccess(version.getStatus()))
            .findFirst()
            .orElseGet(
                () ->
                    versions.stream()
                        .filter(version -> version.getTotalCost() != null)
                        .findFirst()
                        .orElse(versions.get(0)));
    return toDto(selected);
  }

  @Override
  public CostRunResultDto getResult(Long costRunVersionId) {
    if (costRunVersionId == null) {
      return null;
    }
    return toDto(versionMapper.selectById(costRunVersionId));
  }

  private CostRunResultDto toDto(QuoteCostRunVersion version) {
    if (version == null) {
      return null;
    }
    OaForm form = findForm(version.getOaNo());
    OaFormItem item = findItem(version, form);
    MaterialMaster material = findMaterial(version.getProductCode());

    CostRunResultDto dto = new CostRunResultDto();
    dto.setOaNo(version.getOaNo());
    dto.setProductCode(version.getProductCode());
    dto.setProductName(
        firstText(
            material == null ? null : material.getMaterialName(),
            item == null ? null : item.getProductName()));
    dto.setProductModel(
        firstText(
            material == null ? null : material.getItemModel(),
            item == null ? null : item.getSunlModel()));
    dto.setCustomerName(form == null ? null : trimToNull(form.getCustomer()));
    dto.setBusinessUnit(
        form == null ? null : trimToNull(form.getSourceBusinessDivision()));
    dto.setDepartment(form == null ? null : trimToNull(form.getApplicantDept()));
    dto.setPeriod(version.getResultPeriod());
    dto.setTotalCost(version.getTotalCost());
    dto.setFinanceMaterialCost(version.getFinanceMaterialCost());
    dto.setOaMaterialCost(version.getOaMaterialCost());
    dto.setCuMaterialAdjustment(version.getCuMaterialAdjustment());
    dto.setFinalQuoteAmount(version.getFinalQuoteAmount());
    dto.setCalcStatus(calcStatus(version.getStatus(), version.getTotalCost() != null));
    dto.setProductAttr(
        firstText(
            technicalProperty(version),
            item == null ? null : item.getProductAttr(),
            form == null ? null : form.getProductAttr()));
    return dto;
  }

  /** 属性名称跟随本次实际计算来源，查看历史版本时不读取另一版的补录。 */
  private String technicalProperty(QuoteCostRunVersion version) {
    if (!StringUtils.hasText(version.getTechDataInputJson())) return null;
    var rows = costItems.selectList(Wrappers.<com.sanhua.marketingcost.entity.CostRunCostItem>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.CostRunCostItem::getCostRunVersionId, version.getId())
        .eq(com.sanhua.marketingcost.entity.CostRunCostItem::getCostCode, "ADJUSTED_MANUFACTURE_COST")
        .eq(com.sanhua.marketingcost.entity.CostRunCostItem::getSourceTable, "lp_quote_tech_data_version"));
    if (rows.isEmpty()) return null;
    if (rows.size() != 1) throw new IllegalStateException("本成本版本的产品属性来源不唯一");
    try {
      String property = json.readTree(version.getTechDataInputJson()).path("productProperty").asText(null);
      if (!StringUtils.hasText(property)) throw new IllegalStateException("本成本版本缺少实际采用的补录产品属性");
      return property;
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalStateException("成本版本技术输入快照无法读取", exception);
    }
  }

  private OaForm findForm(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return null;
    }
    return formMapper.selectOne(
        Wrappers.lambdaQuery(OaForm.class)
            .eq(OaForm::getOaNo, oaNo.trim())
            .last("LIMIT 1"));
  }

  private OaFormItem findItem(QuoteCostRunVersion version, OaForm form) {
    if (version.getOaFormItemId() != null) {
      OaFormItem item = itemMapper.selectById(version.getOaFormItemId());
      if (item != null) {
        return item;
      }
    }
    if (form == null || form.getId() == null || !StringUtils.hasText(version.getProductCode())) {
      return null;
    }
    return itemMapper.selectOne(
        Wrappers.lambdaQuery(OaFormItem.class)
            .eq(OaFormItem::getOaFormId, form.getId())
            .eq(OaFormItem::getMaterialNo, version.getProductCode())
            .orderByDesc(OaFormItem::getId)
            .last("LIMIT 1"));
  }

  private MaterialMaster findMaterial(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    return materialMasterMapper.selectOne(
        Wrappers.lambdaQuery(MaterialMaster.class)
            .eq(MaterialMaster::getMaterialCode, productCode.trim())
            .last("LIMIT 1"));
  }

  private String calcStatus(String status, boolean hasResult) {
    if (QuoteCostRunStatus.isCurrentSuccess(status) || "HISTORY".equals(status) || hasResult) {
      return "已核算";
    }
    if ("RUNNING".equals(status) || "TRIAL".equals(status)) {
      return "试算中";
    }
    return "未核算";
  }

  private String firstText(String... values) {
    for (String value : values) if (StringUtils.hasText(value)) return value.trim();
    return null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
