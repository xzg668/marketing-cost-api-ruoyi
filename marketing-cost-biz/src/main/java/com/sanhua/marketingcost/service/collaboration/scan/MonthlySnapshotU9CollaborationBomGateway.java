package com.sanhua.marketingcost.service.collaboration.scan;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 同料号、同组织、同月只读取一次 U9，并冻结 AVAILABLE 或明确 NOT_FOUND。 */
@Component
public class MonthlySnapshotU9CollaborationBomGateway
    implements QuoteCollaborationCurrentU9BomGateway {

  static final String STATUS_SYNCING = "SYNCING";
  static final String STATUS_SUCCESS = "SUCCESS";
  static final String STATUS_NOT_FOUND = "NOT_FOUND";

  private final QuoteBomMonthlySnapshotMapper mapper;
  private final QuoteCollaborationLiveU9BomGateway liveGateway;
  private final Clock clock;

  @Autowired
  public MonthlySnapshotU9CollaborationBomGateway(
      QuoteBomMonthlySnapshotMapper mapper,
      QuoteCollaborationLiveU9BomGateway liveGateway) {
    this(mapper, liveGateway, Clock.system(CostPricingPeriodUtils.BUSINESS_ZONE));
  }

  MonthlySnapshotU9CollaborationBomGateway(
      QuoteBomMonthlySnapshotMapper mapper,
      QuoteCollaborationLiveU9BomGateway liveGateway,
      Clock clock) {
    this.mapper = mapper;
    this.liveGateway = liveGateway;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public CurrentU9BomResult read(QuoteCollaborationScanContext context) {
    U9MonthlySnapshotIdentity identity = U9MonthlySnapshotIdentity.from(context);
    QuoteBomMonthlySnapshot existing = mapper.selectU9MonthlyByIdentity(identity.identityKey());
    if (existing != null) return restored(existing);

    LocalDateTime now = LocalDateTime.now(clock);
    QuoteBomMonthlySnapshot claim = claim(identity, context, now);
    if (mapper.insertU9MonthlyClaim(claim) == 0) {
      QuoteBomMonthlySnapshot winner =
          mapper.selectU9MonthlyByIdentityForUpdate(identity.identityKey());
      return winner == null
          ? CurrentU9BomResult.error("U9月度快照并发创建失败，请重试")
          : restored(winner);
    }

    CurrentU9BomResult live = liveGateway.readLive(context);
    if (live == null) live = CurrentU9BomResult.error("U9正式查询没有返回结果");
    String resolvedStatus = switch (live.status()) {
      case AVAILABLE -> STATUS_SUCCESS;
      case NOT_FOUND -> STATUS_NOT_FOUND;
      default -> null;
    };
    if (resolvedStatus == null) {
      mapper.deleteU9MonthlyClaim(claim.getId());
      return live;
    }
    LocalDateTime completedAt = LocalDateTime.now(clock);
    if (mapper.completeU9MonthlyClaim(
            claim.getId(), resolvedStatus, completedAt, live) != 1) {
      throw new IllegalStateException("U9月度快照完成状态写入失败");
    }
    return live.withMonthlySnapshot(claim.getId(), true);
  }

  private QuoteBomMonthlySnapshot claim(
      U9MonthlySnapshotIdentity identity,
      QuoteCollaborationScanContext context,
      LocalDateTime now) {
    QuoteBomMonthlySnapshot row = new QuoteBomMonthlySnapshot();
    row.setProductCode(identity.productCode());
    row.setPriceOrgCode(identity.priceOrgCode());
    row.setBusinessUnitType(identity.businessUnitType());
    row.setMaterialOrganizationCode(identity.materialOrganizationCode());
    row.setSnapshotIdentityKey(identity.identityKey());
    row.setCustomerCode("");
    row.setPackageMethod("");
    row.setCostPeriodMonth(identity.accountingMonth());
    row.setBomSource("U9");
    row.setBomPurpose(identity.bomPurpose());
    row.setSyncType("AUTO");
    row.setSyncStatus(STATUS_SYNCING);
    row.setSyncBy("SYSTEM");
    row.setSourceOaNo(trimToNull(context.oaNo()));
    row.setSourceOaFormItemId(context.oaFormItemId());
    row.setActiveFlag(1);
    row.setLineCount(0);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    return row;
  }

  private CurrentU9BomResult restored(QuoteBomMonthlySnapshot row) {
    if (STATUS_SUCCESS.equals(row.getSyncStatus())) {
      return CurrentU9BomResult.available(
              first(row.getBomSource(), "U9"), row.getBomVersion(), row.getBomBatchId(),
              row.getLineCount() == null ? 0 : row.getLineCount(),
              row.getStructureFingerprint())
          .withMonthlySnapshot(row.getId(), false);
    }
    if (STATUS_NOT_FOUND.equals(row.getSyncStatus())) {
      return CurrentU9BomResult.notFound(
              first(row.getErrorMessage(), "本月首次查询确认U9无有效BOM"))
          .withMonthlySnapshot(row.getId(), false);
    }
    return CurrentU9BomResult.error("U9月度快照状态异常：" + row.getSyncStatus());
  }

  private String first(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
