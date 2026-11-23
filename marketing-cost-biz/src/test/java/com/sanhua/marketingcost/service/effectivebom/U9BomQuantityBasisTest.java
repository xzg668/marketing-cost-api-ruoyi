package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class U9BomQuantityBasisTest {
  private static final LocalDate END = LocalDate.of(9999, 12, 31);
  private final BomU9SourceMapper sources = mock(BomU9SourceMapper.class);
  private final BomRawHierarchyMapper hierarchy = mock(BomRawHierarchyMapper.class);
  private final PackageComponentIdentifyService packages = mock(PackageComponentIdentifyService.class);
  private final MonthlyBomSnapshotDetailService snapshots = mock(MonthlyBomSnapshotDetailService.class);
  private final U9BomQuantityBasis quantities = new U9BomQuantityBasis(sources, hierarchy, packages);

  @Test
  void foldsEachSourceOccurrenceBeforeAccumulationAndPreservesPackageAndOtherParents() {
    var root = node(1, "P", "P", 0, "/P/", "1", "制造件");
    var u1 = row(2, "206600016", "P", 1, "/P/U@1/", "1", "委外");
    var u2 = row(3, "206600016", "P", 1, "/P/U@2/", "1", "委外");
    var steel1 = row(4, "301240404", "206600016", 2, "/P/U@1/S@10/", "1", "采购件");
    var steel2 = row(5, "301240404", "206600016", 2, "/P/U@2/S@10/", "1", "采购件");
    steel1.setSortSeq(10);
    steel2.setSortSeq(10);
    var film = row(6, "202051362", "P", 1, "/P/F/", "1", "制造件");
    var filmSteel = row(7, "301240404", "202051362", 2, "/P/F/S/", "0.0014", "采购件");
    var pack = row(8, "9830000025720", "P", 1, "/P/Pack/", "1", "虚拟");
    var packChild = row(9, "250012046", "9830000025720", 2, "/P/Pack/Box/", "3", "采购件");
    var otherVersion = source(steel1, "25000");
    otherVersion.setBomVersion("OTHER");
    when(sources.selectParentBases(eq("210"), anyList()))
        .thenReturn(List.of(source(steel1, "19000"), source(pack, "24"), source(packChild, "6"), otherVersion));
    when(packages.batchIdentify(anySet(), eq("COMMERCIAL")))
        .thenReturn(Map.of("9830000025720", true));
    var inputs = List.of(root, u1, u2, steel1, steel2, film, filmSteel, pack, packChild);
    var normalized = quantities.normalize(inputs);
    var effective = builder().build(request(normalized,
        shape("P", QuoteMaterialShape.MANUFACTURE),
        shape("206600016", QuoteMaterialShape.OUTSOURCE),
        shape("301240404", QuoteMaterialShape.PURCHASE),
        shape("202051362", QuoteMaterialShape.MANUFACTURE),
        shape("9830000025720", QuoteMaterialShape.VIRTUAL),
        shape("250012046", QuoteMaterialShape.PURCHASE)));

    assertThat(effective.blockIssues()).isEmpty();
    var steelQuantity = effective.nodes().stream().filter(n -> n.sourceNodePath().contains("/U@")
            && n.materialCode().equals("301240404")).map(EffectiveBomNodeDraft::qtyPerTop)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(steelQuantity).isEqualByComparingTo("0.0001052631578948");
    assertThat(steelQuantity.multiply(new BigDecimal("224.19")).setScale(6, RoundingMode.HALF_UP))
        .isEqualByComparingTo("0.023599");
    assertThat(normalized.get(6)).isSameAs(filmSteel);
    assertThat(normalized.get(7)).isSameAs(pack);
    assertThat(normalized.get(8)).isSameAs(packChild);
    assertThat(steel1.getQtyPerParent()).isEqualByComparingTo("1");
    assertThat(quantities.normalize(normalized)).isSameAs(normalized);
    verify(sources, times(2)).selectParentBases(eq("210"), anyList());
  }

  @Test
  void revisionDetectsAffectedSavedTreeAndStaysStableAfterRebuilding() {
    var raw = row(4, "301240404", "206600016", 2, "/P/U/S/", "1", "采购件");
    var effective = new QuoteEffectiveBomNode();
    effective.setSourceHierarchyId(raw.getId());
    effective.setSourceBomType("U9");
    effective.setSourceBomBatchId(raw.getBuildBatchId());
    effective.setSourceNodePath(raw.getPath());
    effective.setOriginMonthlySnapshotId(259L);
    effective.setNodeLevel(2);
    effective.setNodePath(raw.getPath());
    effective.setMaterialCode(raw.getMaterialCode());
    effective.setPriceOrgCode("210");
    effective.setQtyPerTop(BigDecimal.ONE);
    when(snapshots.load(259L)).thenReturn(List.of(raw));
    var refreshed = source(raw, "19000");
    refreshed.setImportBatchId("IMPORT-2");
    when(sources.selectParentBases("210", List.of("206600016")))
        .thenReturn(List.of(source(raw, "19000")), List.of(refreshed), List.of());

    var oldRevision = quantities.revision(List.of(effective), snapshots::load);
    assertThat(oldRevision).startsWith("U9_PARENT_BASE_V1:").contains("/P/U/S/");
    effective.setQtyPerTop(new BigDecimal("0.0000526315789474"));
    assertThat(quantities.revision(List.of(effective), snapshots::load)).isEqualTo(oldRevision);
    assertThat(quantities.revision(List.of(effective), snapshots::load)).isNull();
    verifyNoInteractions(hierarchy);
  }

  @Test
  void newMonthlyRowsKeepTheirBasisWhenTheSourceDisappearsOrChanges() {
    var raw = row(4, "301240404", "206600016", 2, "/P/U/S/", "1", "采购件");
    when(sources.selectParentBases("210", List.of("206600016")))
        .thenReturn(List.of(source(raw, "19000")), List.of(source(raw, "25000")));
    quantities.capture(List.of(raw));
    assertThat(raw.getFrozenU9ParentBaseQty()).isEqualByComparingTo("19000");
    assertThat(quantities.normalize(List.of(raw)).getFirst().getQtyPerParent())
        .isEqualByComparingTo("0.0000526315789474");
    assertThat(quantities.normalize(List.of(raw)).getFirst().getQtyPerParent())
        .isEqualByComparingTo("0.0000526315789474");
    verify(sources).selectParentBases("210", List.of("206600016"));
  }

  private static BomRawHierarchy row(long id, String child, String parent, int depth,
      String path, String usage, String shape) {
    var row = node(id, child, parent, depth, path, usage, shape);
    row.setEffectiveTo(END);
    return row;
  }

  private static BomU9Source source(BomRawHierarchy row, String base) {
    var source = new BomU9Source();
    source.setPriceOrgCode(row.getPriceOrgCode());
    source.setParentMaterialNo(row.getParentCode());
    source.setChildMaterialNo(row.getMaterialCode());
    source.setBomPurpose(row.getBomPurpose());
    source.setBomVersion(row.getBomVersion());
    source.setChildSeq(row.getSortSeq());
    source.setEffectiveFrom(row.getEffectiveFrom());
    source.setEffectiveTo(row.getEffectiveTo());
    source.setQtyPerParent(row.getQtyPerParent());
    source.setParentBaseQty(new BigDecimal(base));
    return source;
  }
}
