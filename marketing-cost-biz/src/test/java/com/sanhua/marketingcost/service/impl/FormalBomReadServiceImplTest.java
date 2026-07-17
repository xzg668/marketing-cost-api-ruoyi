package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("正式 BOM 只读服务")
class FormalBomReadServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomRawHierarchy.class);
  }

  @Test
  @DisplayName("按产品料号、期间、BOM 用途读取 lp_bom_raw_hierarchy 并补齐主档字段")
  void readsFormalBomRows() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    when(bomMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                rawRow(2L, 1, "P-001", "C-002", "210"),
                rawRow(1L, 0, "P-001", "P-001", "210")));
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(master("P-001"), master("C-002")));

    FormalBomReadServiceImpl service = new FormalBomReadServiceImpl(bomMapper, rawMapper);
    FormalBomReadResult result =
        service.read(
            " P-001 ",
            "2026-05",
            "主制造",
            LocalDate.parse("2026-05-16"),
            new QuoteDataOrganization("210", "COMMERCIAL"));

    assertThat(result.found()).isTrue();
    assertThat(result.productCode()).isEqualTo("P-001");
    assertThat(result.periodMonth()).isEqualTo("2026-05");
    assertThat(result.lines()).extracting(QuoteBomSourceLineDto::materialCode)
        .containsExactly("P-001", "C-002");
    assertThat(result.lines().get(0).materialModel()).isEqualTo("MODEL-P-001");
    assertThat(result.lines().get(1).drawingNo()).isEqualTo("DRAW-C-002");
    assertThat(result.lines().get(1).sourceRawHierarchyId()).isEqualTo(2L);
    assertThat(result.lines()).allSatisfy(line -> {
      assertThat(line.priceOrgCode()).isEqualTo("210");
      assertThat(line.materialOrganizationCode()).isEqualTo("COMMERCIAL");
    });
    assertRawHierarchyQueryUsesPriceOrg(bomMapper, "210");
    verify(rawMapper).selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL"));
  }

  @Test
  @DisplayName("板换组织只读取 220 BOM，并按 PLATE 主档补字段")
  void readsPlateBomRowsAndPlateMaster() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    when(bomMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                rawRow(12L, 1, "P-PLATE", "C-PLATE", "220"),
                rawRow(11L, 0, "P-PLATE", "P-PLATE", "220")));
    MaterialMasterRaw plateTop = master("P-PLATE");
    plateTop.setMaterialModel("PLATE-MODEL-P-PLATE");
    MaterialMasterRaw plateChild = master("C-PLATE");
    plateChild.setDrawingNo("PLATE-DRAW-C-PLATE");
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(plateTop, plateChild));

    FormalBomReadServiceImpl service = new FormalBomReadServiceImpl(bomMapper, rawMapper);
    FormalBomReadResult result =
        service.read(
            "P-PLATE",
            "2026-05",
            "主制造",
            LocalDate.parse("2026-05-16"),
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.found()).isTrue();
    assertThat(result.lines()).extracting(QuoteBomSourceLineDto::materialCode)
        .containsExactly("P-PLATE", "C-PLATE");
    assertThat(result.lines().get(0).materialModel()).isEqualTo("PLATE-MODEL-P-PLATE");
    assertThat(result.lines().get(1).drawingNo()).isEqualTo("PLATE-DRAW-C-PLATE");
    assertThat(result.lines()).allSatisfy(line -> {
      assertThat(line.priceOrgCode()).isEqualTo("220");
      assertThat(line.materialOrganizationCode()).isEqualTo("PLATE");
    });
    assertRawHierarchyQueryUsesPriceOrg(bomMapper, "220");
    verify(rawMapper).selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE"));
  }

  @Test
  @DisplayName("当前组织无 BOM 时返回缺口，不回退另一个组织")
  void returnsGapWithoutCrossOrganizationFallback() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    FormalBomReadServiceImpl service = new FormalBomReadServiceImpl(bomMapper, rawMapper);
    FormalBomReadResult result =
        service.read(
            "P-ONLY-210",
            "2026-05",
            "主制造",
            LocalDate.parse("2026-05-16"),
            new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.found()).isFalse();
    assertThat(result.gapMessage()).contains("lp_bom_raw_hierarchy");
    assertRawHierarchyQueryUsesPriceOrg(bomMapper, "220");
    verifyNoInteractions(rawMapper);
  }

  @Test
  @DisplayName("板换采购件在商用为制造件时正式 BOM 嫁接商用子件并保留行级组织")
  void expandsCommercialMakeBomInsidePlateFormalBom() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchy plateTop = rawRow(11L, 0, "P-PLATE", "P-PLATE", "220");
    plateTop.setShapeAttr("制造件");
    BomRawHierarchy platePurchase = rawRow(12L, 1, "P-PLATE", "C-CROSS", "220");
    platePurchase.setShapeAttr("采购件");
    platePurchase.setQtyPerTop(new BigDecimal("2"));
    BomRawHierarchy commercialChild = rawRow(21L, 1, "C-CROSS", "R-COMM", "210");
    commercialChild.setShapeAttr("采购件");
    commercialChild.setQtyPerParent(new BigDecimal("0.5"));
    commercialChild.setQtyPerTop(new BigDecimal("0.5"));
    commercialChild.setSourceType("U9");
    when(bomMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(plateTop, platePurchase), List.of(commercialChild));

    MaterialMasterRaw plateTopMaster = master("P-PLATE");
    plateTopMaster.setShapeAttr("制造件");
    MaterialMasterRaw platePurchaseMaster = master("C-CROSS");
    platePurchaseMaster.setShapeAttr("采购件");
    MaterialMasterRaw commercialParentMaster = master("C-CROSS");
    commercialParentMaster.setShapeAttr("制造件");
    MaterialMasterRaw commercialChildMaster = master("R-COMM");
    commercialChildMaster.setShapeAttr("采购件");
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(plateTopMaster, platePurchaseMaster));
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(commercialParentMaster, commercialChildMaster));

    FormalBomReadResult result =
        new FormalBomReadServiceImpl(bomMapper, rawMapper)
            .read(
                "P-PLATE",
                "2026-07",
                "主制造",
                LocalDate.of(2026, 7, 10),
                new QuoteDataOrganization("220", "PLATE"));

    assertThat(result.found()).isTrue();
    assertThat(result.lines()).extracting(QuoteBomSourceLineDto::materialCode)
        .containsExactly("P-PLATE", "C-CROSS", "R-COMM");
    QuoteBomSourceLineDto crossParent = result.lines().get(1);
    QuoteBomSourceLineDto crossChild = result.lines().get(2);
    assertThat(crossParent.shapeAttr()).isEqualTo("制造件");
    assertThat(crossParent.priceOrgCode()).isEqualTo("210");
    assertThat(crossParent.materialOrganizationCode()).isEqualTo("COMMERCIAL");
    assertThat(crossChild.path()).isEqualTo("/P-PLATE/C-CROSS/R-COMM/");
    assertThat(crossChild.qtyPerTop()).isEqualByComparingTo("1.0");
    assertThat(crossChild.priceOrgCode()).isEqualTo("210");
    assertThat(crossChild.materialOrganizationCode()).isEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("正式 BOM 无数据时返回明确缺口")
  void returnsGapWhenFormalBomMissing() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    FormalBomReadServiceImpl service = new FormalBomReadServiceImpl(bomMapper, rawMapper);
    FormalBomReadResult result =
        service.read(
            "P-MISS",
            "2026-05",
            "主制造",
            LocalDate.parse("2026-05-16"),
            new QuoteDataOrganization("210", "COMMERCIAL"));

    assertThat(result.found()).isFalse();
    assertThat(result.lines()).isEmpty();
    assertThat(result.gapMessage()).contains("lp_bom_raw_hierarchy");
    assertRawHierarchyQueryUsesPriceOrg(bomMapper, "210");
  }

  @Test
  @DisplayName("旧三参数入口不允许用于正式 BOM 读取")
  void legacyReadWithoutOrganizationFails() {
    FormalBomReadServiceImpl service =
        new FormalBomReadServiceImpl(mock(BomRawHierarchyMapper.class), mock(MaterialMasterRawMapper.class));

    assertThatThrownBy(() -> service.read("P-001", "2026-05", "主制造"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入报价组织");
  }

  private BomRawHierarchy rawRow(Long id, int level, String topCode, String materialCode) {
    return rawRow(id, level, topCode, materialCode, "210");
  }

  private BomRawHierarchy rawRow(
      Long id, int level, String topCode, String materialCode, String priceOrgCode) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode(priceOrgCode);
    row.setTopProductCode(topCode);
    row.setParentCode(level == 0 ? topCode : topCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("BOM-" + materialCode);
    row.setMaterialSpec("SPEC-" + materialCode);
    row.setLevel(level);
    row.setPath(level == 0 ? "/" + topCode + "/" : "/" + topCode + "/" + materialCode + "/");
    row.setSortSeq(level);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.parse("2026-01-01"));
    return row;
  }

  private MaterialMasterRaw master(String code) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode(code);
    raw.setMaterialName("MASTER-" + code);
    raw.setMaterialSpec("SPEC-" + code);
    raw.setMaterialModel("MODEL-" + code);
    raw.setDrawingNo("DRAW-" + code);
    raw.setShapeAttr("采购件");
    raw.setMainCategoryCode("1515601");
    raw.setUnit("PCS");
    raw.setActiveFlag(1);
    raw.setImportBatchId("u9-latest");
    return raw;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void assertRawHierarchyQueryUsesPriceOrg(
      BomRawHierarchyMapper bomMapper, String expectedPriceOrgCode) {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(bomMapper).selectList(captor.capture());
    Wrapper wrapper = captor.getValue();
    assertThat(wrapper.getSqlSegment()).contains("price_org_code");
    assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs())
        .containsValue(expectedPriceOrgCode);
  }
}
