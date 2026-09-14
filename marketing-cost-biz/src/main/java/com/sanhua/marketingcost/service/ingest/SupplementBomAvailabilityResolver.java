package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * U9 无 BOM 时只从共享的已生效补录版本读取候选，不依赖任何任务、报价关联或审核旧表。
 */
@Component
public class SupplementBomAvailabilityResolver {
  private static final String FULL_BOM_SCOPE = "NON_BARE_FULL_BOM";

  private final QuoteBomSupplementVersionMapper versionMapper;

  public SupplementBomAvailabilityResolver(QuoteBomSupplementVersionMapper versionMapper) {
    this.versionMapper = versionMapper;
  }

  public BomAvailability resolve(
      Long oaFormItemId,
      String businessUnitType,
      String costPeriodMonth,
      BomAvailability u9Availability) {
    if (oaFormItemId == null || !StringUtils.hasText(costPeriodMonth)) {
      return null;
    }
    if (u9Availability != null && u9Availability.isAvailable()) {
      return null;
    }
    List<QuoteBomSupplementVersion> versions = versionMapper.selectList(
        Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
            .eq(QuoteBomSupplementVersion::getOaFormItemId, oaFormItemId)
            .eq(QuoteBomSupplementVersion::getPeriodMonth, costPeriodMonth)
            .eq(QuoteBomSupplementVersion::getSupplementScope, FULL_BOM_SCOPE)
            .eq(QuoteBomSupplementVersion::getVersionStatus, "APPROVED")
            .eq(QuoteBomSupplementVersion::getActiveFlag, 1)
            .isNotNull(QuoteBomSupplementVersion::getCompositionFingerprint)
            .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
            .orderByDesc(QuoteBomSupplementVersion::getId)
            .last("LIMIT 1"));
    if (versions == null || versions.isEmpty()) {
      return null;
    }
    QuoteBomSupplementVersion version = versions.getFirst();
    YearMonth period = YearMonth.parse(costPeriodMonth);
    BomAvailability result = new BomAvailability();
    result.setAvailable(true);
    result.setSource("ELECTRONIC_DRAWING_BOM");
    result.setBomPurpose("主制造");
    result.setBomVersion("ED-" + version.getId());
    result.setSyncBatchId("SUPPLEMENT_VERSION:" + version.getId());
    result.setEffectiveFrom(period.atDay(1));
    result.setEffectiveTo(period.atEndOfMonth());
    return result;
  }
}
