package com.sanhua.marketingcost.service.quotebom;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 已有可用 U9 BOM 按月复用；原来没有 BOM 时仍核实当前来源，避免挡住后来补齐的公共资料。 */
@Component
public class MonthlySnapshotU9BomGateway implements CurrentU9BomGateway {

  static final String STATUS_SYNCING = "SYNCING";
  static final String STATUS_SUCCESS = "SUCCESS";
  static final String STATUS_NOT_FOUND = "NOT_FOUND";

  private final QuoteBomMonthlySnapshotMapper mapper;
  private final LiveU9BomGateway liveGateway;
  private final MonthlyBomSnapshotDetailService details;
  private final Clock clock;

  @Autowired
  public MonthlySnapshotU9BomGateway(
      QuoteBomMonthlySnapshotMapper mapper,
      LiveU9BomGateway liveGateway,
      MonthlyBomSnapshotDetailService details) {
    this(mapper, liveGateway, details, Clock.system(CostPricingPeriodUtils.BUSINESS_ZONE));
  }

  MonthlySnapshotU9BomGateway(
      QuoteBomMonthlySnapshotMapper mapper,
      LiveU9BomGateway liveGateway,
      MonthlyBomSnapshotDetailService details,
      Clock clock) {
    this.mapper = mapper;
    this.liveGateway = liveGateway;
    this.details = details;
    this.clock = clock;
  }

  @Override
  // 月度快照必须与发起核算共用事务。MySQL REPEATABLE READ 下，独立事务提交的新快照
  // 对已建立读视图的外层事务不可见，会让首次核算在后续 BOM 状态和有效树查询中误报缺失。
  @Transactional(rollbackFor = Exception.class)
  public CurrentU9BomResult read(QuoteBomReadContext context) {
    U9MonthlySnapshotIdentity identity = U9MonthlySnapshotIdentity.from(context);
    QuoteBomMonthlySnapshot existing = mapper.selectU9MonthlyByIdentity(identity.identityKey());
    if (existing != null) {
      if (STATUS_SUCCESS.equals(existing.getSyncStatus())) {
        return reuseAvailable(context, identity, existing);
      }
      if (!STATUS_NOT_FOUND.equals(existing.getSyncStatus())) return restored(existing);
      return recheckMissing(context, identity, existing);
    }
    return createSnapshot(context, identity, null);
  }

  private CurrentU9BomResult reuseAvailable(QuoteBomReadContext context,
      U9MonthlySnapshotIdentity identity, QuoteBomMonthlySnapshot existing) {
    if (!details.load(existing.getId()).isEmpty()) return restored(existing);
    // Pre-migration headers stored only a batch ID. Backfill while that exact batch still exists.
    // If EasyData already removed it, preserve the old header for history and start a new card
    // from the current BOM; the lost historical structure cannot be reconstructed.
    QuoteBomMonthlySnapshot locked = mapper.selectU9MonthlyByIdentityForUpdate(identity.identityKey());
    if (locked == null) return CurrentU9BomResult.error("U9月度卡片并发更新，请重试");
    if (!STATUS_SUCCESS.equals(locked.getSyncStatus())) return restored(locked);
    if (!details.load(locked.getId()).isEmpty()) return restored(locked);
    try {
      details.captureU9(locked.getId(), context, locked.getBomBatchId());
      return restored(locked);
    } catch (MonthlyBomSnapshotDetailService.SourceUnavailableException missing) {
      CurrentU9BomResult live = liveGateway.readLive(context);
      if (live == null || live.status() != CurrentU9BomResult.Status.AVAILABLE) {
        return live == null ? CurrentU9BomResult.error("U9正式查询没有返回结果") : live;
      }
      if (mapper.retireUnavailableU9MonthlySnapshot(
          locked.getId(), identity.identityKey(), LocalDateTime.now(clock)) != 1) {
        throw new IllegalStateException("旧U9月度卡片释放失败，请重试");
      }
      return createSnapshot(context, identity, live);
    }
  }

  private CurrentU9BomResult recheckMissing(QuoteBomReadContext context,
      U9MonthlySnapshotIdentity identity, QuoteBomMonthlySnapshot previous) {
    CurrentU9BomResult live = liveGateway.readLive(context);
    if (live == null) return CurrentU9BomResult.error("U9正式查询没有返回结果");
    if (live.status() == CurrentU9BomResult.Status.NOT_FOUND) return restored(previous);
    if (live.status() != CurrentU9BomResult.Status.AVAILABLE) return live;
    // 当前读串行切换月度索引；旧的“无 BOM”证据保留，新可用结构另存快照。
    var current = mapper.selectU9MonthlyByIdentityForUpdate(identity.identityKey());
    if (current == null) throw new IllegalStateException("U9月度来源切换期间记录丢失，请重试");
    if (!STATUS_NOT_FOUND.equals(current.getSyncStatus())) return restored(current);
    if (mapper.retireMissingU9MonthlySnapshot(current.getId(), identity.identityKey(), LocalDateTime.now(clock)) != 1) {
      throw new IllegalStateException("U9原缺失记录已变化，请重试");
    }
    return createSnapshot(context, identity, live);
  }

  private CurrentU9BomResult createSnapshot(QuoteBomReadContext context,
      U9MonthlySnapshotIdentity identity, CurrentU9BomResult verified) {
    LocalDateTime now = LocalDateTime.now(clock);
    QuoteBomMonthlySnapshot claim = claim(identity, context, now);
    if (mapper.insertU9MonthlyClaim(claim) == 0) {
      QuoteBomMonthlySnapshot winner =
          mapper.selectU9MonthlyByIdentityForUpdate(identity.identityKey());
      return winner == null
          ? CurrentU9BomResult.error("U9月度快照并发创建失败，请重试")
          : restored(winner);
    }

    CurrentU9BomResult live = verified == null ? liveGateway.readLive(context) : verified;
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
    if (live.status() == CurrentU9BomResult.Status.AVAILABLE) {
      try {
        details.captureU9(claim.getId(), context, live.syncBatchId());
      } catch (MonthlyBomSnapshotDetailService.SourceUnavailableException missing) {
        mapper.deleteU9MonthlyClaim(claim.getId());
        return CurrentU9BomResult.error(missing.getMessage());
      }
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
      QuoteBomReadContext context,
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
