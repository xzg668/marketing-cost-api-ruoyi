package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("包装组件结构只读服务")
class PackageComponentStructureReadServiceImplTest {

  private PackageComponentSnapshotMapper snapshotMapper;
  private PackageComponentSnapshotDetailMapper snapshotDetailMapper;
  private MaterialMasterRawMapper materialMasterRawMapper;
  private QuoteBomPackageReferenceMapper packageReferenceMapper;
  private PackageComponentStructureReadServiceImpl service;

  @BeforeEach
  void setUp() {
    snapshotMapper = mock(PackageComponentSnapshotMapper.class);
    snapshotDetailMapper = mock(PackageComponentSnapshotDetailMapper.class);
    materialMasterRawMapper = mock(MaterialMasterRawMapper.class);
    packageReferenceMapper = mock(QuoteBomPackageReferenceMapper.class);
    service = new PackageComponentStructureReadServiceImpl(
        snapshotMapper, snapshotDetailMapper, materialMasterRawMapper, packageReferenceMapper);
  }

  @Test
  @DisplayName("包装组件快照按 source_top_product_code 隔离读取")
  void readsSnapshotBySourceTopProductCode() {
    when(snapshotMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(snapshot(10L, "PKG-PARENT", "FIN-A", "2026-05")));
    when(snapshotDetailMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(detail(100L, 10L, 1, "PKG-CHILD-A", "1.00000000")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(master("PKG-PARENT"), master("PKG-CHILD-A")));

    PackageComponentStructureReadResult result =
        service.readByReference("FIN-A", "FIN-A", "2026-05");

    assertThat(result.found()).isTrue();
    assertThat(result.sourceTopProductCode()).isEqualTo("FIN-A");
    assertThat(result.lines()).singleElement().satisfies(line -> {
      assertThat(line.packageParentCode()).isEqualTo("PKG-PARENT");
      assertThat(line.packageChildCode()).isEqualTo("PKG-CHILD-A");
      assertThat(line.packageParentModel()).isEqualTo("MODEL-PKG-PARENT");
      assertThat(line.packageChildDrawingNo()).isEqualTo("DRAW-PKG-CHILD-A");
      assertThat(line.childSourceRawHierarchyId()).isEqualTo(5100L);
    });
  }

  @Test
  @DisplayName("同包装父件在不同参考成品下不会串结构")
  void samePackageParentDoesNotMixAcrossReferenceFinishedProducts() {
    when(snapshotMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(snapshot(11L, "PKG-PARENT", "FIN-A", "2026-05")),
            List.of(snapshot(12L, "PKG-PARENT", "FIN-B", "2026-05")));
    when(snapshotDetailMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(detail(101L, 11L, 1, "PKG-CHILD-A", "1.00000000")),
            List.of(detail(102L, 12L, 1, "PKG-CHILD-B", "3.00000000")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(
            List.of(master("PKG-PARENT"), master("PKG-CHILD-A")),
            List.of(master("PKG-PARENT"), master("PKG-CHILD-B")));

    PackageComponentStructureReadResult a =
        service.readByReference("FIN-A", "FIN-A", "2026-05");
    PackageComponentStructureReadResult b =
        service.readByReference("FIN-B", "FIN-B", "2026-05");

    assertThat(a.found()).isTrue();
    assertThat(b.found()).isTrue();
    assertThat(a.lines()).extracting(line -> line.packageChildCode()).containsExactly("PKG-CHILD-A");
    assertThat(b.lines()).extracting(line -> line.packageChildCode()).containsExactly("PKG-CHILD-B");
    assertThat(a.lines().get(0).childQtyPerParent()).isEqualByComparingTo("1.00000000");
    assertThat(b.lines().get(0).childQtyPerParent()).isEqualByComparingTo("3.00000000");
  }

  @Test
  @DisplayName("包装子件重复行保留，不合并")
  void keepsDuplicateChildRows() {
    when(snapshotMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(snapshot(20L, "PKG-PARENT", "FIN-A", "2026-05")));
    when(snapshotDetailMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                detail(201L, 20L, 1, "PKG-CHILD-A", "1.00000000"),
                detail(202L, 20L, 2, "PKG-CHILD-A", "2.50000000")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(master("PKG-PARENT"), master("PKG-CHILD-A")));

    PackageComponentStructureReadResult result =
        service.readByReference("FIN-A", "FIN-A", "2026-05");

    assertThat(result.found()).isTrue();
    assertThat(result.lines()).hasSize(2);
    assertThat(result.lines()).extracting(line -> line.packageChildCode())
        .containsExactly("PKG-CHILD-A", "PKG-CHILD-A");
    assertThat(result.lines()).extracting(line -> line.lineNo()).containsExactly(1, 2);
    assertThat(result.lines()).extracting(line -> line.childQtyPerParent())
        .containsExactly(new BigDecimal("1.00000000"), new BigDecimal("2.50000000"));
  }

  @Test
  @DisplayName("裸品包装参考长期复用，不按 6 个月失效")
  void reusesApprovedBarePackageReferenceWithoutSixMonthExpiry() {
    when(packageReferenceMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(packageReference(900L, "BARE-001", "FIN-OLD", "2025-01")));
    when(snapshotMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(snapshot(30L, "PKG-PARENT", "FIN-OLD", "2025-01")));
    when(snapshotDetailMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(detail(301L, 30L, 1, "PKG-CHILD-A", "1.00000000")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(master("PKG-PARENT"), master("PKG-CHILD-A")));

    PackageComponentStructureReadResult result =
        service.readApprovedReferenceForBareProduct("BARE-001");

    assertThat(result.found()).isTrue();
    assertThat(result.packageReferenceId()).isEqualTo(900L);
    assertThat(result.referenceFinishedCode()).isEqualTo("FIN-OLD");
    assertThat(result.periodMonth()).isEqualTo("2025-01");
    assertThat(result.lines()).singleElement()
        .satisfies(line -> assertThat(line.packageChildCode()).isEqualTo("PKG-CHILD-A"));
  }

  @Test
  @DisplayName("快照缺失时只返回明确缺口，不隐式刷新")
  void returnsGapWhenSnapshotMissing() {
    when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    PackageComponentStructureReadResult result =
        service.readByReference("FIN-MISSING", "FIN-MISSING", "2026-05");

    assertThat(result.found()).isFalse();
    assertThat(result.lines()).isEmpty();
    assertThat(result.gaps()).containsExactly("未找到该参考成品在所选期间的包装结构");
  }

  @Test
  @DisplayName("缺少查询料号时返回业务可读提示")
  void returnsBusinessMessageWhenReferenceCodeMissing() {
    PackageComponentStructureReadResult result =
        service.readByReference(null, null, "2026-05");

    assertThat(result.found()).isFalse();
    assertThat(result.lines()).isEmpty();
    assertThat(result.gaps()).containsExactly("请先输入参考成品料号，系统才能查询对应的包装结构");
  }

  private PackageComponentSnapshot snapshot(
      Long id, String packageCode, String sourceTopCode, String periodMonth) {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setId(id);
    snapshot.setPackageMaterialCode(packageCode);
    snapshot.setPackageMaterialName("包装父件" + packageCode);
    snapshot.setPeriodMonth(periodMonth);
    snapshot.setStatus("NORMAL");
    snapshot.setSourceTopProductCode(sourceTopCode);
    snapshot.setPackageQtyPerParent(new BigDecimal("1.00000000"));
    snapshot.setPackageQtyPerTop(new BigDecimal("1.00000000"));
    snapshot.setPackageParentBaseQty(new BigDecimal("1.00000000"));
    snapshot.setSourceRawHierarchyId(4000L + id);
    snapshot.setSourcePath("/" + sourceTopCode + "/" + packageCode + "/");
    return snapshot;
  }

  private PackageComponentSnapshotDetail detail(
      Long id, Long snapshotId, int lineNo, String childCode, String qty) {
    PackageComponentSnapshotDetail detail = new PackageComponentSnapshotDetail();
    detail.setId(id);
    detail.setSnapshotId(snapshotId);
    detail.setPackageMaterialCode("PKG-PARENT");
    detail.setPeriodMonth("2026-05");
    detail.setLineNo(lineNo);
    detail.setChildMaterialCode(childCode);
    detail.setChildMaterialName("包装子件" + childCode);
    detail.setChildMaterialSpec("SPEC-" + childCode);
    detail.setChildShapeAttr("采购件");
    detail.setQtyPerParent(new BigDecimal(qty));
    detail.setQtyPerTop(new BigDecimal(qty));
    detail.setChildParentBaseQty(new BigDecimal("1.00000000"));
    detail.setSourceHierarchyId(5000L + id);
    detail.setSourceParentCode("PKG-PARENT");
    detail.setSourcePath("/FIN/PKG-PARENT/" + childCode + "/" + lineNo + "/");
    detail.setSourceSortSeq(lineNo);
    return detail;
  }

  private MaterialMasterRaw master(String code) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode(code);
    raw.setMaterialName("主档" + code);
    raw.setMaterialSpec("SPEC-" + code);
    raw.setMaterialModel("MODEL-" + code);
    raw.setDrawingNo("DRAW-" + code);
    raw.setShapeAttr(code.contains("PARENT") ? "虚拟" : "采购件");
    raw.setMainCategoryCode(code.contains("PARENT") ? "1515501" : "1515601");
    raw.setUnit("PCS");
    raw.setActiveFlag(1);
    raw.setImportBatchId("u9-latest");
    return raw;
  }

  private QuoteBomPackageReference packageReference(
      Long id, String bareCode, String referenceFinishedCode, String periodMonth) {
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setId(id);
    reference.setBareProductCode(bareCode);
    reference.setReferenceFinishedCode(referenceFinishedCode);
    reference.setSourceTopProductCode(referenceFinishedCode);
    reference.setPeriodMonth(periodMonth);
    reference.setReferenceStatus("APPROVED");
    reference.setActiveFlag(1);
    reference.setUpdatedAt(LocalDateTime.parse("2025-01-15T10:00:00"));
    return reference;
  }
}
