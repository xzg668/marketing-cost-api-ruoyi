package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("板换采购件跨商用制造 BOM 展开")
class PlateCommercialMakeBomExpansionServiceTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomRawHierarchy.class);
  }

  @Test
  @DisplayName("板换采购件在商用为制造件时按每个出现路径嫁接商用子树并累计用量")
  void expandsCommercialMakeSubtreeForEveryOccurrence() {
    Fixture fixture = fixture();
    List<BomRawHierarchy> baseRows =
        List.of(
            row(1L, "P", "P", "P", 0, "/P/", "制造件", "1"),
            row(2L, "P", "P", "C", 1, "/P/C@10/", "采购件", "2"),
            row(3L, "P", "P", "X", 1, "/P/X@20/", "制造件", "1"),
            row(4L, "P", "X", "C", 2, "/P/X@20/C@10/", "采购件", "3"));
    BomRawHierarchy commercialChild =
        row(20L, "C", "C", "R", 1, "/C/R@10/", "采购件", "0.5");
    commercialChild.setQtyPerParent(new BigDecimal("0.5"));
    when(fixture.bomMapper().selectList(any(Wrapper.class)))
        .thenReturn(List.of(commercialChild));
    stubMasters(
        fixture.masterMapper(),
        List.of(master("P", "制造件"), master("C", "采购件"), master("X", "制造件")),
        List.of(master("C", "制造件"), master("R", "采购件")));

    PlateCommercialMakeBomExpansionService.ExpansionResult result =
        fixture.service().expand(
            baseRows,
            "P",
            LocalDate.of(2026, 7, 10),
            "主制造",
            "U9",
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.gaps()).isEmpty();
    assertThat(result.rows()).extracting(BomRawHierarchy::getPath)
        .containsExactlyInAnyOrder(
            "/P/", "/P/C@10/", "/P/C@10/R@10/", "/P/X@20/", "/P/X@20/C@10/", "/P/X@20/C@10/R@10/");
    assertThat(rowAt(result.rows(), "/P/C@10/").getShapeAttr()).isEqualTo("制造件");
    assertThat(rowAt(result.rows(), "/P/C@10/").getPriceOrgCode()).isEqualTo("210");
    assertThat(rowAt(result.rows(), "/P/C@10/R@10/").getQtyPerTop())
        .isEqualByComparingTo("1.0");
    assertThat(rowAt(result.rows(), "/P/X@20/C@10/R@10/").getQtyPerTop())
        .isEqualByComparingTo("1.5");
    assertThat(rowAt(result.rows(), "/P/X@20/C@10/R@10/").getLevel()).isEqualTo(3);
    assertCommercialHierarchyQuery(fixture.bomMapper());
  }

  @Test
  @DisplayName("商用仍为采购件时保持板换采购叶子且不查询商用 BOM")
  void keepsPurchaseLeafWhenCommercialIsAlsoPurchase() {
    Fixture fixture = fixture();
    List<BomRawHierarchy> baseRows =
        List.of(
            row(1L, "P", "P", "P", 0, "/P/", "制造件", "1"),
            row(2L, "P", "P", "C", 1, "/P/C@10/", "采购件", "2"));
    stubMasters(
        fixture.masterMapper(),
        List.of(master("P", "制造件"), master("C", "采购件")),
        List.of(master("C", "采购件")));

    PlateCommercialMakeBomExpansionService.ExpansionResult result =
        fixture.service().expand(
            baseRows,
            "P",
            LocalDate.of(2026, 7, 10),
            "主制造",
            "U9",
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.gaps()).isEmpty();
    assertThat(result.rows()).hasSize(2);
    assertThat(rowAt(result.rows(), "/P/C@10/").getShapeAttr()).isEqualTo("采购件");
    assertThat(rowAt(result.rows(), "/P/C@10/").getPriceOrgCode()).isEqualTo("220");
    verify(fixture.bomMapper(), never()).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("板换自身是制造件时不被商用采购属性截断")
  void doesNotOverridePlateMakeNode() {
    Fixture fixture = fixture();
    List<BomRawHierarchy> baseRows =
        List.of(
            row(1L, "P", "P", "P", 0, "/P/", "制造件", "1"),
            row(2L, "P", "P", "M", 1, "/P/M@10/", "制造件", "1"),
            row(3L, "P", "M", "R", 2, "/P/M@10/R@10/", "采购件", "0.2"));
    stubMasters(
        fixture.masterMapper(),
        List.of(master("P", "制造件"), master("M", "制造件"), master("R", "采购件")),
        List.of(master("M", "采购件"), master("R", "采购件")));

    PlateCommercialMakeBomExpansionService.ExpansionResult result =
        fixture.service().expand(
            baseRows,
            "P",
            LocalDate.of(2026, 7, 10),
            "主制造",
            "U9",
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.rows()).extracting(BomRawHierarchy::getPath)
        .containsExactlyInAnyOrder("/P/", "/P/M@10/", "/P/M@10/R@10/");
    assertThat(rowAt(result.rows(), "/P/M@10/").getShapeAttr()).isEqualTo("制造件");
    verify(fixture.bomMapper(), never()).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("商用为制造件但无有效主制造 BOM 时返回明确缺口")
  void reportsGapWhenCommercialMakeBomIsMissing() {
    Fixture fixture = fixture();
    List<BomRawHierarchy> baseRows =
        List.of(
            row(1L, "P", "P", "P", 0, "/P/", "制造件", "1"),
            row(2L, "P", "P", "C", 1, "/P/C@10/", "采购件", "1"));
    stubMasters(
        fixture.masterMapper(),
        List.of(master("P", "制造件"), master("C", "采购件")),
        List.of(master("C", "制造件")));
    when(fixture.bomMapper().selectList(any(Wrapper.class))).thenReturn(List.of());

    PlateCommercialMakeBomExpansionService.ExpansionResult result =
        fixture.service().expand(
            baseRows,
            "P",
            LocalDate.of(2026, 7, 10),
            null,
            "U9",
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.hasGaps()).isTrue();
    assertThat(result.gaps().get(0)).contains("C", "商用组织中为制造件", "主制造 BOM");
  }

  @Test
  @DisplayName("商用制造子树回指板换祖先时阻止跨组织环")
  void reportsCrossOrganizationCycle() {
    Fixture fixture = fixture();
    List<BomRawHierarchy> baseRows =
        List.of(
            row(1L, "P", "P", "P", 0, "/P/", "制造件", "1"),
            row(2L, "P", "P", "C", 1, "/P/C@10/", "采购件", "1"));
    BomRawHierarchy cycle = row(20L, "C", "C", "P", 1, "/C/P@10/", "采购件", "1");
    when(fixture.bomMapper().selectList(any(Wrapper.class))).thenReturn(List.of(cycle));
    stubMasters(
        fixture.masterMapper(),
        List.of(master("P", "制造件"), master("C", "采购件")),
        List.of(master("C", "制造件"), master("P", "采购件")));

    PlateCommercialMakeBomExpansionService.ExpansionResult result =
        fixture.service().expand(
            baseRows,
            "P",
            LocalDate.of(2026, 7, 10),
            "主制造",
            "U9",
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.hasGaps()).isTrue();
    assertThat(result.gaps().get(0)).contains("形成环", "P");
  }

  @Test
  @DisplayName("非板换组织完全不触发跨组织逻辑")
  void ignoresCommercialTopLevelBom() {
    Fixture fixture = fixture();
    List<BomRawHierarchy> rows =
        List.of(row(1L, "P", "P", "C", 1, "/P/C@10/", "采购件", "1"));

    PlateCommercialMakeBomExpansionService.ExpansionResult result =
        fixture.service().expand(
            rows,
            "P",
            LocalDate.of(2026, 7, 10),
            "主制造",
            "U9",
            new QuoteDataOrganization("210", "COMMERCIAL"));

    assertThat(result.rows()).hasSize(1);
    verify(fixture.masterMapper(), never())
        .selectByLatestBatchAndCodes(any(Collection.class), isNull(), any());
    verify(fixture.bomMapper(), never()).selectList(any(Wrapper.class));
  }

  private Fixture fixture() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    return new Fixture(
        bomMapper,
        masterMapper,
        new PlateCommercialMakeBomExpansionService(bomMapper, masterMapper));
  }

  private void stubMasters(
      MaterialMasterRawMapper mapper,
      List<MaterialMasterRaw> plate,
      List<MaterialMasterRaw> commercial) {
    when(mapper.selectByLatestBatchAndCodes(any(Collection.class), isNull(), eq("PLATE")))
        .thenReturn(plate);
    when(mapper.selectByLatestBatchAndCodes(any(Collection.class), isNull(), eq("COMMERCIAL")))
        .thenReturn(commercial);
  }

  private void assertCommercialHierarchyQuery(BomRawHierarchyMapper mapper) {
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    @SuppressWarnings("rawtypes")
    AbstractWrapper wrapper = (AbstractWrapper) captor.getValue();
    assertThat(wrapper.getSqlSegment()).contains("price_org_code", "top_product_code", "bom_purpose");
    assertThat(wrapper.getParamNameValuePairs())
        .containsValue("210")
        .containsValue("C")
        .containsValue("主制造")
        .containsValue("U9")
        .containsValue(LocalDate.of(2026, 7, 10));
  }

  private BomRawHierarchy row(
      Long id,
      String top,
      String parent,
      String code,
      int level,
      String path,
      String shape,
      String qtyPerTop) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(code);
    row.setMaterialName("NAME-" + code);
    row.setMaterialSpec("SPEC-" + code);
    row.setShapeAttr(shape);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(10);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(new BigDecimal(qtyPerTop));
    row.setIsLeaf("采购件".equals(shape) ? 1 : 0);
    row.setBomPurpose("主制造");
    row.setBomVersion("F001");
    row.setSourceType("U9");
    row.setPriceOrgCode("P".equals(top) ? "220" : "210");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }

  private MaterialMasterRaw master(String code, String shape) {
    MaterialMasterRaw master = new MaterialMasterRaw();
    master.setMaterialCode(code);
    master.setMaterialName("MASTER-" + code);
    master.setMaterialSpec("MASTER-SPEC-" + code);
    master.setShapeAttr(shape);
    master.setMainCategoryCode("MC-" + code);
    master.setMainCategoryName("MAIN-" + code);
    master.setActiveFlag(1);
    return master;
  }

  private BomRawHierarchy rowAt(List<BomRawHierarchy> rows, String path) {
    return rows.stream().filter(row -> path.equals(row.getPath())).findFirst().orElseThrow();
  }

  private record Fixture(
      BomRawHierarchyMapper bomMapper,
      MaterialMasterRawMapper masterMapper,
      PlateCommercialMakeBomExpansionService service) {}
}
