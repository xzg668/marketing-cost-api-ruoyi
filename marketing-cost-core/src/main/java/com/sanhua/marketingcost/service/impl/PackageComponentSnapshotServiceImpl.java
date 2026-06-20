package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PackageSnapshotDetailResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PackageComponentSnapshotServiceImpl implements PackageComponentSnapshotService {

  private static final String DEFAULT_BOM_SOURCE_TYPE = "U9";
  private static final String DEFAULT_BOM_PURPOSE = "主制造";
  private static final String SNAPSHOT_STATUS_NORMAL = "NORMAL";
  private static final String SNAPSHOT_STATUS_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  private static final String SNAPSHOT_SOURCE_BOM = "BOM";
  private static final String GAP_TYPE_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  private static final String GAP_STATUS_PENDING_MAINTAIN = "PENDING_MAINTAIN";
  private static final String OA_PUSH_STATUS_NOT_PUSHED = "NOT_PUSHED";

  private final PackageComponentSnapshotMapper snapshotMapper;
  private final PackageComponentSnapshotDetailMapper snapshotDetailMapper;
  private final PackageComponentGapItemMapper gapItemMapper;
  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final BomU9SourceMapper bomU9SourceMapper;

  public PackageComponentSnapshotServiceImpl(
      PackageComponentSnapshotMapper snapshotMapper,
      PackageComponentSnapshotDetailMapper snapshotDetailMapper,
      PackageComponentGapItemMapper gapItemMapper,
      BomRawHierarchyMapper bomRawHierarchyMapper,
      BomU9SourceMapper bomU9SourceMapper) {
    this.snapshotMapper = snapshotMapper;
    this.snapshotDetailMapper = snapshotDetailMapper;
    this.gapItemMapper = gapItemMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.bomU9SourceMapper = bomU9SourceMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PackageSnapshotResult ensureSnapshot(PackageSnapshotRequest request) {
    NormalizedRequest req = normalize(request);
    PackageComponentSnapshot existing =
        selectByPackagePeriodAndTop(req.packageMaterialCode, req.periodMonth, req.topProductCode);
    if (existing != null) {
      List<PackageComponentSnapshotDetail> existingDetails = selectDetails(existing.getId());
      if (SNAPSHOT_STATUS_NORMAL.equals(existing.getStatus()) || !existingDetails.isEmpty()) {
        return PackageSnapshotResult.of(existing, existingDetails, false);
      }
    }
    if (!StringUtils.hasText(req.topProductCode)) {
      throw new IllegalArgumentException("topProductCode 必填：包装组件取结构必须指定来源顶层产品");
    }

    BomRawHierarchy parent = selectSourceParent(req);
    if (parent == null) {
      return createMissingSnapshot(req, "未在 lp_bom_raw_hierarchy 找到包装父料号结构");
    }

    List<BomRawHierarchy> children = selectDirectChildren(req, parent);
    if (children.isEmpty()) {
      return createMissingSnapshot(req, "包装父料号在 lp_bom_raw_hierarchy 中没有直接子件");
    }

    PackageComponentSnapshot snapshot = buildNormalSnapshot(req, parent);
    if (existing != null) {
      snapshot.setId(existing.getId());
      snapshotDetailMapper.delete(
          Wrappers.<PackageComponentSnapshotDetail>lambdaQuery()
              .eq(PackageComponentSnapshotDetail::getSnapshotId, existing.getId()));
      snapshotMapper.updateById(snapshot);
    } else {
      try {
        snapshotMapper.insert(snapshot);
      } catch (DuplicateKeyException ex) {
        PackageComponentSnapshot concurrent =
            selectByPackagePeriodAndTop(req.packageMaterialCode, req.periodMonth, req.topProductCode);
        if (concurrent != null) {
          return PackageSnapshotResult.of(concurrent, selectDetails(concurrent.getId()), false);
        }
        throw ex;
      }
    }

    List<PackageComponentSnapshotDetail> details = buildDetails(snapshot, children);
    for (PackageComponentSnapshotDetail detail : details) {
      snapshotDetailMapper.insert(detail);
    }
    return PackageSnapshotResult.of(snapshot, details, true);
  }

  @Override
  public PackageSnapshotDetailResult getSnapshotDetail(Long snapshotId) {
    PackageSnapshotDetailResult result = new PackageSnapshotDetailResult();
    if (snapshotId == null) {
      return result;
    }
    PackageComponentSnapshot snapshot = snapshotMapper.selectById(snapshotId);
    result.setSnapshot(snapshot);
    result.setDetails(snapshot == null ? List.of() : selectDetails(snapshotId));
    return result;
  }

  private PackageSnapshotResult createMissingSnapshot(NormalizedRequest req, String reason) {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setPackageMaterialCode(req.packageMaterialCode);
    snapshot.setPeriodMonth(req.periodMonth);
    snapshot.setStatus(SNAPSHOT_STATUS_MISSING_STRUCTURE);
    snapshot.setSourceType(SNAPSHOT_SOURCE_BOM);
    snapshot.setSourceQuoteNo(req.quoteNo);
    snapshot.setSourceOaNo(req.oaNo);
    snapshot.setSourceTopProductCode(req.topProductCode);
    snapshot.setSourceBomPurpose(req.bomPurpose);
    snapshot.setSourceBomSourceType(req.sourceType);
    snapshot.setSourceAsOfDate(req.asOfDate);
    snapshot.setMissingReason(reason);
    snapshot.setLockedAt(LocalDateTime.now());
    try {
      snapshotMapper.insert(snapshot);
    } catch (DuplicateKeyException ex) {
      PackageComponentSnapshot concurrent =
          selectByPackagePeriodAndTop(req.packageMaterialCode, req.periodMonth, req.topProductCode);
      if (concurrent != null) {
        return PackageSnapshotResult.of(concurrent, selectDetails(concurrent.getId()), false);
      }
      throw ex;
    }

    upsertGap(buildMissingStructureGap(req, snapshot, reason));

    PackageSnapshotResult result = PackageSnapshotResult.of(snapshot, List.of(), true);
    result.getWarnings().add(reason);
    return result;
  }

  private PackageComponentSnapshot buildNormalSnapshot(NormalizedRequest req, BomRawHierarchy parent) {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setPackageMaterialCode(req.packageMaterialCode);
    snapshot.setPackageMaterialName(parent.getMaterialName());
    snapshot.setPeriodMonth(req.periodMonth);
    snapshot.setStatus(SNAPSHOT_STATUS_NORMAL);
    snapshot.setSourceType(SNAPSHOT_SOURCE_BOM);
    snapshot.setSourceQuoteNo(req.quoteNo);
    snapshot.setSourceOaNo(req.oaNo);
    snapshot.setSourceTopProductCode(parent.getTopProductCode());
    snapshot.setSourceBomPurpose(parent.getBomPurpose());
    snapshot.setSourceBomSourceType(parent.getSourceType());
    snapshot.setSourceAsOfDate(req.asOfDate);
    snapshot.setSourceRawHierarchyId(parent.getId());
    snapshot.setSourcePath(parent.getPath());
    snapshot.setPackageQtyPerParent(parent.getQtyPerParent());
    snapshot.setPackageQtyPerTop(parent.getQtyPerTop());
    snapshot.setPackageParentBaseQty(resolveParentBaseQty(parent));
    snapshot.setMissingReason("");
    snapshot.setLockedAt(LocalDateTime.now());
    return snapshot;
  }

  private PackageComponentGapItem buildMissingStructureGap(
      NormalizedRequest req, PackageComponentSnapshot snapshot, String reason) {
    PackageComponentGapItem gap = new PackageComponentGapItem();
    gap.setPeriodMonth(req.periodMonth);
    gap.setQuoteNo(req.quoteNo);
    gap.setOaNo(req.oaNo);
    gap.setTopProductCode(req.topProductCode);
    gap.setPackageMaterialCode(req.packageMaterialCode);
    gap.setPackageMaterialName(snapshot.getPackageMaterialName());
    gap.setGapType(GAP_TYPE_MISSING_STRUCTURE);
    gap.setMissingMaterialCode(req.packageMaterialCode);
    gap.setMissingReason(reason);
    gap.setStatus(GAP_STATUS_PENDING_MAINTAIN);
    gap.setOaPushStatus(OA_PUSH_STATUS_NOT_PUSHED);
    return gap;
  }

  private void upsertGap(PackageComponentGapItem gap) {
    Long existingId = findExistingGapId(gap);
    if (existingId == null) {
      gapItemMapper.insert(gap);
      return;
    }
    gap.setId(existingId);
    gapItemMapper.updateById(gap);
  }

  private Long findExistingGapId(PackageComponentGapItem gap) {
    if (gap == null
        || !StringUtils.hasText(gap.getPeriodMonth())
        || !StringUtils.hasText(gap.getPackageMaterialCode())
        || !StringUtils.hasText(gap.getGapType())) {
      return null;
    }
    var query = Wrappers.lambdaQuery(PackageComponentGapItem.class)
        .eq(PackageComponentGapItem::getPeriodMonth, trimToNull(gap.getPeriodMonth()))
        .eq(PackageComponentGapItem::getPackageMaterialCode, trimToNull(gap.getPackageMaterialCode()))
        .eq(PackageComponentGapItem::getGapType, trimToNull(gap.getGapType()))
        .orderByDesc(PackageComponentGapItem::getId)
        .last("LIMIT 1");
    eqNullable(query, PackageComponentGapItem::getQuoteNo, gap.getQuoteNo());
    eqNullable(query, PackageComponentGapItem::getOaNo, gap.getOaNo());
    eqNullable(query, PackageComponentGapItem::getTopProductCode, gap.getTopProductCode());
    eqNullable(query, PackageComponentGapItem::getLineNo, gap.getLineNo());
    eqNullable(query, PackageComponentGapItem::getChildMaterialCode, gap.getChildMaterialCode());
    eqNullable(query, PackageComponentGapItem::getMissingMaterialCode, gap.getMissingMaterialCode());
    List<PackageComponentGapItem> existingRows = gapItemMapper.selectList(query);
    if (existingRows == null || existingRows.isEmpty()) {
      return null;
    }
    return existingRows.get(0).getId();
  }

  private <T> void eqNullable(
      com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PackageComponentGapItem> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<PackageComponentGapItem, T> column,
      T value) {
    if (value instanceof String text) {
      String trimmed = trimToNull(text);
      if (trimmed == null) {
        query.isNull(column);
      } else {
        query.eq(column, trimmed);
      }
      return;
    }
    if (value == null) {
      query.isNull(column);
    } else {
      query.eq(column, value);
    }
  }

  private List<PackageComponentSnapshotDetail> buildDetails(
      PackageComponentSnapshot snapshot, List<BomRawHierarchy> children) {
    List<PackageComponentSnapshotDetail> details = new ArrayList<>(children.size());
    int lineNo = 1;
    for (BomRawHierarchy child : children) {
      PackageComponentSnapshotDetail detail = new PackageComponentSnapshotDetail();
      detail.setSnapshotId(snapshot.getId());
      detail.setPackageMaterialCode(snapshot.getPackageMaterialCode());
      detail.setPeriodMonth(snapshot.getPeriodMonth());
      detail.setLineNo(lineNo++);
      detail.setChildMaterialCode(child.getMaterialCode());
      detail.setChildMaterialName(child.getMaterialName());
      detail.setChildMaterialSpec(child.getMaterialSpec());
      detail.setChildShapeAttr(child.getShapeAttr());
      detail.setQtyPerParent(child.getQtyPerParent());
      detail.setQtyPerTop(child.getQtyPerTop());
      detail.setChildParentBaseQty(resolveParentBaseQty(child));
      detail.setSourceHierarchyId(child.getId());
      detail.setSourceParentCode(child.getParentCode());
      detail.setSourcePath(child.getPath());
      detail.setSourceSortSeq(child.getSortSeq());
      details.add(detail);
    }
    return details;
  }

  private BomRawHierarchy selectSourceParent(NormalizedRequest req) {
    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getMaterialCode, req.packageMaterialCode)
                .eq(BomRawHierarchy::getSourceType, req.sourceType)
                .eq(StringUtils.hasText(req.topProductCode),
                    BomRawHierarchy::getTopProductCode, req.topProductCode)
                .eq(StringUtils.hasText(req.bomPurpose), BomRawHierarchy::getBomPurpose, req.bomPurpose)
                .le(req.asOfDate != null, BomRawHierarchy::getEffectiveFrom, req.asOfDate)
                .and(req.asOfDate != null,
                    w -> w.ge(BomRawHierarchy::getEffectiveTo, req.asOfDate)
                        .or()
                        .isNull(BomRawHierarchy::getEffectiveTo)));
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    List<BomRawHierarchy> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator
        .comparing((BomRawHierarchy r) -> r.getBuiltAt() == null ? LocalDateTime.MIN : r.getBuiltAt())
        .reversed()
        .thenComparing((BomRawHierarchy r) -> r.getId() == null ? Long.MIN_VALUE : r.getId(), Comparator.reverseOrder()));
    return sorted.get(0);
  }

  private List<BomRawHierarchy> selectDirectChildren(NormalizedRequest req, BomRawHierarchy parent) {
    Integer childLevel = parent.getLevel() == null ? null : parent.getLevel() + 1;
    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, parent.getTopProductCode())
                .eq(BomRawHierarchy::getSourceType, parent.getSourceType())
                .eq(StringUtils.hasText(parent.getBomPurpose()),
                    BomRawHierarchy::getBomPurpose, parent.getBomPurpose())
                .isNull(!StringUtils.hasText(parent.getBomPurpose()), BomRawHierarchy::getBomPurpose)
                .eq(BomRawHierarchy::getParentCode, parent.getMaterialCode())
                .eq(childLevel != null, BomRawHierarchy::getLevel, childLevel)
                .likeRight(StringUtils.hasText(parent.getPath()), BomRawHierarchy::getPath, parent.getPath())
                .le(req.asOfDate != null, BomRawHierarchy::getEffectiveFrom, req.asOfDate)
                .and(req.asOfDate != null,
                    w -> w.ge(BomRawHierarchy::getEffectiveTo, req.asOfDate)
                        .or()
                        .isNull(BomRawHierarchy::getEffectiveTo)));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .filter(r -> r.getId() == null || !r.getId().equals(parent.getId()))
        .sorted(Comparator
            .comparing((BomRawHierarchy r) -> r.getSortSeq() == null ? Integer.MAX_VALUE : r.getSortSeq())
            .thenComparing(r -> r.getPath() == null ? "" : r.getPath())
            .thenComparing(r -> r.getId() == null ? Long.MAX_VALUE : r.getId()))
        .toList();
  }

  private BigDecimal resolveParentBaseQty(BomRawHierarchy row) {
    if (row == null
        || !StringUtils.hasText(row.getSourceImportBatchId())
        || !StringUtils.hasText(row.getParentCode())
        || !StringUtils.hasText(row.getMaterialCode())) {
      return null;
    }
    List<BomU9Source> rows =
        bomU9SourceMapper.selectList(
            Wrappers.<BomU9Source>lambdaQuery()
                .eq(BomU9Source::getImportBatchId, row.getSourceImportBatchId())
                .eq(BomU9Source::getParentMaterialNo, row.getParentCode())
                .eq(BomU9Source::getChildMaterialNo, row.getMaterialCode())
                .eq(StringUtils.hasText(row.getBomPurpose()), BomU9Source::getBomPurpose, row.getBomPurpose())
                .eq(row.getSortSeq() != null, BomU9Source::getChildSeq, row.getSortSeq())
                .eq(row.getEffectiveFrom() != null, BomU9Source::getEffectiveFrom, row.getEffectiveFrom())
                .orderByDesc(BomU9Source::getId)
                .last("LIMIT 1"));
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    return rows.get(0).getParentBaseQty();
  }

  private PackageComponentSnapshot selectByPackagePeriodAndTop(
      String packageMaterialCode, String periodMonth, String topProductCode) {
    return snapshotMapper.selectOne(
        Wrappers.<PackageComponentSnapshot>lambdaQuery()
            .eq(PackageComponentSnapshot::getPackageMaterialCode, packageMaterialCode)
            .eq(PackageComponentSnapshot::getPeriodMonth, periodMonth)
            .eq(PackageComponentSnapshot::getSourceTopProductCode, topProductCode)
            .last("LIMIT 1"));
  }

  private List<PackageComponentSnapshotDetail> selectDetails(Long snapshotId) {
    if (snapshotId == null) {
      return List.of();
    }
    return snapshotDetailMapper.selectList(
        Wrappers.<PackageComponentSnapshotDetail>lambdaQuery()
            .eq(PackageComponentSnapshotDetail::getSnapshotId, snapshotId)
            .orderByAsc(PackageComponentSnapshotDetail::getLineNo));
  }

  private NormalizedRequest normalize(PackageSnapshotRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request 必填");
    }
    String packageMaterialCode = trimToNull(request.getPackageMaterialCode());
    if (packageMaterialCode == null) {
      throw new IllegalArgumentException("packageMaterialCode 必填");
    }
    String periodMonth = trimToNull(request.getPeriodMonth());
    if (periodMonth == null) {
      periodMonth = YearMonth.now().toString();
    }
    return new NormalizedRequest(
        packageMaterialCode,
        periodMonth,
        trimToNull(request.getQuoteNo()),
        trimToNull(request.getOaNo()),
        trimToNull(request.getTopProductCode()),
        DEFAULT_BOM_PURPOSE,
        trimToNull(request.getSourceType()) == null ? DEFAULT_BOM_SOURCE_TYPE : trimToNull(request.getSourceType()),
        request.getAsOfDate());
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record NormalizedRequest(
      String packageMaterialCode,
      String periodMonth,
      String quoteNo,
      String oaNo,
      String topProductCode,
      String bomPurpose,
      String sourceType,
      LocalDate asOfDate) {}
}
