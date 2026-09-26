package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.bom.U9BomLineKey;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.MakePartSourceDataService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.Status;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.SubBomQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("正式 U9 BOM 到电子图库子 BOM 端口适配")
class FormalElectronicDrawingU9SubBomAdapterTest {
  private FormalBomReadService readService;
  private MakePartSourceDataService sourceDataService;
  private FormalElectronicDrawingU9SubBomAdapter adapter;

  @BeforeEach
  void setUp() {
    readService = mock(FormalBomReadService.class);
    sourceDataService = mock(MakePartSourceDataService.class);
    adapter = new FormalElectronicDrawingU9SubBomAdapter(readService, sourceDataService);
  }

  @Test
  void removesU9RootAndBuildsStableParentKeysForDescendants() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, true, List.of(
            line(1L, 0, "M", null, "/M/", "制造件", "1", "件", "COMMERCIAL"),
            line(2L, 1, "SUB", "M", "/M/SUB/", "制造件", "2", "件", "COMMERCIAL"),
            line(3L, 2, "RAW", "SUB", "/M/SUB/RAW/", "采购件", "3", "kg", "COMMERCIAL")),
        null));

    var result = adapter.query(query());

    assertThat(result.status()).isEqualTo(Status.AVAILABLE);
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.nodes().getFirst().nodeKey()).startsWith("RAW:");
    assertThat(result.nodes().getFirst().parentNodeKey()).isNull();
    assertThat(result.nodes().get(1).nodeKey()).startsWith("RAW:")
        .isNotEqualTo(result.nodes().getFirst().nodeKey());
    assertThat(result.nodes().get(1).parentNodeKey())
        .isEqualTo(result.nodes().getFirst().nodeKey());
    assertThat(result.nodes().get(1).sourceU9BomId()).isEqualTo(1003L);
  }

  @Test
  void onlyExplicitFormalNotFoundBecomesNotFound() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, false, List.of(),
        "未在 lp_bom_raw_hierarchy 找到正式 BOM"));
    assertThat(adapter.query(query()).status()).isEqualTo(Status.NOT_FOUND);

    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, false, List.of(), "未找到有效连通 BOM"));
    assertThat(adapter.query(query()).status()).isEqualTo(Status.ERROR);
  }

  @Test
  void rawHierarchyMissFallsBackToCurrentU9SourceAndKeepsRealSourceRowIds() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, false, List.of(),
        "未在 lp_bom_raw_hierarchy 找到正式 BOM"));
    when(sourceDataService.listDedupedChildren(
        eq("M"), eq(LocalDate.of(2026, 8, 1)), eq("210")))
        .thenReturn(List.of(u9Row(11L, "M", "SUB", "制造件", "2", "件", 1)));
    when(sourceDataService.listDedupedChildren(
        eq("SUB"), eq(LocalDate.of(2026, 8, 1)), eq("210")))
        .thenReturn(List.of(u9Row(12L, "SUB", "RAW", "采购件", "3", "kg", 1)));

    var result = adapter.query(query());

    assertThat(result.status()).isEqualTo(Status.AVAILABLE);
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.nodes().getFirst().sourceRawHierarchyId()).isNull();
    assertThat(result.nodes().getFirst().sourceU9BomId()).isEqualTo(11L);
    assertThat(result.nodes().getFirst().parentNodeKey()).isNull();
    assertThat(result.nodes().getFirst().nodeKey())
        .isEqualTo("U9SRC:" + U9BomLineKey.from(
            u9Row(999L, "M", "SUB", "制造件", "2", "件", 1)).occurrenceToken(null));
    assertThat(result.nodes().get(1).parentNodeKey())
        .isEqualTo(result.nodes().getFirst().nodeKey());
    assertThat(result.nodes().get(1).sourceU9BomId()).isEqualTo(12L);
  }

  @Test
  void sharedU9LineInTwoBranchesHasDistinctStableOccurrenceKeys() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, false, List.of(),
        "未在 lp_bom_raw_hierarchy 找到正式 BOM"));
    when(sourceDataService.listDedupedChildren(eq("M"), any(), eq("210")))
        .thenReturn(List.of(
            u9Row(1L, "M", "A", "制造件", "1", "件", 1),
            u9Row(2L, "M", "B", "制造件", "1", "件", 2)));
    when(sourceDataService.listDedupedChildren(eq("A"), any(), eq("210")))
        .thenReturn(List.of(u9Row(3L, "A", "X", "制造件", "1", "件", 1)));
    when(sourceDataService.listDedupedChildren(eq("B"), any(), eq("210")))
        .thenReturn(List.of(u9Row(4L, "B", "X", "制造件", "1", "件", 1)));
    when(sourceDataService.listDedupedChildren(eq("X"), any(), eq("210")))
        .thenReturn(List.of(u9Row(5L, "X", "RAW", "采购件", "1", "kg", 1)));

    var result = adapter.query(query());

    assertThat(result.status()).isEqualTo(Status.AVAILABLE);
    assertThat(result.nodes()).hasSize(6);
    assertThat(result.nodes().stream().filter(node -> "RAW".equals(node.materialCode()))
        .map(node -> node.nodeKey()).distinct()).hasSize(2);
  }

  @Test
  void multipleRootsAreNeverCollapsedToTheFirstOne() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, true, List.of(
            line(1L, 0, "M", null, "/M-A/", "制造件", "1", "件", "COMMERCIAL"),
            line(2L, 0, "M", null, "/M-B/", "制造件", "1", "件", "COMMERCIAL")), null));

    assertThat(adapter.query(query()).status()).isEqualTo(Status.MULTIPLE);
  }

  @Test
  void anyLineFromAnotherOrganizationBlocksTheWholeSubBom() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, true, List.of(
            line(1L, 0, "M", null, "/M/", "制造件", "1", "件", "COMMERCIAL"),
            line(2L, 1, "RAW", "M", "/M/RAW/", "采购件", "1", "kg", "PLATE")), null));

    assertThat(adapter.query(query()).status()).isEqualTo(Status.ORGANIZATION_MISMATCH);
  }

  @Test
  void rootWithoutChildrenAndOrphanPathAreErrors() {
    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, true, List.of(
            line(1L, 0, "M", null, "/M/", "制造件", "1", "件", "COMMERCIAL")), null));
    assertThat(adapter.query(query()).status()).isEqualTo(Status.ERROR);

    when(readService.read(any(QuoteBomReadContext.class))).thenReturn(new FormalBomReadResult(
        "M", "2026-08", null, true, List.of(
            line(1L, 0, "M", null, "/M/", "制造件", "1", "件", "COMMERCIAL"),
            line(2L, 2, "RAW", "MISSING", "/M/MISSING/RAW/", "采购件", "1", "kg", "COMMERCIAL")),
        null));
    assertThat(adapter.query(query()).status()).isEqualTo(Status.ERROR);
  }

  @Test
  void timeoutExceptionRemainsTimeoutInsteadOfNotFound() {
    when(readService.read(any(QuoteBomReadContext.class)))
        .thenThrow(new IllegalStateException("upstream timeout"));

    assertThat(adapter.query(query()).status()).isEqualTo(Status.TIMEOUT);
  }

  private SubBomQuery query() {
    return new SubBomQuery(
        "OA-1", 11L, "M", "2026-08", "210", "COMMERCIAL", "COMMERCIAL",
        null, LocalDate.of(2026, 8, 1));
  }

  private QuoteBomSourceLineDto line(
      Long rawId,
      int level,
      String code,
      String parent,
      String path,
      String nature,
      String quantity,
      String unit,
      String organization) {
    return new QuoteBomSourceLineDto(
        rawId, rawId.intValue(), level, "M", parent, code, "名称" + code,
        "规格" + code, "型号" + code, "图号" + code, nature, "CAT", "类别", unit,
        "U9", "RAW", "主制造", "V1", new BigDecimal(quantity), null, BigDecimal.ONE,
        path, rawId.intValue(), rawId, rawId + 1000, 0, "210", organization, null, null);
  }

  private BomU9Source u9Row(
      Long id,
      String parent,
      String child,
      String nature,
      String quantity,
      String unit,
      int sequence) {
    BomU9Source row = new BomU9Source();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setParentMaterialNo(parent);
    row.setChildMaterialNo(child);
    row.setChildMaterialName("名称" + child);
    row.setChildMaterialSpec("规格" + child);
    row.setShapeAttr(nature);
    row.setProductionCategory(nature);
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setQtyPerParent(new BigDecimal(quantity));
    row.setParentBaseQty(BigDecimal.ONE);
    row.setStockUnit(unit);
    row.setChildSeq(sequence);
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(LocalDate.of(2099, 12, 31));
    return row;
  }
}
