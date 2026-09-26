package com.sanhua.marketingcost.service.quotebom;

import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.PublicationContext;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApprovedElectronicBomRawSnapshotPublisherTest {
  private final QuoteBomSupplementDetailMapper detailMapper =
      mock(QuoteBomSupplementDetailMapper.class);
  private final BomRawHierarchyMapper rawMapper = mock(BomRawHierarchyMapper.class);
  private ApprovedElectronicBomRawSnapshotPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new ApprovedElectronicBomRawSnapshotPublisher(detailMapper, rawMapper);
  }

  @Test
  void publishesExactApprovedVersionAsImmutableElectronicRawBatch() {
    when(detailMapper.selectList(any())).thenReturn(List.of(
        detail(1, 0, null, "P-1", "/P-1/"),
        u9Detail(2, 1, "P-1", "C-1", "/P-1/C-1/")));
    when(rawMapper.selectList(any())).thenReturn(List.of());

    String batch = publisher.publish(product());

    assertThat(batch).isEqualTo("SUPPLEMENT_VERSION:90");
    ArgumentCaptor<BomRawHierarchy> rows = ArgumentCaptor.forClass(BomRawHierarchy.class);
    verify(rawMapper, org.mockito.Mockito.times(2)).insert(rows.capture());
    assertThat(rows.getAllValues()).allSatisfy(row -> {
      assertThat(row.getBuildBatchId()).isEqualTo(batch);
      assertThat(row.getBomPurpose()).isEqualTo("主制造");
      assertThat(row.getPriceOrgCode()).isEqualTo("210");
      assertThat(row.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    });
    assertThat(rows.getAllValues()).extracting(BomRawHierarchy::getIsLeaf)
        .containsExactly(0, 1);
    assertThat(rows.getAllValues()).extracting(BomRawHierarchy::getSourceType)
        .containsExactly("E_DRAWING", "E_DRAWING");
    assertThat(rows.getAllValues().get(1).getSourceU9RowId()).isEqualTo(7002L);
  }

  @Test
  void determinesLeafByStructuralPathWhenSameMaterialAlsoAppearsAsParentElsewhere() {
    when(detailMapper.selectList(any())).thenReturn(List.of(
        detail(1, 0, null, "P-1", "/P-1/"),
        detail(2, 1, "P-1", "C-1", "/P-1/ED:1/"),
        detail(3, 2, "C-1", "X-1", "/P-1/ED:1/ED:2/"),
        detail(4, 1, "P-1", "C-1", "/P-1/ED:3/")));
    when(rawMapper.selectList(any())).thenReturn(List.of());

    publisher.publish(product());

    ArgumentCaptor<BomRawHierarchy> rows = ArgumentCaptor.forClass(BomRawHierarchy.class);
    verify(rawMapper, org.mockito.Mockito.times(4)).insert(rows.capture());
    assertThat(rows.getAllValues()).extracting(BomRawHierarchy::getIsLeaf)
        .containsExactly(0, 0, 1, 1);
  }

  @Test
  void retryWithCompleteExistingBatchIsIdempotent() {
    List<QuoteBomSupplementDetail> details = List.of(
        detail(1, 0, null, "P-1", "/P-1/"),
        detail(2, 1, "P-1", "C-1", "/P-1/C-1/"));
    when(detailMapper.selectList(any())).thenReturn(details);
    when(rawMapper.selectList(any())).thenReturn(List.of(
        raw(details.get(0), 0), raw(details.get(1), 1)));

    assertThat(publisher.publish(product())).isEqualTo("SUPPLEMENT_VERSION:90");

    verify(rawMapper, never()).insert(any(BomRawHierarchy.class));
  }

  @Test
  void retryAfterU9ReimportAcceptsHistoricalSourceIds() {
    QuoteBomSupplementDetail root = detail(1, 0, null, "P-1", "/P-1/");
    QuoteBomSupplementDetail child = u9Detail(2, 1, "P-1", "C-1", "/P-1/C-1/");
    BomRawHierarchy oldRoot = raw(root, 0);
    BomRawHierarchy oldChild = raw(child, 1);
    oldChild.setSourceU9RowId(7002L);
    oldChild.setSourceLineKey("U9_EXPANDED|90|2|NO_ED|7002");
    child.setSourceRawHierarchyId(9999L);
    when(detailMapper.selectList(any())).thenReturn(List.of(root, child));
    when(rawMapper.selectList(any())).thenReturn(List.of(oldRoot, oldChild));

    assertThat(publisher.publish(product())).isEqualTo("SUPPLEMENT_VERSION:90");
    verify(rawMapper, never()).insert(any(BomRawHierarchy.class));
  }

  @Test
  void modelOnlyProductPublishesUnderItsStableTemporaryIdentity() {
    when(detailMapper.selectList(any())).thenReturn(List.of(
        detail(1, 0, "MODEL:MODEL-NEW", "MODEL:MODEL-NEW", "/MODEL:MODEL-NEW/"),
        detail(2, 1, "MODEL:MODEL-NEW", "C-1", "/MODEL:MODEL-NEW/C-1/")));
    when(rawMapper.selectList(any())).thenReturn(List.of());
    PublicationContext product = new PublicationContext(
        90L, null, "MODEL:MODEL-NEW", "210", "COMMERCIAL", "2026-08");

    publisher.publish(product);

    ArgumentCaptor<BomRawHierarchy> rows = ArgumentCaptor.forClass(BomRawHierarchy.class);
    verify(rawMapper, org.mockito.Mockito.times(2)).insert(rows.capture());
    assertThat(rows.getAllValues())
        .extracting(BomRawHierarchy::getTopProductCode)
        .containsOnly("MODEL:MODEL-NEW");
  }

  @Test
  void partialExistingBatchIsBlockedInsteadOfSilentlyOverwritten() {
    when(detailMapper.selectList(any())).thenReturn(List.of(
        detail(1, 0, "P-1", "P-1", "/P-1/"),
        detail(2, 1, "P-1", "C-1", "/P-1/C-1/")));
    when(rawMapper.selectList(any())).thenReturn(List.of(new BomRawHierarchy()));

    assertThatThrownBy(() -> publisher.publish(product()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不完整");
    verify(rawMapper, never()).insert(any(BomRawHierarchy.class));
  }

  private PublicationContext product() {
    return new PublicationContext(
        90L, "P-1", null, "210", "COMMERCIAL", "2026-08");
  }

  private QuoteBomSupplementDetail detail(
      int lineNo, int level, String parent, String material, String path) {
    QuoteBomSupplementDetail row = new QuoteBomSupplementDetail();
    row.setSupplementVersionId(90L);
    row.setLineNo(lineNo);
    row.setLevel(level);
    row.setParentCode(parent);
    row.setMaterialCode(material);
    row.setMaterialName(material);
    row.setPath(path);
    row.setSortSeq(lineNo);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setShapeAttr(level == 0 ? "制造件" : "采购件");
    row.setSourceCategory(level == 0 ? "制造件" : "采购件");
    return row;
  }

  private QuoteBomSupplementDetail u9Detail(
      int lineNo, int level, String parent, String material, String path) {
    QuoteBomSupplementDetail row = detail(lineNo, level, parent, material, path);
    row.setNodeSourceType(ElectronicDrawingHybridBomAssembler.SOURCE_U9_EXPANDED);
    row.setSourceRawHierarchyId(7002L);
    return row;
  }

  private BomRawHierarchy raw(QuoteBomSupplementDetail detail, int isLeaf) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setPriceOrgCode("210");
    row.setTopProductCode("P-1");
    row.setParentCode(detail.getLevel() == 0 ? detail.getMaterialCode() : detail.getParentCode());
    row.setMaterialCode(detail.getMaterialCode());
    row.setLevel(detail.getLevel());
    row.setPath(detail.getPath());
    row.setSortSeq(detail.getSortSeq());
    row.setSourceU9RowId(detail.getSourceRawHierarchyId());
    row.setSourceLineKey("E_DRAWING|90|" + detail.getLineNo() + "|NO_ED|NO_U9");
    row.setQtyPerParent(detail.getQtyPerParent());
    row.setQtyPerTop(detail.getQtyPerTop());
    row.setMaterialName(detail.getMaterialName());
    row.setShapeAttr(detail.getShapeAttr());
    row.setSourceCategory(detail.getSourceCategory());
    row.setBomPurpose("主制造");
    row.setBomVersion("ED-90");
    row.setBomStatus("APPROVED");
    row.setIsLeaf(isLeaf);
    row.setEffectiveFrom(LocalDate.of(2026, 8, 1));
    row.setSourceType("E_DRAWING");
    row.setSourceImportBatchId("SUPPLEMENT_VERSION:90");
    row.setBuildBatchId("SUPPLEMENT_VERSION:90");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }
}
