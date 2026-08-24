package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MakePartChildNetWeight;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MakePartChildNetWeightMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.MakePartWeightService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartWeightServiceImpl implements MakePartWeightService {

  private static final BigDecimal KG_TO_G = new BigDecimal("1000");
  private static final String BOM_PURPOSE_MAIN_MANUFACTURING = "主制造";
  private static final String STATUS_APPROVED = "已核准";

  private final MaterialMasterRawMapper materialMasterRawMapper;
  private final MakePartChildNetWeightMapper childNetWeightMapper;
  private final MakePartScrapMappingService scrapMappingService;
  private final U9BomByproductMasterMapper byproductMapper;

  public MakePartWeightServiceImpl(
      MaterialMasterRawMapper materialMasterRawMapper,
      MakePartChildNetWeightMapper childNetWeightMapper,
      MakePartScrapMappingService scrapMappingService,
      U9BomByproductMasterMapper byproductMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.childNetWeightMapper = childNetWeightMapper;
    this.scrapMappingService = scrapMappingService;
    this.byproductMapper = byproductMapper;
  }

  @Override
  public MakePartWeightResult resolveWeights(
      String parentMaterialNo,
      BomU9Source child,
      String itemProcessType,
      String periodMonth,
      LocalDate asOfDate,
      String businessUnitType,
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
    boolean parentNetWeightUsed = false;
    if (netWeightG == null && !requireChildNetWeight) {
      // 单子料制造件沿用原业务口径：料品档案理论净重是正式净重来源，
      // U9 副产品仅在料品档案也没有净重时用于兜底反算。
      netWeightG = theoreticalNetWeightG(rawByCode.get(parentCode));
      parentNetWeightUsed = netWeightG != null;
      if (parentNetWeightUsed) {
        remark = appendRemark(remark, "单子料：净重取 parent 理论净重");
      }
    }
    ByproductNetWeightResolution byproductResolution = ByproductNetWeightResolution.notAttempted();
    if (netWeightG == null
        && grossWeightG != null
        && MakePartProcessTypePolicy.PROCESS_TYPE_RAW.equals(itemProcessType)) {
      byproductResolution = resolveNetWeightFromByproduct(
          parentCode,
          child,
          grossWeightG,
          asOfDate,
          businessUnitType);
      netWeightG = byproductResolution.netWeightG();
      if (netWeightG != null) {
        remark = appendRemark(remark, byproductResolution.remark());
      }
    }
    if (netWeightG == null) {
      String byproductFailure = byproductResolution.failureRemark();
      remark = appendRemark(
          remark,
          requireChildNetWeight
              ? "缺父子材料净重(material_organization_code=" + organizationCode
                  + ", parent_material_no=" + parentCode
                  + ", child_material_no=" + childCode
                  + ", bom_version=" + nullToBlank(trim(child.getBomVersion()))
                  + ", period_month=" + nullToBlank(trim(periodMonth)) + ")"
              : "缺父子材料净重且缺 parent 理论净重(parent_material_no=" + parentCode + ")");
      remark = appendRemark(remark, byproductFailure);
    }
    String status = grossWeightG == null || netWeightG == null ? "MISSING_WEIGHT" : "OK";
    return MakePartWeightResult.of(
        parentCode, childCode, itemProcessType, grossWeightG, netWeightG, status, remark);
  }

  private ByproductNetWeightResolution resolveNetWeightFromByproduct(
      String parentCode,
      BomU9Source child,
      BigDecimal grossWeightG,
      LocalDate asOfDate,
      String businessUnitType) {
    String childCode = trim(child.getChildMaterialNo());
    List<MaterialScrapRef> mappings = scrapMappingService.listMappings(childCode, businessUnitType);
    Set<String> scrapCodes = new LinkedHashSet<>();
    for (MaterialScrapRef mapping : mappings == null ? List.<MaterialScrapRef>of() : mappings) {
      String scrapCode = mapping == null ? null : trim(mapping.getScrapCode());
      if (scrapCode != null) {
        scrapCodes.add(scrapCode);
      }
    }
    if (scrapCodes.isEmpty()) {
      return ByproductNetWeightResolution.failure(
          "副产品反算失败：缺原材料对应废料映射(child_material_no=" + childCode + ")");
    }

    LocalDate effectiveDate = asOfDate == null ? LocalDate.now() : asOfDate;
    var query = Wrappers.lambdaQuery(U9BomByproductMaster.class)
        .eq(U9BomByproductMaster::getPriceOrgCode, trim(child.getPriceOrgCode()))
        .eq(U9BomByproductMaster::getParentMaterialNo, parentCode)
        .in(U9BomByproductMaster::getByproductMaterialNo, scrapCodes)
        .eq(U9BomByproductMaster::getBomPurpose, BOM_PURPOSE_MAIN_MANUFACTURING)
        .eq(U9BomByproductMaster::getStatus, STATUS_APPROVED)
        .and(q -> q.le(U9BomByproductMaster::getEffectiveFrom, effectiveDate)
            .or()
            .isNull(U9BomByproductMaster::getEffectiveFrom))
        .and(q -> q.ge(U9BomByproductMaster::getEffectiveTo, effectiveDate)
            .or()
            .isNull(U9BomByproductMaster::getEffectiveTo))
        .orderByDesc(U9BomByproductMaster::getEffectiveFrom)
        .orderByDesc(U9BomByproductMaster::getId);
    String bomVersion = trim(child.getBomVersion());
    if (bomVersion != null) {
      query.eq(U9BomByproductMaster::getVersionNo, bomVersion);
    }
    List<U9BomByproductMaster> rows = byproductMapper.selectList(query);
    if (rows == null || rows.isEmpty()) {
      return ByproductNetWeightResolution.failure(
          "副产品反算失败：缺有效U9主制造副产品(parent_material_no=" + parentCode
              + ", child_material_no=" + childCode
              + ", scrap_codes=" + String.join(",", scrapCodes)
              + ", bom_version=" + nullToBlank(bomVersion)
              + ", as_of_date=" + effectiveDate + ")");
    }
    if (rows.size() != 1) {
      return ByproductNetWeightResolution.failure(
          "副产品反算失败：有效副产品匹配不唯一(parent_material_no=" + parentCode
              + ", child_material_no=" + childCode + ", count=" + rows.size() + ")");
    }

    U9BomByproductMaster row = rows.get(0);
    BigDecimal byproductWeightG = toGram(row.getOutputQty(), row.getUnit());
    if (byproductWeightG == null) {
      return ByproductNetWeightResolution.failure(
          "副产品反算失败：副产品用量或单位无效(byproduct_material_no="
              + nullToBlank(trim(row.getByproductMaterialNo()))
              + ", output_qty=" + format(row.getOutputQty())
              + ", unit=" + nullToBlank(trim(row.getUnit())) + ")");
    }
    BigDecimal netWeightG = grossWeightG.subtract(byproductWeightG);
    if (netWeightG.signum() < 0) {
      return ByproductNetWeightResolution.failure(
          "副产品反算失败：副产品重量大于毛重(gross_weight_g=" + format(grossWeightG)
              + ", byproduct_weight_g=" + format(byproductWeightG) + ")");
    }
    return ByproductNetWeightResolution.success(
        netWeightG,
        "净重按U9副产品反算(byproduct_material_no="
            + nullToBlank(trim(row.getByproductMaterialNo()))
            + ", gross_weight_g=" + format(grossWeightG)
            + ", output_qty=" + format(row.getOutputQty())
            + ", unit=" + nullToBlank(trim(row.getUnit()))
            + ", byproduct_weight_g=" + format(byproductWeightG)
            + ", net_weight_g=" + format(netWeightG) + ")");
  }

  private BigDecimal toGram(BigDecimal quantity, String unit) {
    if (quantity == null || quantity.signum() < 0 || !StringUtils.hasText(unit)) {
      return null;
    }
    String normalizedUnit = unit.trim().toLowerCase(java.util.Locale.ROOT);
    return switch (normalizedUnit) {
      case "千克", "公斤", "kg", "kgs" -> quantity.multiply(KG_TO_G);
      case "克", "g" -> quantity;
      default -> null;
    };
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
    if (!StringUtils.hasText(second)) {
      return first;
    }
    return first + "；" + second;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  private String format(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private record ByproductNetWeightResolution(
      BigDecimal netWeightG, String remark, String failureRemark) {

    private static ByproductNetWeightResolution notAttempted() {
      return new ByproductNetWeightResolution(null, null, null);
    }

    private static ByproductNetWeightResolution success(BigDecimal netWeightG, String remark) {
      return new ByproductNetWeightResolution(netWeightG, remark, null);
    }

    private static ByproductNetWeightResolution failure(String failureRemark) {
      return new ByproductNetWeightResolution(null, null, failureRemark);
    }
  }
}
