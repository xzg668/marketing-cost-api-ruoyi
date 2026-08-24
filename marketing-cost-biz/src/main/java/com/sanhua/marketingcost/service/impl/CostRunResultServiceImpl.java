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

  public CostRunResultServiceImpl(
      QuoteCostRunVersionMapper versionMapper,
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      MaterialMasterMapper materialMasterMapper) {
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
            item == null ? null : item.getProductAttr(),
            form == null ? null : form.getProductAttr()));
    return dto;
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

  private String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first.trim() : trimToNull(second);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
