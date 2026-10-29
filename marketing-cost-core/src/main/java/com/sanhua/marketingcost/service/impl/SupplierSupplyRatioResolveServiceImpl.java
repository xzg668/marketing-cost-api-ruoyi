package com.sanhua.marketingcost.service.impl;

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
    var captured = new java.util.LinkedHashMap<String,java.util.List<SupplierSupplyRatio>>();
    for (int start = 0; start < codes.size(); start += 256) {
      for (var row : mapper.currentRelations(
          bu, pricingDate, codes.subList(start, Math.min(start + 256, codes.size()))))
        captured.computeIfAbsent(row.getRequestedMaterialCode(), ignored -> new java.util.ArrayList<>()).add(row);
    }
    result.putAll(com.sanhua.marketingcost.service.pricing.SupplierPrimaryRatioPolicy.resolve(
        bu,new java.util.LinkedHashSet<>(codes),pricingDate,captured));
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

    return resolveAll(businessUnitType, java.util.Set.of(code), pricingDate).get(code);
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
