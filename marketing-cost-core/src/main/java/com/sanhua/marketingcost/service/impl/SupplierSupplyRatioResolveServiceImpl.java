package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioResolveServiceImpl implements SupplierSupplyRatioResolveService {

  private static final String DEFAULT_BUSINESS_UNIT = "COMMERCIAL";

  private final SupplierSupplyRatioMapper mapper;

  public SupplierSupplyRatioResolveServiceImpl(SupplierSupplyRatioMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public java.util.Map<String, SupplierSupplyRatioResolveResult> resolveAll(
      String businessUnitType, java.util.Set<String> materialCodes, LocalDate pricingDate) {
    var result = new java.util.LinkedHashMap<String, SupplierSupplyRatioResolveResult>();
    if (materialCodes == null || materialCodes.isEmpty()) return result;
    var codes =
        materialCodes.stream()
            .map(this::normalized)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (codes.isEmpty()) return result;
    String bu =
        StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : DEFAULT_BUSINESS_UNIT;
    var query =
        new QueryWrapper<SupplierSupplyRatio>()
            .eq("business_unit_type", bu)
            .in("material_code", codes)
            .eq("is_active", 1)
            .eq("deleted", 0)
            .isNotNull("supply_ratio");
    if (pricingDate != null)
      query
          .and(w -> w.le("effective_from", pricingDate).or().isNull("effective_from"))
          .and(w -> w.ge("effective_to", pricingDate).or().isNull("effective_to"));
    query.last(
        "ORDER BY material_code, supply_ratio DESC, IFNULL(updated_at, '1970-01-01 00:00:00') DESC,"
            + " id DESC");
    var captured=new java.util.LinkedHashMap<String,java.util.List<SupplierSupplyRatio>>();
    for(var row:mapper.selectList(query))captured.computeIfAbsent(normalized(row.getMaterialCode()),ignored->new java.util.ArrayList<>()).add(row);
    result.putAll(com.sanhua.marketingcost.service.pricing.SupplierPrimaryRatioPolicy.resolve(
        bu,captured.keySet(),pricingDate,captured));
    for(String code:codes)result.putIfAbsent(code,SupplierSupplyRatioResolveResult.miss("未命中供货比例：material_code 无数据"));
    return result;
  }

  @Override
  public SupplierSupplyRatioResolveResult resolve(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      LocalDate pricingDate) {
    String code = normalized(materialCode);
    if (!StringUtils.hasText(code)) {
      return SupplierSupplyRatioResolveResult.miss("物料代码为空，未查询供货比例");
    }

    String bu =
        StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : DEFAULT_BUSINESS_UNIT;
    SupplierSupplyRatio mainSupplier = selectMainSupplier(bu, code, pricingDate);
    if (mainSupplier != null) {
      return hit(mainSupplier, "匹配 material_code，取供货比例最大供应商");
    }
    return SupplierSupplyRatioResolveResult.miss("未命中供货比例：material_code 无数据");
  }

  @Override
  public SupplierSupplyRatioResolveResult resolveByMonth(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      String pricingMonth) {
    return resolve(
        businessUnitType, materialCode, materialName, specModel, parseMonth(pricingMonth));
  }

  private SupplierSupplyRatio selectMainSupplier(
      String businessUnitType, String materialCode, LocalDate pricingDate) {
    QueryWrapper<SupplierSupplyRatio> query =
        new QueryWrapper<SupplierSupplyRatio>()
            .eq("business_unit_type", businessUnitType)
            .eq("material_code", materialCode)
            .eq("is_active", 1)
            .isNotNull("supply_ratio")
            .eq("deleted", 0);
    if (pricingDate != null) {
      query
          .and(w -> w.le("effective_from", pricingDate).or().isNull("effective_from"))
          .and(w -> w.ge("effective_to", pricingDate).or().isNull("effective_to"));
    }
    // 主供选择只负责选供应商，不负责决定固定价/联动价/区间价；价格桶仍由后续取价链路决定。
    query.last(
        "ORDER BY supply_ratio DESC, IFNULL(updated_at, '1970-01-01 00:00:00') DESC, id DESC LIMIT"
            + " 1");
    return mapper.selectOne(query);
  }

  private SupplierSupplyRatioResolveResult hit(SupplierSupplyRatio row, String matchMode) {
    return com.sanhua.marketingcost.service.pricing.SupplierPrimaryRatioPolicy.hit(row, matchMode);
  }

  private LocalDate parseMonth(String pricingMonth) {
    if (!StringUtils.hasText(pricingMonth)) {
      return null;
    }
    try {
      return YearMonth.parse(pricingMonth.trim()).atDay(1);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private String normalized(String value) {
    return SupplierSupplyRatioNormalizeUtils.normalizeToNull(value);
  }
}
