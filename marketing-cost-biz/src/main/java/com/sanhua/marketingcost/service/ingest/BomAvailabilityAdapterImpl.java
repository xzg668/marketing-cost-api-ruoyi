package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BomAvailabilityAdapterImpl implements BomAvailabilityAdapter {
  private final BomCostingRowMapper bomCostingRowMapper;
  private final BomRawHierarchyMapper bomRawHierarchyMapper;

  public BomAvailabilityAdapterImpl(
      BomCostingRowMapper bomCostingRowMapper, BomRawHierarchyMapper bomRawHierarchyMapper) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
  }

  @Override
  public BomAvailability findAvailableBom(String oaNo, String productCode, String periodMonth) {
    return findAvailableBom(oaNo, productCode, periodMonth, null);
  }

  @Override
  public BomAvailability findAvailableBom(
      String oaNo, String productCode, String periodMonth, String priceOrgCode) {
    if (!StringUtils.hasText(productCode)) {
      return BomAvailability.unavailable("产品料号为空，无法自动匹配 BOM");
    }
    String org = requiredPriceOrgCode(priceOrgCode);

    BomRawHierarchy raw = selectRawHierarchy(productCode.trim(), org);
    if (raw != null) {
      BomAvailability availability = new BomAvailability();
      availability.setAvailable(true);
      availability.setSource(defaultSource(raw.getSourceType()));
      availability.setBomPurpose(raw.getBomPurpose());
      availability.setBomVersion(raw.getBomVersion());
      availability.setEffectiveFrom(raw.getEffectiveFrom());
      availability.setEffectiveTo(raw.getEffectiveTo());
      availability.setSyncBatchId(raw.getBuildBatchId());
      return availability;
    }

    BomCostingRow snapshot =
        bomCostingRowMapper.selectAvailabilitySnapshot(
            trimToNull(oaNo), productCode.trim(), trimToNull(periodMonth), org);
    if (snapshot != null) {
      BomAvailability availability = new BomAvailability();
      availability.setAvailable(true);
      availability.setSource("COSTING_SNAPSHOT");
      availability.setBomPurpose(snapshot.getBomPurpose());
      availability.setBomVersion(snapshot.getBomVersion());
      availability.setEffectiveFrom(snapshot.getEffectiveFrom());
      availability.setEffectiveTo(snapshot.getEffectiveTo());
      availability.setSyncBatchId(snapshot.getBuildBatchId());
      return availability;
    }

    return BomAvailability.unavailable("未匹配到本地正式 BOM 或有效补录 BOM");
  }

  private BomRawHierarchy selectRawHierarchy(String productCode, String priceOrgCode) {
    return bomRawHierarchyMapper.selectOne(
        Wrappers.lambdaQuery(BomRawHierarchy.class)
            .eq(BomRawHierarchy::getPriceOrgCode, priceOrgCode)
            .eq(BomRawHierarchy::getTopProductCode, productCode)
            .eq(BomRawHierarchy::getLevel, 0)
            .orderByDesc(BomRawHierarchy::getBuiltAt)
            .orderByDesc(BomRawHierarchy::getId)
            .last("LIMIT 1"));
  }

  private String requiredPriceOrgCode(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("BOM 可用性检查缺少 priceOrgCode");
    }
    return MaterialOrganization.fromPriceOrgCode(value).getPriceOrgCode();
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String defaultSource(String sourceType) {
    return StringUtils.hasText(sourceType) ? sourceType.trim() : "U9";
  }
}
