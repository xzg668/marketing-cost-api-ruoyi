package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
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

  public MakePartWeightServiceImpl(MaterialMasterRawMapper materialMasterRawMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public MakePartWeightResult resolveWeights(
      String parentMaterialNo, BomU9Source child, String itemProcessType) {
    String parentCode = trim(parentMaterialNo);
    String childCode = child == null ? null : trim(child.getChildMaterialNo());
    if (parentCode == null || childCode == null) {
      return MakePartWeightResult.of(
          parentCode, childCode, itemProcessType, null, null, "MISSING_WEIGHT", "parent 或 child 料号为空");
    }
    Map<String, MaterialMasterRaw> rawByCode = loadLatestRawRows(List.of(parentCode, childCode));
    BigDecimal netWeightG = theoreticalNetWeightG(rawByCode.get(parentCode));
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
      remark = appendRemark(remark, "缺 parent 理论净重(parent_material_no=" + parentCode + ")");
    }
    String status = grossWeightG == null || netWeightG == null ? "MISSING_WEIGHT" : "OK";
    return MakePartWeightResult.of(
        parentCode, childCode, itemProcessType, grossWeightG, netWeightG, status, remark);
  }

  private Map<String, MaterialMasterRaw> loadLatestRawRows(Collection<String> codes) {
    List<MaterialMasterRaw> rows = materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null);
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
}
