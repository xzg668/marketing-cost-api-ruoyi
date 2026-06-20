package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.OaFormDetailDto;
import com.sanhua.marketingcost.dto.OaFormDetailItemDto;
import com.sanhua.marketingcost.dto.OaFormDetailKeyDto;
import com.sanhua.marketingcost.dto.OaFormListItemDto;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.service.OaFormService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OaFormServiceImpl implements OaFormService {
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final CostRunResultMapper costRunResultMapper;
  private final MaterialMasterMapper materialMasterMapper;

  public OaFormServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      CostRunResultMapper costRunResultMapper,
      MaterialMasterMapper materialMasterMapper) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.costRunResultMapper = costRunResultMapper;
    this.materialMasterMapper = materialMasterMapper;
  }

  @Override
  public List<OaFormListItemDto> listForms(
      String oaNo,
      String formType,
      String customer,
      String status,
      LocalDate startDate,
      LocalDate endDate) {
    var query = Wrappers.lambdaQuery(OaForm.class);
    if (StringUtils.hasText(oaNo)) {
      query.like(OaForm::getOaNo, oaNo.trim());
    }
    if (StringUtils.hasText(formType)) {
      query.eq(OaForm::getFormType, formType.trim());
    }
    if (StringUtils.hasText(customer)) {
      query.like(OaForm::getCustomer, customer.trim());
    }
    if (StringUtils.hasText(status)) {
      query.eq(OaForm::getCalcStatus, status.trim());
    }
    if (startDate != null) {
      query.ge(OaForm::getApplyDate, startDate);
    }
    if (endDate != null) {
      query.le(OaForm::getApplyDate, endDate);
    }
    query.orderByDesc(OaForm::getApplyDate).orderByDesc(OaForm::getId);

    return oaFormMapper.selectList(query).stream()
        .map(this::toListItem)
        .collect(Collectors.toList());
  }

  @Override
  public OaFormDetailDto getDetailByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return null;
    }
    OaForm form = oaFormMapper.selectOne(Wrappers.lambdaQuery(OaForm.class)
        .eq(OaForm::getOaNo, oaNo.trim()));
    if (form == null) {
      return null;
    }

    List<OaFormItem> items = oaFormItemMapper.selectList(Wrappers.lambdaQuery(OaFormItem.class)
        .eq(OaFormItem::getOaFormId, form.getId())
        .orderByAsc(OaFormItem::getSeq)
        .orderByAsc(OaFormItem::getId));

    CostRunTotalIndex costTotalIndex = buildCostRunTotalIndex(form.getOaNo());
    Map<String, MaterialMaster> materialMasterMap = buildMaterialMasterMap(items);
    OaFormDetailDto detail = new OaFormDetailDto();
    detail.setKey(toKeyDto(form));
    detail.setItems(items.stream()
        .map((item) -> toItemDto(item, costTotalIndex, materialMasterMap))
        .collect(Collectors.toList()));
    return detail;
  }

  private OaFormListItemDto toListItem(OaForm form) {
    OaFormListItemDto dto = new OaFormListItemDto();
    dto.setOaNo(form.getOaNo());
    dto.setFormType(form.getFormType());
    dto.setApplyDate(form.getApplyDate());
    dto.setCustomer(form.getCustomer());
    dto.setCopperPrice(form.getCopperPrice());
    dto.setZincPrice(form.getZincPrice());
    dto.setAluminumPrice(form.getAluminumPrice());
    dto.setSteelPrice(form.getSteelPrice());
    dto.setOtherMaterial(form.getOtherMaterial());
    dto.setBaseShipping(form.getBaseShipping());
    dto.setCalcStatus(normalizeCalcStatus(form.getCalcStatus(), form.getCalcAt() != null));
    return dto;
  }

  private OaFormDetailKeyDto toKeyDto(OaForm form) {
    OaFormDetailKeyDto dto = new OaFormDetailKeyDto();
    dto.setOaNo(form.getOaNo());
    dto.setFormType(form.getFormType());
    dto.setApplyDate(form.getApplyDate());
    dto.setCustomer(form.getCustomer());
    dto.setCopperPrice(form.getCopperPrice());
    dto.setZincPrice(form.getZincPrice());
    dto.setAluminumPrice(form.getAluminumPrice());
    dto.setSteelPrice(form.getSteelPrice());
    dto.setOtherMaterial(form.getOtherMaterial());
    dto.setBaseShipping(form.getBaseShipping());
    dto.setCalcStatus(normalizeCalcStatus(form.getCalcStatus(), form.getCalcAt() != null));
    dto.setSaleLink(form.getSaleLink());
    dto.setRemark(form.getRemark());
    return dto;
  }

  private OaFormDetailItemDto toItemDto(
      OaFormItem item,
      CostRunTotalIndex costTotalIndex,
      Map<String, MaterialMaster> materialMasterMap) {
    OaFormDetailItemDto dto = new OaFormDetailItemDto();
    dto.setId(item.getId());
    dto.setSeq(item.getSeq());
    MaterialMaster materialMaster = null;
    if (materialMasterMap != null && StringUtils.hasText(item.getMaterialNo())) {
      materialMaster = materialMasterMap.get(item.getMaterialNo().trim());
    }
    String productName =
        materialMaster == null ? null : trimToNull(materialMaster.getMaterialName());
    if (!StringUtils.hasText(productName)) {
      productName = trimToNull(item.getProductName());
    }
    String productModel =
        materialMaster == null ? null : trimToNull(materialMaster.getItemModel());
    if (!StringUtils.hasText(productModel)) {
      productModel = trimToNull(item.getSunlModel());
    }
    dto.setProductName(productName);
    dto.setCustomerDrawing(item.getCustomerDrawing());
    dto.setCustomerCode(item.getCustomerCode());
    dto.setMaterialNo(item.getMaterialNo());
    dto.setSunlModel(productModel);
    dto.setSpec(item.getSpec());
    dto.setPackageType(item.getPackageType());
    dto.setPackageMethod(item.getPackageMethod());
    dto.setPackageComponentCode(item.getPackageComponentCode());
    dto.setShippingFee(item.getShippingFee());
    dto.setSupportQty(item.getSupportQty());
    dto.setTotalWithShip(item.getTotalWithShip());
    dto.setTotalNoShip(item.getTotalNoShip());
    dto.setMaterialCost(item.getMaterialCost());
    dto.setLaborCost(item.getLaborCost());
    dto.setManufacturingCost(item.getManufacturingCost());
    dto.setManagementCost(item.getManagementCost());
    dto.setValidMonth(item.getValidMonth());
    dto.setSus304WeightG(item.getSus304WeightG());
    dto.setSus316WeightG(item.getSus316WeightG());
    dto.setCopperWeightG(item.getCopperWeightG());
    dto.setValidDate(item.getValidDate());
    dto.setCalcAt(item.getCalcAt());
    BigDecimal totalCost = costTotalIndex == null ? null : costTotalIndex.totalCostOf(item);
    if (totalCost != null) {
      dto.setUnitCost(totalCost);
      if (item.getSupportQty() != null) {
        dto.setCostAmount(totalCost.multiply(item.getSupportQty()));
      }
    }
    dto.setCalcStatus(normalizeCalcStatus(item.getCalcStatus(), item.getCalcAt() != null || totalCost != null));
    return dto;
  }

  private CostRunTotalIndex buildCostRunTotalIndex(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CostRunTotalIndex.empty();
    }
    Map<Long, BigDecimal> totalByItemId = new HashMap<>();
    Map<String, BigDecimal> legacyTotalByProductCode = new HashMap<>();
    List<CostRunResult> results =
        costRunResultMapper.selectList(
            Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getOaNo, oaNo.trim())
                .orderByDesc(CostRunResult::getCalcAt)
                .orderByDesc(CostRunResult::getId));
    if (results != null) {
      for (CostRunResult result : results) {
        if (result.getTotalCost() == null) {
          continue;
        }
        if (result.getOaFormItemId() != null) {
          totalByItemId.putIfAbsent(result.getOaFormItemId(), result.getTotalCost());
          continue;
        }
        String productCode = result.getProductCode() == null ? null : result.getProductCode().trim();
        if (StringUtils.hasText(productCode)) {
          legacyTotalByProductCode.putIfAbsent(productCode, result.getTotalCost());
        }
      }
    }

    return new CostRunTotalIndex(totalByItemId, legacyTotalByProductCode);
  }

  private String normalizeCalcStatus(String calcStatus, boolean hasCostResult) {
    String text = calcStatus == null ? "" : calcStatus.trim();
    if ("CALCULATING".equals(text) || "RUNNING".equals(text) || "试算中".equals(text)) {
      return "试算中";
    }
    if (hasCostResult || "CALCULATED".equals(text) || "DONE".equals(text) || "SUCCESS".equals(text) || "已核算".equals(text)) {
      return "已核算";
    }
    return "未核算";
  }

  private record CostRunTotalIndex(
      Map<Long, BigDecimal> totalByItemId,
      Map<String, BigDecimal> legacyTotalByProductCode) {

    static CostRunTotalIndex empty() {
      return new CostRunTotalIndex(Map.of(), Map.of());
    }

    BigDecimal totalCostOf(OaFormItem item) {
      if (item == null) {
        return null;
      }
      if (item.getId() != null) {
        BigDecimal byItemId = totalByItemId.get(item.getId());
        if (byItemId != null) {
          return byItemId;
        }
      }
      if (!StringUtils.hasText(item.getMaterialNo())) {
        return null;
      }
      return legacyTotalByProductCode.get(item.getMaterialNo().trim());
    }
  }

  private Map<String, MaterialMaster> buildMaterialMasterMap(List<OaFormItem> items) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    Set<String> codes =
        items.stream()
            .map(OaFormItem::getMaterialNo)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (codes.isEmpty()) {
      return Map.of();
    }
    List<MaterialMaster> list =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .in(MaterialMaster::getMaterialCode, codes));
    if (list == null || list.isEmpty()) {
      return Map.of();
    }
    Map<String, MaterialMaster> map = new HashMap<>();
    for (MaterialMaster materialMaster : list) {
      String code = materialMaster.getMaterialCode();
      if (StringUtils.hasText(code)) {
        String key = code.trim();
        if (!map.containsKey(key)) {
          map.put(key, materialMaster);
        }
      }
    }
    return map;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
