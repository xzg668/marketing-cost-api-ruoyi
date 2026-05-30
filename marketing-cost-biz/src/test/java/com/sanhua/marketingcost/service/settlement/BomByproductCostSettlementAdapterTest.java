package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomByproductCostSettlementAdapter · 副产品附加行 DB 适配")
class BomByproductCostSettlementAdapterTest {

  @Test
  @DisplayName("读取当前有效 U9 副产品，并只带回制造件下层原材料命中的废料映射")
  void readsCurrentByproductsAndMatchingScrapRefs() {
    U9BomByproductMasterMapper byproductMapper = mock(U9BomByproductMasterMapper.class);
    MaterialScrapRefMapper scrapRefMapper = mock(MaterialScrapRefMapper.class);
    BomByproductSettlementAdapterImpl adapter =
        new BomByproductSettlementAdapterImpl(byproductMapper, scrapRefMapper);
    when(byproductMapper.selectList(any())).thenReturn(List.of(byproductRow()));
    when(scrapRefMapper.selectList(any())).thenReturn(List.of(scrapRefRow()));

    BomByproductSettlementReadResult result = adapter.read(
        List.of(
            node("P", null, 0, "/P/", "制造件", 0),
            node("MAKE-1", "P", 1, "/P/MAKE-1/", "制造件", 0),
            node("RAW-1", "MAKE-1", 2, "/P/MAKE-1/RAW-1/", "采购件", 1)),
        LocalDate.of(2026, 5, 29),
        "COMMERCIAL",
        "主制造");

    assertThat(result.warnings()).isEmpty();
    assertThat(result.byproducts()).extracting(BomSettlementByproduct::byproductMaterialCode)
        .containsExactly("SCRAP-1");
    assertThat(result.scrapRefs()).extracting(BomSettlementScrapRef::materialCode)
        .containsExactly("RAW-1");
  }

  private static BomSettlementNode node(
      String materialCode,
      String parentCode,
      int level,
      String path,
      String shapeAttr,
      int isLeaf) {
    return new BomSettlementNode(
        (long) Math.abs(path.hashCode()),
        "P",
        parentCode,
        materialCode,
        level,
        path,
        BigDecimal.ONE,
        BigDecimal.ONE,
        materialCode,
        "SPEC",
        shapeAttr,
        shapeAttr,
        null,
        "18",
        "主分类",
        null,
        "主制造",
        "V1",
        1,
        isLeaf,
        LocalDate.of(2026, 1, 1),
        null,
        LocalDate.of(2026, 1, 1),
        "COMMERCIAL",
        null);
  }

  private static U9BomByproductMaster byproductRow() {
    U9BomByproductMaster row = new U9BomByproductMaster();
    row.setId(1L);
    row.setParentMaterialNo("MAKE-1");
    row.setBomPurpose("主制造");
    row.setByproductMaterialNo("SCRAP-1");
    row.setByproductMaterialName("副产品一");
    row.setOutputQty(BigDecimal.ONE);
    row.setUnit("KG");
    row.setVersionNo("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(LocalDate.of(2099, 12, 31));
    return row;
  }

  private static MaterialScrapRef scrapRefRow() {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setMaterialCode("RAW-1");
    row.setScrapCode("SCRAP-1");
    row.setBusinessUnitType("COMMERCIAL");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(null);
    return row;
  }
}
