package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MakePartChildNetWeight;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MakePartChildNetWeightMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.MakePartWeightService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartWeightServiceImpl implements MakePartWeightService {

  private static final BigDecimal KG_TO_G = new BigDecimal("1000");

  private final MaterialMasterRawMapper materialMasterRawMapper;
  private final MakePartChildNetWeightMapper childNetWeightMapper;

  public MakePartWeightServiceImpl(
      MaterialMasterRawMapper materialMasterRawMapper,
      MakePartChildNetWeightMapper childNetWeightMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.childNetWeightMapper = childNetWeightMapper;
  }

  @Override
  public MakePartWeightResult resolveWeights(
      String parentMaterialNo,
      BomU9Source child,
      String itemProcessType,
      String periodMonth,
      boolean requireChildNetWeight) {
    String parentCode = trim(parentMaterialNo);
    String childCode = child == null ? null : trim(child.getChildMaterialNo());
    if (parentCode == null || childCode == null) {
      return MakePartWeightResult.of(
          parentCode, childCode, itemProcessType, null, null, "MISSING_WEIGHT", "parent 或 child 料号为空");
    }
    String organizationCode = requiredMaterialOrganization(child.getPriceOrgCode());
    Map<String, MaterialMasterRaw> rawByCode =
        loadLatestRawRows(List.of(parentCode, childCode), organizationCode);
    MakePartChildNetWeight childNetWeight = childNetWeightMapper.selectEffective(
        organizationCode,
        parentCode,
        childCode,
        trim(child.getBomVersion()),
        trim(periodMonth));
    BigDecimal netWeightG = childNetWeight == null ? null : childNetWeight.getNetWeightG();
    boolean parentNetWeightFallback = false;
    if (netWeightG == null && !requireChildNetWeight) {
      // 单子料制造件兼容历史数据：没有父子关系净重时，父件理论净重就是该唯一子料净重。
      netWeightG = theoreticalNetWeightG(rawByCode.get(parentCode));
      parentNetWeightFallback = netWeightG != null;
    }
    BigDecimal grossWeightG;
    String remark = "";
    if (MakePartProcessTypePolicy.PROCESS_TYPE_BLANK.equals(itemProcessType)) {
      // 毛坯加工：毛重取 child 理论净重；global_seg_3_theoretical_net_weight 已按 g 进入生成表。
      grossWeightG = theoreticalNetWeightG(rawByCode.get(childCode));
      if (grossWeightG == null) {
        remark = "缺 child 理论净重(child_material_no=" + childCode + ")";
      }
    } else {
      // 原材料加工：U9 qty_per_parent 按 kg 理解，生成表毛重字段统一换算为 g。
      grossWeightG = child.getQtyPerParent() == null ? null : child.getQtyPerParent().multiply(KG_TO_G);
      if (grossWeightG == null) {
        remark = "缺 qty_per_parent(child_material_no=" + childCode + ")";
      }
    }
    if (netWeightG == null) {
      remark = appendRemark(
          remark,
          requireChildNetWeight
              ? "缺父子材料净重(material_organization_code=" + organizationCode
                  + ", parent_material_no=" + parentCode
                  + ", child_material_no=" + childCode
                  + ", bom_version=" + nullToBlank(trim(child.getBomVersion()))
                  + ", period_month=" + nullToBlank(trim(periodMonth)) + ")"
              : "缺父子材料净重且缺 parent 理论净重(parent_material_no=" + parentCode + ")");
    } else if (parentNetWeightFallback) {
      remark = appendRemark(remark, "单子料兼容：净重取 parent 理论净重");
    }
    String status = grossWeightG == null || netWeightG == null ? "MISSING_WEIGHT" : "OK";
    return MakePartWeightResult.of(
        parentCode, childCode, itemProcessType, grossWeightG, netWeightG, status, remark);
  }

  private Map<String, MaterialMasterRaw> loadLatestRawRows(
      Collection<String> codes, String organizationCode) {
    List<MaterialMasterRaw> rows =
        materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null, organizationCode);
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    Map<String, MaterialMasterRaw> result = new LinkedHashMap<>();
    for (MaterialMasterRaw row : rows) {
      String code = trim(row.getMaterialCode());
      if (code != null) {
        result.putIfAbsent(code, row);
      }
    }
    return result;
  }

  private String requiredMaterialOrganization(String priceOrgCode) {
    if (!StringUtils.hasText(priceOrgCode)) {
      throw new IllegalArgumentException("自制件重量计算缺少上游 priceOrgCode");
    }
    return MaterialOrganization.fromPriceOrgCode(priceOrgCode).getCode();
  }

  private BigDecimal theoreticalNetWeightG(MaterialMasterRaw row) {
    if (row == null) {
      return null;
    }
    return parseDecimal(row.getGlobalSeg3TheoreticalNetWeight());
  }

  private BigDecimal parseDecimal(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim().replace(",", "");
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String appendRemark(String first, String second) {
    if (!StringUtils.hasText(first)) {
      return second;
    }
    return first + "；" + second;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }
}
