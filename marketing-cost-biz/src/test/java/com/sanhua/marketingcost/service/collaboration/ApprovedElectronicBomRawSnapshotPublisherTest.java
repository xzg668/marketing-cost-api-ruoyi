package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import java.math.BigDecimal;
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
        detail(1, 0, "P-1", "P-1", "/P-1/"),
        detail(2, 1, "P-1", "C-1", "/P-1/C-1/")));
    when(rawMapper.selectList(any())).thenReturn(List.of());

    String batch = publisher.publish(product());

    assertThat(batch).isEqualTo("SUPPLEMENT_VERSION:90");
    ArgumentCaptor<BomRawHierarchy> rows = ArgumentCaptor.forClass(BomRawHierarchy.class);
    verify(rawMapper, org.mockito.Mockito.times(2)).insert(rows.capture());
    assertThat(rows.getAllValues()).allSatisfy(row -> {
      assertThat(row.getSourceType()).isEqualTo("E_DRAWING");
      assertThat(row.getBuildBatchId()).isEqualTo(batch);
      assertThat(row.getBomPurpose()).isEqualTo("主制造");
      assertThat(row.getPriceOrgCode()).isEqualTo("210");
      assertThat(row.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    });
    assertThat(rows.getAllValues()).extracting(BomRawHierarchy::getIsLeaf)
        .containsExactly(0, 1);
  }

  @Test
  void retryWithCompleteExistingBatchIsIdempotent() {
    List<QuoteBomSupplementDetail> details = List.of(
        detail(1, 0, "P-1", "P-1", "/P-1/"),
        detail(2, 1, "P-1", "C-1", "/P-1/C-1/"));
    when(detailMapper.selectList(any())).thenReturn(details);
    when(rawMapper.selectList(any())).thenReturn(List.of(new BomRawHierarchy(), new BomRawHierarchy()));

    assertThat(publisher.publish(product())).isEqualTo("SUPPLEMENT_VERSION:90");

    verify(rawMapper, never()).insert(any(BomRawHierarchy.class));
  }

  @Test
  void modelOnlyProductPublishesUnderItsStableTemporaryIdentity() {
    when(detailMapper.selectList(any())).thenReturn(List.of(
        detail(1, 0, "MODEL:MODEL-NEW", "MODEL:MODEL-NEW", "/MODEL:MODEL-NEW/"),
        detail(2, 1, "MODEL:MODEL-NEW", "C-1", "/MODEL:MODEL-NEW/C-1/")));
    when(rawMapper.selectList(any())).thenReturn(List.of());
    QuoteCollaborationProductTask product = product();
    product.setProductCode(null);
    product.setTemporaryProductKey("MODEL:MODEL-NEW");

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

  private QuoteCollaborationProductTask product() {
    QuoteCollaborationProductTask product = new QuoteCollaborationProductTask();
    product.setId(10L);
    product.setProductCode("P-1");
    product.setPriceOrgCode("210");
    product.setBusinessUnitType("COMMERCIAL");
    product.setAccountingMonth("2026-08");
    product.setSupplementVersionId(90L);
    return product;
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
}
