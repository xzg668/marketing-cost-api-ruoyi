package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

@DisplayName("PackageComponentSnapshotServiceImpl")
class PackageComponentSnapshotServiceImplTest {

  private PackageComponentSnapshotMapper snapshotMapper;
  private PackageComponentSnapshotDetailMapper snapshotDetailMapper;
  private PackageComponentGapItemMapper gapItemMapper;
  private BomRawHierarchyMapper bomRawHierarchyMapper;
  private BomU9SourceMapper bomU9SourceMapper;
  private PackageComponentSnapshotServiceImpl service;

  @BeforeEach
  void setUp() {
    snapshotMapper = mock(PackageComponentSnapshotMapper.class);
    snapshotDetailMapper = mock(PackageComponentSnapshotDetailMapper.class);
    gapItemMapper = mock(PackageComponentGapItemMapper.class);
    bomRawHierarchyMapper = mock(BomRawHierarchyMapper.class);
    bomU9SourceMapper = mock(BomU9SourceMapper.class);
    service = new PackageComponentSnapshotServiceImpl(
        snapshotMapper, snapshotDetailMapper, gapItemMapper, bomRawHierarchyMapper, bomU9SourceMapper);
  }

  @Test
  @DisplayName("已有同月快照时直接复用，不重新读取 BOM")
  void reusesExistingSnapshot() {
    PackageComponentSnapshot existing = snapshot(10L, "NORMAL");
    PackageComponentSnapshotDetail detail = detail(100L, 10L, 1, "A");
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(snapshotDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(detail));

    PackageSnapshotResult result = service.ensureSnapshot(request());

    assertThat(result.isCreated()).isFalse();
    assertThat(result.getSnapshot()).isSameAs(existing);
    assertThat(result.getDetails()).containsExactly(detail);
    verifyNoInteractions(bomRawHierarchyMapper, gapItemMapper);
    verify(snapshotMapper, never()).insert(any(PackageComponentSnapshot.class));
  }

  @Test
  @DisplayName("正常结构：从 BOM 父节点直接子件生成快照和明细")
  void createsNormalSnapshotWithDirectChildren() {
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(snapshotMapper.insert(any(PackageComponentSnapshot.class))).thenAnswer(invocation -> {
      PackageComponentSnapshot snapshot = invocation.getArgument(0);
      snapshot.setId(20L);
      return 1;
    });
    BomRawHierarchy parent = parent();
    BomRawHierarchy child2 = child(202L, "B", 2, "2.500000", "/107/pkg/B/");
    BomRawHierarchy child1 = child(201L, "A", 1, "1.000000", "/107/pkg/A/");
    when(bomRawHierarchyMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(parent), List.of(child2, child1));
    when(bomU9SourceMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(source("1079900000536", "9830000026238", 60, "12.00000000")),
            List.of(source("9830000026238", "A", 1, "1.00000000")),
            List.of(source("9830000026238", "B", 2, "1.00000000")));

    PackageSnapshotResult result = service.ensureSnapshot(request());

    assertThat(result.isCreated()).isTrue();
    assertThat(result.getStatus()).isEqualTo("NORMAL");
    assertThat(result.getSnapshot().getId()).isEqualTo(20L);
    assertThat(result.getSnapshot().getSourceRawHierarchyId()).isEqualTo(101L);
    assertThat(result.getSnapshot().getPackageQtyPerParent()).isEqualByComparingTo("1.000000");
    assertThat(result.getSnapshot().getPackageParentBaseQty()).isEqualByComparingTo("12.00000000");
    assertThat(result.getDetails()).hasSize(2);
    assertThat(result.getDetails()).extracting(PackageComponentSnapshotDetail::getChildMaterialCode)
        .containsExactly("A", "B");
    assertThat(result.getDetails()).extracting(PackageComponentSnapshotDetail::getLineNo)
        .containsExactly(1, 2);

    ArgumentCaptor<PackageComponentSnapshotDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentSnapshotDetail.class);
    verify(snapshotDetailMapper, org.mockito.Mockito.times(2)).insert(detailCaptor.capture());
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentSnapshotDetail::getSourceHierarchyId)
        .containsExactly(201L, 202L);
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentSnapshotDetail::getChildParentBaseQty)
        .containsExactly(new BigDecimal("1.00000000"), new BigDecimal("1.00000000"));
    verifyNoInteractions(gapItemMapper);
  }

  @Test
  @DisplayName("BOM 目的为空：默认按主制造取结构")
  void blankBomPurposeDefaultsToMainManufacturing() {
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(snapshotMapper.insert(any(PackageComponentSnapshot.class))).thenAnswer(invocation -> {
      PackageComponentSnapshot snapshot = invocation.getArgument(0);
      snapshot.setId(22L);
      return 1;
    });
    PackageSnapshotRequest request = request();
    request.setBomPurpose(" ");
    when(bomRawHierarchyMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(parent()), List.of(child(201L, "A", 1, "1.000000", "/107/pkg/A/")));

    PackageSnapshotResult result = service.ensureSnapshot(request);

    assertThat(result.getStatus()).isEqualTo("NORMAL");
    assertThat(result.getSnapshot().getSourceBomPurpose()).isEqualTo("主制造");
    assertThat(result.getDetails()).hasSize(1);
    assertThat(result.getDetails().get(0).getChildMaterialCode()).isEqualTo("A");
    verifyNoInteractions(gapItemMapper);
  }

  @Test
  @DisplayName("重复子件：保留两行并生成不同 line_no")
  void keepsDuplicateChildRows() {
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(snapshotMapper.insert(any(PackageComponentSnapshot.class))).thenAnswer(invocation -> {
      PackageComponentSnapshot snapshot = invocation.getArgument(0);
      snapshot.setId(21L);
      return 1;
    });
    when(bomRawHierarchyMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(parent()),
            List.of(
                child(301L, "A", 1, "1.000000", "/107/pkg/A1/"),
                child(302L, "A", 2, "3.000000", "/107/pkg/A2/")));

    PackageSnapshotResult result = service.ensureSnapshot(request());

    assertThat(result.getDetails()).hasSize(2);
    assertThat(result.getDetails()).extracting(PackageComponentSnapshotDetail::getChildMaterialCode)
        .containsExactly("A", "A");
    assertThat(result.getDetails()).extracting(PackageComponentSnapshotDetail::getLineNo)
        .containsExactly(1, 2);
    assertThat(result.getDetails()).extracting(PackageComponentSnapshotDetail::getQtyPerParent)
        .containsExactly(new BigDecimal("1.000000"), new BigDecimal("3.000000"));
  }

  @Test
  @DisplayName("缺结构：找不到包装父节点时生成 MISSING_STRUCTURE 快照和 gap")
  void createsMissingStructureWhenParentNotFound() {
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(bomRawHierarchyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(snapshotMapper.insert(any(PackageComponentSnapshot.class))).thenAnswer(invocation -> {
      PackageComponentSnapshot snapshot = invocation.getArgument(0);
      snapshot.setId(30L);
      return 1;
    });

    PackageSnapshotResult result = service.ensureSnapshot(request());

    assertThat(result.isCreated()).isTrue();
    assertThat(result.getStatus()).isEqualTo("MISSING_STRUCTURE");
    assertThat(result.getDetails()).isEmpty();
    assertThat(result.getWarnings()).singleElement().asString().contains("未在 lp_bom_raw_hierarchy");

    ArgumentCaptor<PackageComponentGapItem> gapCaptor =
        ArgumentCaptor.forClass(PackageComponentGapItem.class);
    verify(gapItemMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_STRUCTURE");
    assertThat(gapCaptor.getValue().getQuoteNo()).isEqualTo("Q-001");
    assertThat(gapCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(gapCaptor.getValue().getTopProductCode()).isEqualTo("1079900000536");
    assertThat(gapCaptor.getValue().getPackageMaterialCode()).isEqualTo("9830000026238");
    assertThat(gapCaptor.getValue().getLineNo()).isNull();
    assertThat(gapCaptor.getValue().getChildMaterialCode()).isNull();
    assertThat(gapCaptor.getValue().getMissingMaterialCode()).isEqualTo("9830000026238");
    assertThat(gapCaptor.getValue().getStatus()).isEqualTo("PENDING_MAINTAIN");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
    verify(snapshotDetailMapper, never()).insert(any(PackageComponentSnapshotDetail.class));
  }

  @Test
  @DisplayName("缺结构重复生成：复用既有 gap，不重复插入")
  void upsertsMissingStructureGap() {
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(bomRawHierarchyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(snapshotMapper.insert(any(PackageComponentSnapshot.class))).thenAnswer(invocation -> {
      PackageComponentSnapshot snapshot = invocation.getArgument(0);
      snapshot.setId(31L);
      return 1;
    });
    PackageComponentGapItem existingGap = new PackageComponentGapItem();
    existingGap.setId(700L);
    when(gapItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existingGap));

    service.ensureSnapshot(request());

    verify(gapItemMapper, never()).insert(any(PackageComponentGapItem.class));
    ArgumentCaptor<PackageComponentGapItem> gapCaptor =
        ArgumentCaptor.forClass(PackageComponentGapItem.class);
    verify(gapItemMapper).updateById(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getId()).isEqualTo(700L);
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_STRUCTURE");
    assertThat(gapCaptor.getValue().getStatus()).isEqualTo("PENDING_MAINTAIN");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }

  @Test
  @DisplayName("并发唯一键冲突：插入失败后重新查询并返回已存在快照")
  void duplicateInsertReloadsExistingSnapshot() {
    PackageComponentSnapshot concurrent = snapshot(40L, "NORMAL");
    PackageComponentSnapshotDetail concurrentDetail = detail(401L, 40L, 1, "A");
    when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null, concurrent);
    when(snapshotMapper.insert(any(PackageComponentSnapshot.class)))
        .thenThrow(new DuplicateKeyException("duplicate"));
    when(bomRawHierarchyMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(parent()), List.of(child(201L, "A", 1, "1.000000", "/107/pkg/A/")));
    when(snapshotDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(concurrentDetail));

    PackageSnapshotResult result = service.ensureSnapshot(request());

    assertThat(result.isCreated()).isFalse();
    assertThat(result.getSnapshot()).isSameAs(concurrent);
    assertThat(result.getDetails()).containsExactly(concurrentDetail);
    verify(snapshotDetailMapper, never()).insert(any(PackageComponentSnapshotDetail.class));
    verifyNoInteractions(gapItemMapper);
  }

  private PackageSnapshotRequest request() {
    PackageSnapshotRequest request = new PackageSnapshotRequest();
    request.setPackageMaterialCode(" 9830000026238 ");
    request.setPeriodMonth("2026-05");
    request.setQuoteNo("Q-001");
    request.setOaNo("OA-001");
    request.setTopProductCode("1079900000536");
    request.setBomPurpose("主制造");
    request.setSourceType("U9");
    request.setAsOfDate(LocalDate.parse("2026-05-21"));
    return request;
  }

  private PackageComponentSnapshot snapshot(Long id, String status) {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setId(id);
    snapshot.setPackageMaterialCode("9830000026238");
    snapshot.setPeriodMonth("2026-05");
    snapshot.setStatus(status);
    return snapshot;
  }

  private PackageComponentSnapshotDetail detail(Long id, Long snapshotId, int lineNo, String childCode) {
    PackageComponentSnapshotDetail detail = new PackageComponentSnapshotDetail();
    detail.setId(id);
    detail.setSnapshotId(snapshotId);
    detail.setLineNo(lineNo);
    detail.setChildMaterialCode(childCode);
    return detail;
  }

  private BomRawHierarchy parent() {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(101L);
    row.setTopProductCode("1079900000536");
    row.setParentCode("1079900000536");
    row.setMaterialCode("9830000026238");
    row.setMaterialName("包装组件");
    row.setLevel(1);
    row.setPath("/107/pkg/");
    row.setSortSeq(60);
    row.setQtyPerParent(new BigDecimal("1.000000"));
    row.setQtyPerTop(new BigDecimal("1.000000"));
    row.setBomPurpose("主制造");
    row.setSourceType("U9");
    row.setSourceImportBatchId("b-test");
    row.setEffectiveFrom(LocalDate.parse("2026-01-01"));
    row.setBuiltAt(LocalDateTime.parse("2026-05-20T10:15:30"));
    return row;
  }

  private BomRawHierarchy child(
      Long id, String materialCode, Integer sortSeq, String qtyPerParent, String path) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode("1079900000536");
    row.setParentCode("9830000026238");
    row.setMaterialCode(materialCode);
    row.setMaterialName("子件" + materialCode);
    row.setMaterialSpec("SPEC-" + materialCode);
    row.setShapeAttr("采购件");
    row.setLevel(2);
    row.setPath(path);
    row.setSortSeq(sortSeq);
    row.setQtyPerParent(new BigDecimal(qtyPerParent));
    row.setQtyPerTop(new BigDecimal(qtyPerParent));
    row.setBomPurpose("主制造");
    row.setSourceType("U9");
    row.setSourceImportBatchId("b-test");
    row.setEffectiveFrom(LocalDate.parse("2026-01-01"));
    return row;
  }

  private BomU9Source source(String parentCode, String childCode, Integer childSeq, String parentBaseQty) {
    BomU9Source row = new BomU9Source();
    row.setImportBatchId("b-test");
    row.setParentMaterialNo(parentCode);
    row.setChildMaterialNo(childCode);
    row.setBomPurpose("主制造");
    row.setChildSeq(childSeq);
    row.setEffectiveFrom(LocalDate.parse("2026-01-01"));
    row.setParentBaseQty(new BigDecimal(parentBaseQty));
    return row;
  }
}
