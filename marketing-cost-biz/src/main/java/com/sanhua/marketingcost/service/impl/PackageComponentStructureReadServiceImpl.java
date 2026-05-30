package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PackageComponentStructureReadServiceImpl
    implements PackageComponentStructureReadService {

  private static final String SNAPSHOT_STATUS_NORMAL = "NORMAL";
  private static final String PACKAGE_REFERENCE_STATUS_APPROVED = "APPROVED";
  private static final int ACTIVE = 1;

  private final PackageComponentSnapshotMapper snapshotMapper;
  private final PackageComponentSnapshotDetailMapper snapshotDetailMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;
  private final QuoteBomPackageReferenceMapper packageReferenceMapper;

  public PackageComponentStructureReadServiceImpl(
      PackageComponentSnapshotMapper snapshotMapper,
      PackageComponentSnapshotDetailMapper snapshotDetailMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      QuoteBomPackageReferenceMapper packageReferenceMapper) {
    this.snapshotMapper = snapshotMapper;
    this.snapshotDetailMapper = snapshotDetailMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.packageReferenceMapper = packageReferenceMapper;
  }

  @Override
  public PackageComponentStructureReadResult readByReference(
      String referenceFinishedCode, String sourceTopProductCode, String periodMonth) {
    return readSnapshots(referenceFinishedCode, sourceTopProductCode, periodMonth, null);
  }

  @Override
  public PackageComponentStructureReadResult readApprovedReferenceForBareProduct(
      String bareProductCode) {
    String normalizedBareCode = trimToNull(bareProductCode);
    if (normalizedBareCode == null) {
      return new PackageComponentStructureReadResult(
          null, null, null, null, false, List.of(), List.of("裸品料号为空"));
    }
    List<QuoteBomPackageReference> references =
        packageReferenceMapper.selectList(
            Wrappers.<QuoteBomPackageReference>lambdaQuery()
                .eq(QuoteBomPackageReference::getBareProductCode, normalizedBareCode)
                .eq(QuoteBomPackageReference::getReferenceStatus, PACKAGE_REFERENCE_STATUS_APPROVED)
                .eq(QuoteBomPackageReference::getActiveFlag, ACTIVE));
    QuoteBomPackageReference selected =
        references == null
            ? null
            : references.stream()
                .sorted(packageReferenceComparator())
                .findFirst()
                .orElse(null);
    if (selected == null) {
      return new PackageComponentStructureReadResult(
          null,
          null,
          null,
          null,
          false,
          List.of(),
          List.of("未找到已审核裸品包装参考"));
    }
    return readSnapshots(
        selected.getReferenceFinishedCode(),
        selected.getSourceTopProductCode(),
        selected.getPeriodMonth(),
        selected.getId());
  }

  private PackageComponentStructureReadResult readSnapshots(
      String referenceFinishedCode,
      String sourceTopProductCode,
      String periodMonth,
      Long packageReferenceId) {
    String normalizedReferenceCode = trimToNull(referenceFinishedCode);
    String normalizedSourceTopCode = trimToNull(sourceTopProductCode);
    if (normalizedSourceTopCode == null) {
      normalizedSourceTopCode = normalizedReferenceCode;
    }
    String normalizedPeriodMonth = normalizePeriodMonth(periodMonth);
    if (normalizedSourceTopCode == null) {
      return new PackageComponentStructureReadResult(
          normalizedReferenceCode,
          null,
          normalizedPeriodMonth,
          packageReferenceId,
          false,
          List.of(),
          List.of("请先输入参考成品料号，系统才能查询对应的包装结构"));
    }

    List<PackageComponentSnapshot> snapshots =
        snapshotMapper.selectList(
            Wrappers.<PackageComponentSnapshot>lambdaQuery()
                .eq(PackageComponentSnapshot::getSourceTopProductCode, normalizedSourceTopCode)
                .eq(PackageComponentSnapshot::getPeriodMonth, normalizedPeriodMonth)
                .orderByAsc(PackageComponentSnapshot::getPackageMaterialCode)
                .orderByAsc(PackageComponentSnapshot::getId));
    if (snapshots == null || snapshots.isEmpty()) {
      return new PackageComponentStructureReadResult(
          normalizedReferenceCode,
          normalizedSourceTopCode,
          normalizedPeriodMonth,
          packageReferenceId,
          false,
          List.of(),
          List.of("未找到该参考成品在所选期间的包装结构"));
    }

    List<String> gaps = new ArrayList<>();
    LinkedHashSet<String> materialCodes = new LinkedHashSet<>();
    for (PackageComponentSnapshot snapshot : snapshots) {
      if (!SNAPSHOT_STATUS_NORMAL.equals(trimToNull(snapshot.getStatus()))) {
        gaps.add("包装结构记录 " + snapshot.getId() + " 当前不可用，状态为 " + snapshot.getStatus());
      }
      materialCodes.add(trimToNull(snapshot.getPackageMaterialCode()));
    }

    List<SnapshotWithDetails> groups = new ArrayList<>(snapshots.size());
    for (PackageComponentSnapshot snapshot : snapshots) {
      List<PackageComponentSnapshotDetail> details =
          snapshotDetailMapper.selectList(
              Wrappers.<PackageComponentSnapshotDetail>lambdaQuery()
                  .eq(PackageComponentSnapshotDetail::getSnapshotId, snapshot.getId())
                  .orderByAsc(PackageComponentSnapshotDetail::getLineNo));
      if (details == null || details.isEmpty()) {
        gaps.add("包装结构记录 " + snapshot.getId() + " 暂无子件明细");
        groups.add(new SnapshotWithDetails(snapshot, List.of()));
        continue;
      }
      details.forEach(detail -> materialCodes.add(trimToNull(detail.getChildMaterialCode())));
      groups.add(new SnapshotWithDetails(snapshot, details));
    }

    Map<String, MaterialMasterRaw> masterByCode = selectMasterByCode(materialCodes);
    List<PackageComponentStructureLineDto> lines = new ArrayList<>();
    for (SnapshotWithDetails group : groups) {
      MaterialMasterRaw parentMaster =
          masterByCode.get(trimToNull(group.snapshot().getPackageMaterialCode()));
      for (PackageComponentSnapshotDetail detail : group.details()) {
        MaterialMasterRaw childMaster = masterByCode.get(trimToNull(detail.getChildMaterialCode()));
        PackageComponentStructureLineDto line =
            toLine(
                normalizedReferenceCode,
                normalizedSourceTopCode,
                normalizedPeriodMonth,
                group.snapshot(),
                detail,
                parentMaster,
                childMaster);
        collectMissingFields(line, gaps);
        lines.add(line);
      }
    }

    if (!gaps.isEmpty()) {
      return new PackageComponentStructureReadResult(
          normalizedReferenceCode,
          normalizedSourceTopCode,
          normalizedPeriodMonth,
          packageReferenceId,
          false,
          lines,
          gaps);
    }
    return new PackageComponentStructureReadResult(
        normalizedReferenceCode,
        normalizedSourceTopCode,
        normalizedPeriodMonth,
        packageReferenceId,
        true,
        lines,
        List.of());
  }

  private PackageComponentStructureLineDto toLine(
      String referenceFinishedCode,
      String sourceTopProductCode,
      String periodMonth,
      PackageComponentSnapshot snapshot,
      PackageComponentSnapshotDetail detail,
      MaterialMasterRaw parentMaster,
      MaterialMasterRaw childMaster) {
    return new PackageComponentStructureLineDto(
        snapshot.getId(),
        detail.getId(),
        referenceFinishedCode,
        sourceTopProductCode,
        periodMonth,
        detail.getLineNo(),
        trimToNull(snapshot.getPackageMaterialCode()),
        firstText(snapshot.getPackageMaterialName(), parentMaster == null ? null : parentMaster.getMaterialName()),
        parentMaster == null ? null : trimToNull(parentMaster.getMaterialSpec()),
        parentMaster == null ? null : trimToNull(parentMaster.getMaterialModel()),
        parentMaster == null ? null : trimToNull(parentMaster.getDrawingNo()),
        parentMaster == null ? null : trimToNull(parentMaster.getShapeAttr()),
        parentMaster == null ? null : trimToNull(parentMaster.getMainCategoryCode()),
        parentMaster == null ? null : trimToNull(parentMaster.getUnit()),
        snapshot.getPackageQtyPerParent(),
        snapshot.getPackageQtyPerTop(),
        snapshot.getPackageParentBaseQty(),
        snapshot.getSourceRawHierarchyId(),
        snapshot.getSourcePath(),
        trimToNull(detail.getChildMaterialCode()),
        firstText(detail.getChildMaterialName(), childMaster == null ? null : childMaster.getMaterialName()),
        firstText(detail.getChildMaterialSpec(), childMaster == null ? null : childMaster.getMaterialSpec()),
        childMaster == null ? null : trimToNull(childMaster.getMaterialModel()),
        childMaster == null ? null : trimToNull(childMaster.getDrawingNo()),
        firstText(detail.getChildShapeAttr(), childMaster == null ? null : childMaster.getShapeAttr()),
        childMaster == null ? null : trimToNull(childMaster.getMainCategoryCode()),
        childMaster == null ? null : trimToNull(childMaster.getUnit()),
        detail.getQtyPerParent(),
        detail.getQtyPerTop(),
        detail.getChildParentBaseQty(),
        detail.getSourceHierarchyId(),
        detail.getSourceParentCode(),
        detail.getSourcePath(),
        detail.getSourceSortSeq());
  }

  private void collectMissingFields(PackageComponentStructureLineDto line, List<String> gaps) {
    List<String> missing = new ArrayList<>();
    addMissing(missing, "包装父件料号", line.packageParentCode());
    addMissing(missing, "包装父件名称", line.packageParentName());
    addMissing(missing, "包装父件规格", line.packageParentSpec());
    addMissing(missing, "包装父件型号", line.packageParentModel());
    addMissing(missing, "包装父件图号", line.packageParentDrawingNo());
    addMissing(missing, "包装父件形态属性", line.packageParentShapeAttr());
    addMissing(missing, "包装父件主分类", line.packageParentMainCategoryCode());
    addMissing(missing, "包装父件单位", line.packageParentUnit());
    addMissing(missing, "包装父件用量", line.packageQtyPerParent());
    addMissing(missing, "包装父件累计用量", line.packageQtyPerTop());
    addMissing(missing, "包装父件母件底数", line.packageParentBaseQty());
    addMissing(missing, "包装父件来源 BOM 行", line.packageSourceRawHierarchyId());
    addMissing(missing, "包装父件来源路径", line.packageSourcePath());
    addMissing(missing, "包装子件料号", line.packageChildCode());
    addMissing(missing, "包装子件名称", line.packageChildName());
    addMissing(missing, "包装子件规格", line.packageChildSpec());
    addMissing(missing, "包装子件型号", line.packageChildModel());
    addMissing(missing, "包装子件图号", line.packageChildDrawingNo());
    addMissing(missing, "包装子件形态属性", line.packageChildShapeAttr());
    addMissing(missing, "包装子件主分类", line.packageChildMainCategoryCode());
    addMissing(missing, "包装子件单位", line.packageChildUnit());
    addMissing(missing, "包装子件用量", line.childQtyPerParent());
    addMissing(missing, "包装子件累计用量", line.childQtyPerTop());
    addMissing(missing, "包装子件母件底数", line.childParentBaseQty());
    addMissing(missing, "来源 BOM 行", line.childSourceRawHierarchyId());
    addMissing(missing, "来源路径", line.childSourcePath());
    if (!missing.isEmpty()) {
      gaps.add(
          "包装结构记录 "
              + line.snapshotId()
              + " 第 "
              + line.lineNo()
              + " 行缺少 "
              + String.join("、", missing));
    }
  }

  private void addMissing(List<String> missing, String label, Object value) {
    if (value instanceof String text) {
      if (trimToNull(text) == null) {
        missing.add(label);
      }
      return;
    }
    if (value == null) {
      missing.add(label);
    }
  }

  private Map<String, MaterialMasterRaw> selectMasterByCode(LinkedHashSet<String> codes) {
    codes.removeIf(code -> trimToNull(code) == null);
    if (codes.isEmpty()) {
      return Map.of();
    }
    List<MaterialMasterRaw> rows = materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null);
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    return rows.stream()
        .filter(row -> trimToNull(row.getMaterialCode()) != null)
        .collect(
            Collectors.toMap(
                row -> trimToNull(row.getMaterialCode()),
                Function.identity(),
                (first, ignored) -> first));
  }

  private Comparator<QuoteBomPackageReference> packageReferenceComparator() {
    return Comparator
        .comparing(
            (QuoteBomPackageReference ref) ->
                ref.getUpdatedAt() == null ? LocalDateTime.MIN : ref.getUpdatedAt(),
            Comparator.reverseOrder())
        .thenComparing(
            ref -> ref.getId() == null ? Long.MIN_VALUE : ref.getId(),
            Comparator.reverseOrder());
  }

  private String normalizePeriodMonth(String periodMonth) {
    String value = trimToNull(periodMonth);
    if (value == null) {
      return YearMonth.now().toString();
    }
    return YearMonth.parse(value).toString();
  }

  private String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record SnapshotWithDetails(
      PackageComponentSnapshot snapshot, List<PackageComponentSnapshotDetail> details) {}
}
