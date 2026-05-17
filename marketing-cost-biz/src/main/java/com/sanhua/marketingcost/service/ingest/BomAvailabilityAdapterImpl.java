package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
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
  public BomAvailability findAvailableBom(String oaNo, String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return BomAvailability.unavailable("产品料号为空，无法自动匹配 BOM");
    }

    BomCostingRow snapshot =
        bomCostingRowMapper.selectOne(
            Wrappers.lambdaQuery(BomCostingRow.class)
                .eq(BomCostingRow::getOaNo, oaNo)
                .eq(BomCostingRow::getTopProductCode, productCode.trim())
                .last("LIMIT 1"));
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

    BomRawHierarchy raw =
        bomRawHierarchyMapper.selectOne(
            Wrappers.lambdaQuery(BomRawHierarchy.class)
                .eq(BomRawHierarchy::getTopProductCode, productCode.trim())
                .eq(BomRawHierarchy::getLevel, 0)
                .last("LIMIT 1"));
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

    return BomAvailability.unavailable("未匹配到本地正式 BOM 或有效补录 BOM");
  }

  private String defaultSource(String sourceType) {
    return StringUtils.hasText(sourceType) ? sourceType.trim() : "U9";
  }
}
