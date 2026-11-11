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
    return resolveWeights(parentMaterialNo, child, itemProcessType, periodMonth, asOfDate,
        businessUnitType, requireChildNetWeight, null);
  }

  @Override
  public MakePartWeightResult resolveWeights(String parentMaterialNo, BomU9Source child,
      String itemProcessType, String periodMonth, LocalDate asOfDate, String businessUnitType,
      boolean requireChildNetWeight, com.sanhua.marketingcost.service.MakePartQuotedWeight quotedWeight) {
    String parentCode = trim(parentMaterialNo);
    String childCode = child == null ? null : trim(child.getChildMaterialNo());
    if (parentCode == null || childCode == null) {
      return MakePartWeightResult.of(
          parentCode, childCode, itemProcessType, null, null, "MISSING_WEIGHT", "parent 或 child 料号为空");
    }
    String organizationCode = requiredMaterialOrganization(child.getPriceOrgCode());
    Map<String, MaterialMasterRaw> rawByCode =
        loadLatestRawRows(List.of(parentCode), organizationCode);
    if (MakePartProcessTypePolicy.PROCESS_TYPE_BLANK.equals(itemProcessType) && quotedWeight != null) {
      // SRM报价中的“净重”在毛坯加工中是投料毛重；该分支不再用副产品覆盖废料重。
      BigDecimal gross = quotedWeight.grams();
      BigDecimal net = theoreticalNetWeightG(rawByCode.get(parentCode));
      String basis = "毛重来源=" + quotedWeight.source() + "；毛重取本次采用报价净重(gross_weight_g="
          + format(gross) + ")；净重取U9本件理论净重(parent_material_no=" + parentCode
          + ", net_weight_g=" + format(net) + ")";
      if (net == null || net.signum() < 0) {
        return MakePartWeightResult.of(parentCode, childCode, itemProcessType, gross, null,
            "MISSING_WEIGHT", appendRemark(basis, "缺有效U9本件理论净重，无法计算废料重"));
      }
      if (gross.compareTo(net) < 0) {
        return MakePartWeightResult.of(parentCode, childCode, itemProcessType, gross, net,
            "MISSING_WEIGHT", appendRemark(basis, "SRM毛重小于U9本件理论净重，无法计算有效废料重"));
      }
      return MakePartWeightResult.of(parentCode, childCode, itemProcessType, gross, net, "OK",
          appendRemark(basis, "废料重按SRM毛重减U9本件理论净重(scrap_weight_g="
              + format(gross.subtract(net)) + ")"));
    }
    MakePartChildNetWeight childNetWeight = requireChildNetWeight
        ? childNetWeightMapper.selectEffective(
            organizationCode,
            parentCode,
            childCode,
            trim(child.getBomVersion()),
            trim(periodMonth))
        : null;
    BigDecimal netWeightG = null;
    BigDecimal grossWeightG = toGram(child.getQtyPerParent(), child.getStockUnit());
    boolean countedBlank = MakePartProcessTypePolicy.PROCESS_TYPE_BLANK.equals(itemProcessType)
        && "只".equals(trim(child.getStockUnit()))
        && child.getQtyPerParent() != null && child.getQtyPerParent().signum() >= 0;
    String remark = "";
    if (grossWeightG != null) {
      remark = "毛重取U9 BOM用量(qty_per_parent=" + format(child.getQtyPerParent())
          + ", stock_unit=" + trim(child.getStockUnit())
          + ", gross_weight_g=" + format(grossWeightG) + ")";
    } else if (countedBlank) {
      remark = "U9 BOM用量为件数，毛重待本件净重加副产品废料重反算";
    } else {
      remark = "缺有效BOM重量(qty_per_parent=" + format(child.getQtyPerParent())
          + ", stock_unit=" + nullToBlank(trim(child.getStockUnit()))
          + ", child_material_no=" + childCode + ")";
      if (child.getQtyPerParent() == null) remark = appendRemark(remark, "缺 qty_per_parent");
    }
    // 先独立取得制造BOM副产品的废料重，不能要求毛重已知才查询副产品。
    ByproductWeightResolution byproduct = resolveByproductWeight(
        parentCode, child, asOfDate, businessUnitType);
    BigDecimal scrapWeightG = byproduct.weightG();
    if (com.sanhua.marketingcost.service.MakePartConsumption.nonWeightRaw(itemProcessType, child.getStockUnit())) {
      var result = MakePartWeightResult.of(parentCode, childCode, itemProcessType, null, null,
          child.getQtyPerParent() != null && child.getQtyPerParent().signum() >= 0 && scrapWeightG != null
              ? "OK" : "MISSING_WEIGHT",
          "原料按BOM用量及原计量单位计价(qty_per_parent=" + format(child.getQtyPerParent())
              + ", stock_unit=" + trim(child.getStockUnit()) + ")；"
              + (scrapWeightG == null ? byproduct.failureRemark() : byproduct.remark()));
      result.setRecoveredScrapWeightG(scrapWeightG);
      return result;
    }
    if (scrapWeightG != null && grossWeightG != null
        && scrapWeightG.compareTo(grossWeightG) > 0) {
      remark = appendRemark(remark, "副产品反算失败：副产品重量大于毛重(gross_weight_g="
          + format(grossWeightG) + ", byproduct_weight_g=" + format(scrapWeightG) + ")");
      scrapWeightG = null;
    } else if (scrapWeightG != null && grossWeightG != null) {
      netWeightG = grossWeightG.subtract(scrapWeightG);
      remark = appendRemark(remark, "净重按U9副产品反算(" + byproduct.remark()
          + ", gross_weight_g=" + format(grossWeightG)
          + ", net_weight_g=" + format(netWeightG) + ")");
    } else {
      remark = appendRemark(remark, byproduct.failureRemark());
    }
    if (netWeightG == null && childNetWeight != null) {
      // 多子料仍按各自配置回退，不能把母件总净重重复分给每种材料。
      netWeightG = childNetWeight.getNetWeightG();
      if (netWeightG != null) {
        remark = appendRemark(remark, "副产品不可用：净重取父子材料净重配置");
      }
    }
    if (netWeightG == null && !requireChildNetWeight) {
      netWeightG = theoreticalNetWeightG(rawByCode.get(parentCode));
      if (netWeightG != null) {
        remark = appendRemark(remark, "单子料：净重取 parent 理论净重");
      }
    }
    if (grossWeightG == null && countedBlank && netWeightG != null && scrapWeightG != null) {
      // 件数不能换算为重量；用本件净重加已匹配副产品废料重恢复投料毛重。
      grossWeightG = netWeightG.add(scrapWeightG);
      remark = appendRemark(remark, "毛重按本件净重加U9副产品废料重反算("
          + byproduct.remark() + ", net_weight_g=" + format(netWeightG)
          + ", gross_weight_g=" + format(grossWeightG) + ")");
    }
    if (grossWeightG == null && countedBlank) {
      remark = appendRemark(remark, "缺本件净重或有效副产品废料重，无法反算毛重");
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
    }
    String status = grossWeightG == null || netWeightG == null ? "MISSING_WEIGHT" : "OK";
    return MakePartWeightResult.of(
        parentCode, childCode, itemProcessType, grossWeightG, netWeightG, status, remark);
  }

  private ByproductWeightResolution resolveByproductWeight(
      String parentCode,
      BomU9Source child,
      LocalDate asOfDate,
      String businessUnitType) {
    String childCode = trim(child.getChildMaterialNo());
    LocalDate effectiveDate = asOfDate == null ? LocalDate.now() : asOfDate;
    var query = Wrappers.lambdaQuery(U9BomByproductMaster.class)
        .eq(U9BomByproductMaster::getPriceOrgCode, trim(child.getPriceOrgCode()))
        .eq(U9BomByproductMaster::getParentMaterialNo, parentCode)
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
      return ByproductWeightResolution.failure(
          "副产品反算失败：缺有效U9主制造副产品(parent_material_no=" + parentCode
              + ", child_material_no=" + childCode
              + ", bom_version=" + nullToBlank(bomVersion)
              + ", as_of_date=" + effectiveDate + ")");
    }
    String selectionRule = "SINGLE";
    if (rows.size() > 1) {
      // 唯一有效副产品直接提供重量；多条时才用当前原料的CMS废料码消歧。
      // 废料价格始终由价格生成链按CMS废料码单独取得。
      List<MaterialScrapRef> mappings = scrapMappingService.listMappings(childCode, businessUnitType);
      Set<String> scrapCodes = new LinkedHashSet<>();
      for (MaterialScrapRef mapping : mappings == null ? List.<MaterialScrapRef>of() : mappings) {
        String scrapCode = mapping == null ? null : trim(mapping.getScrapCode());
        if (scrapCode != null) {
          scrapCodes.add(scrapCode);
        }
      }
      if (scrapCodes.isEmpty()) {
        return ByproductWeightResolution.failure(
            "副产品反算失败：多个有效副产品且缺原材料对应废料映射(child_material_no=" + childCode + ")");
      }
      rows = rows.stream()
          .filter(row -> scrapCodes.contains(trim(row.getByproductMaterialNo())))
          .toList();
      if (rows.size() != 1) {
        return ByproductWeightResolution.failure(
            "副产品反算失败：多个有效副产品按CMS废料码匹配不唯一(parent_material_no=" + parentCode
                + ", child_material_no=" + childCode + ", scrap_codes=" + String.join(",", scrapCodes)
                + ", count=" + rows.size() + ")");
      }
      selectionRule = "CMS_MATCH";
    }

    U9BomByproductMaster row = rows.get(0);
    BigDecimal byproductWeightG = toGram(row.getOutputQty(), row.getUnit());
    if (byproductWeightG == null) {
      return ByproductWeightResolution.failure(
          "副产品反算失败：副产品用量或单位无效(byproduct_material_no="
              + nullToBlank(trim(row.getByproductMaterialNo()))
              + ", output_qty=" + format(row.getOutputQty())
              + ", unit=" + nullToBlank(trim(row.getUnit())) + ")");
    }
    return ByproductWeightResolution.success(
        byproductWeightG,
        "byproduct_material_no="
            + nullToBlank(trim(row.getByproductMaterialNo()))
            + ", output_qty=" + format(row.getOutputQty())
            + ", unit=" + nullToBlank(trim(row.getUnit()))
            + ", byproduct_weight_g=" + format(byproductWeightG)
            + ", byproduct_selection=" + selectionRule);
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

  private record ByproductWeightResolution(
      BigDecimal weightG, String remark, String failureRemark) {

    private static ByproductWeightResolution success(BigDecimal weightG, String remark) {
      return new ByproductWeightResolution(weightG, remark, null);
    }

    private static ByproductWeightResolution failure(String failureRemark) {
      return new ByproductWeightResolution(null, null, failureRemark);
    }
  }
}
