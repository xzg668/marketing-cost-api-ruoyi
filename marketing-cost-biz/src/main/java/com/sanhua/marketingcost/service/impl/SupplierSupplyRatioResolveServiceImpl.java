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
  public SupplierSupplyRatioResolveResult resolve(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      LocalDate pricingDate) {
    String code = normalized(materialCode);
    String spec = normalized(specModel);
    if (!StringUtils.hasText(code) || !StringUtils.hasText(spec)) {
      return SupplierSupplyRatioResolveResult.miss("物料代码或型号为空，未查询供货比例");
    }

    String bu = StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : DEFAULT_BUSINESS_UNIT;
    String name = normalized(materialName);
    if (StringUtils.hasText(name)) {
      SupplierSupplyRatio strict = selectMainSupplier(bu, code, name, spec, pricingDate, false);
      if (strict != null) {
        return hit(strict, "严格匹配 material_code + material_name + spec_model");
      }
      return SupplierSupplyRatioResolveResult.miss(
          "未命中供货比例：严格匹配 material_code + material_name + spec_model 无数据");
    }

    SupplierSupplyRatio fallback = selectMainSupplier(bu, code, null, spec, pricingDate, true);
    if (fallback != null) {
      return hit(fallback, "materialName 为空，降级匹配 material_code + spec_model");
    }
    return SupplierSupplyRatioResolveResult.miss(
        "未命中供货比例：materialName 为空，降级匹配 material_code + spec_model 无数据");
  }

  @Override
  public SupplierSupplyRatioResolveResult resolveByMonth(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      String pricingMonth) {
    return resolve(businessUnitType, materialCode, materialName, specModel, parseMonth(pricingMonth));
  }

  private SupplierSupplyRatio selectMainSupplier(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      LocalDate pricingDate,
      boolean fallbackWithoutName) {
    QueryWrapper<SupplierSupplyRatio> query = new QueryWrapper<SupplierSupplyRatio>()
        .eq("business_unit_type", businessUnitType)
        .eq("material_code", materialCode)
        .eq("spec_model", specModel)
        .eq("deleted", 0);
    if (!fallbackWithoutName) {
      query.eq("material_name", materialName);
    }
    if (pricingDate != null) {
      query.and(w -> w.le("effective_from", pricingDate).or().isNull("effective_from"))
          .and(w -> w.ge("effective_to", pricingDate).or().isNull("effective_to"));
    }
    // 主供选择只负责选供应商，不负责决定固定价/联动价/区间价；价格桶仍由后续取价链路决定。
    query.last("ORDER BY supply_ratio DESC, IFNULL(updated_at, '1970-01-01 00:00:00') DESC, id DESC LIMIT 1");
    return mapper.selectOne(query);
  }

  private SupplierSupplyRatioResolveResult hit(SupplierSupplyRatio row, String matchMode) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setId(row.getId());
    result.setSupplierName(row.getSupplierName());
    result.setSupplierCode(row.getSupplierCode());
    result.setSupplyRatio(row.getSupplyRatio());
    result.setSourceType(row.getSourceType());
    result.setSourceBatchNo(row.getSourceBatchNo());
    result.setTraceMessage("命中供货比例主供应商：supplierName=" + row.getSupplierName()
        + ", supplyRatio=" + row.getSupplyRatio()
        + ", matchMode=" + matchMode);
    return result;
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
