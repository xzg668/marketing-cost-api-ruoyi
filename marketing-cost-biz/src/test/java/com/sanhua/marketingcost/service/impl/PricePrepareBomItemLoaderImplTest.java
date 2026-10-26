package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PricePrepareBomItemLoaderImplTest {
  private BomCostingRowMapper mapper;
  private PricePrepareBomItemLoaderImpl loader;

  @BeforeEach void setUp() {
    mapper = mock(BomCostingRowMapper.class);
    loader = new PricePrepareBomItemLoaderImpl(mapper);
  }

  @Test void quoteScopeNeverAddsGlobalRawPackagingParents() {
    var child = row("CHILD", "210"); child.setParentCode("PACK");
    when(mapper.selectQuoteCostingSnapshot("OA-QUOTE", 17L, "TOP", "2026-10")).thenReturn(List.of(child));
    var rows = loader.loadByQuoteItem(" OA-QUOTE ", 17L, " TOP ", " 2026-10 ");
    assertThat(rows).containsExactly(child);
    assertThat(rows.getFirst().getBuildBatchId()).isEqualTo("CURRENT-QUOTE-BUILD");
    assertThatThrownBy(() -> rows.add(row("PACK", "210"))).isInstanceOf(UnsupportedOperationException.class);
    verify(mapper).selectQuoteCostingSnapshot("OA-QUOTE", 17L, "TOP", "2026-10");
    verifyNoMoreInteractions(mapper);
  }

  @Test void keepsBothSavedOrganizationAndOccurrenceIdentities() {
    var commercial = row("SHARED", "210"); var plate = row("SHARED", "220");
    when(mapper.selectQuoteCostingSnapshot("OA", 17L, "TOP", "2026-10")).thenReturn(List.of(commercial, plate));
    assertThat(loader.loadByQuoteItem("OA", 17L, "TOP", "2026-10"))
        .containsExactly(commercial, plate)
        .extracting(BomCostingRow::getPriceOrgCode).containsExactly("210", "220");
  }

  @Test void incompleteProductScopeDoesNotQueryOrInferAnotherProduct() {
    assertThat(loader.loadByQuoteItem(" ", 17L, "TOP", "2026-10")).isEmpty();
    assertThat(loader.loadByQuoteItem("OA", null, "TOP", "2026-10")).isEmpty();
    assertThat(loader.loadByQuoteItem("OA", 0L, "TOP", "2026-10")).isEmpty();
    assertThat(loader.loadByQuoteItem("OA", 17L, " ", "2026-10")).isEmpty();
    assertThat(loader.loadByQuoteItem("OA", 17L, "TOP", null)).isEmpty();
    verifyNoInteractions(mapper);
  }

  @Test void missingSnapshotDoesNotFallBackToRawHierarchy() {
    when(mapper.selectQuoteCostingSnapshot("OA", 17L, "TOP", "2026-10")).thenReturn(null, List.of());
    assertThat(loader.loadByQuoteItem("OA", 17L, "TOP", "2026-10")).isEmpty();
    assertThat(loader.loadByQuoteItem("OA", 17L, "TOP", "2026-10")).isEmpty();
    verify(mapper, times(2)).selectQuoteCostingSnapshot("OA", 17L, "TOP", "2026-10");
    verifyNoMoreInteractions(mapper);
  }

  @Test void databaseFailureIsNotPresentedAsMissingStructure() {
    var failure = new org.springframework.dao.DataAccessResourceFailureException("unavailable snapshot");
    when(mapper.selectQuoteCostingSnapshot("OA", 17L, "TOP", "2026-10")).thenThrow(failure);
    assertThatThrownBy(() -> loader.loadByQuoteItem("OA", 17L, "TOP", "2026-10")).isSameAs(failure);
  }

  private BomCostingRow row(String material, String org) {
    var row = new BomCostingRow(); row.setOaFormItemId(17L); row.setBuildBatchId("CURRENT-QUOTE-BUILD");
    row.setMaterialCode(material); row.setPriceOrgCode(org); row.setPath("TOP/" + org + "/" + material);
    row.setMaterialOrganizationCode("220".equals(org) ? "PLATE" : "COMMERCIAL");
    row.setQtyPerTop(new BigDecimal("2.5")); return row;
  }
}
