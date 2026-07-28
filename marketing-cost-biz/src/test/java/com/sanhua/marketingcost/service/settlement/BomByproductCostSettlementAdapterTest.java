package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("BomByproductCostSettlementAdapter · 副产品附加行 DB 适配")
class BomByproductCostSettlementAdapterTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        U9BomByproductMaster.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        MaterialScrapRef.class);
  }

  @Test
  @DisplayName("读取副产品废料映射时兼容起止生效日期都为空")
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
        "210",
        "COMMERCIAL",
        "主制造");

    assertThat(result.warnings()).isEmpty();
    assertThat(result.byproducts()).extracting(BomSettlementByproduct::byproductMaterialCode)
        .containsExactly("SCRAP-1");
    assertThat(result.byproducts()).extracting(BomSettlementByproduct::businessUnitType)
        .containsExactly((String) null);
    assertThat(result.scrapRefs()).extracting(BomSettlementScrapRef::materialCode)
        .containsExactly("RAW-1");
    assertThat(result.scrapRefs()).singleElement().satisfies(ref -> {
      assertThat(ref.effectiveFrom()).isNull();
      assertThat(ref.effectiveTo()).isNull();
    });
    ArgumentCaptor<Wrapper<U9BomByproductMaster>> queryCaptor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(byproductMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getCustomSqlSegment()).contains("price_org_code");
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getParamNameValuePairs().values())
        .contains("210");

    ArgumentCaptor<Wrapper<MaterialScrapRef>> scrapRefQueryCaptor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(scrapRefMapper).selectList(scrapRefQueryCaptor.capture());
    String scrapRefSql = scrapRefQueryCaptor.getValue().getCustomSqlSegment();
    assertThat(scrapRefSql)
        .contains("effective_from <=")
        .contains("OR effective_from IS NULL")
        .contains("effective_to >=")
        .contains("OR effective_to IS NULL");
  }

  @Test
  @DisplayName("板换副产品读取使用 220，不能落到商用副产品")
  void readsPlateByproductsWithPlateOrg() {
    U9BomByproductMasterMapper byproductMapper = mock(U9BomByproductMasterMapper.class);
    MaterialScrapRefMapper scrapRefMapper = mock(MaterialScrapRefMapper.class);
    BomByproductSettlementAdapterImpl adapter =
        new BomByproductSettlementAdapterImpl(byproductMapper, scrapRefMapper);
    U9BomByproductMaster plate = byproductRow();
    plate.setPriceOrgCode("220");
    plate.setOutputQty(new BigDecimal("2.50000000"));
    when(byproductMapper.selectList(any())).thenReturn(List.of(plate));
    when(scrapRefMapper.selectList(any())).thenReturn(List.of());

    BomByproductSettlementReadResult result = adapter.read(
        List.of(node("MAKE-1", "P", 1, "/P/MAKE-1/", "制造件", 0)),
        LocalDate.of(2026, 5, 29),
        "220",
        "PLATE",
        "主制造");

    assertThat(result.byproducts()).singleElement().satisfies(byproduct -> {
      assertThat(byproduct.outputQty()).isEqualByComparingTo("2.50000000");
      assertThat(byproduct.businessUnitType()).isNull();
    });
    ArgumentCaptor<Wrapper<U9BomByproductMaster>> queryCaptor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(byproductMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getCustomSqlSegment()).contains("price_org_code");
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getParamNameValuePairs().values())
        .contains("220");
  }

  @Test
  @DisplayName("存在制造件时缺少 priceOrgCode 直接失败")
  void failsWhenPriceOrgCodeMissing() {
    BomByproductSettlementAdapterImpl adapter =
        new BomByproductSettlementAdapterImpl(
            mock(U9BomByproductMasterMapper.class), mock(MaterialScrapRefMapper.class));

    assertThatThrownBy(() -> adapter.read(
            List.of(node("MAKE-1", "P", 1, "/P/MAKE-1/", "制造件", 0)),
            LocalDate.of(2026, 5, 29),
            null,
            "COMMERCIAL",
            "主制造"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceOrgCode");
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
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        null);
  }

  private static U9BomByproductMaster byproductRow() {
    U9BomByproductMaster row = new U9BomByproductMaster();
    row.setId(1L);
    row.setPriceOrgCode("210");
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
    row.setEffectiveFrom(null);
    row.setEffectiveTo(null);
    return row;
  }
}
