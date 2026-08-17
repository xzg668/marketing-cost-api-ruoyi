package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-03 正式BOM替代元数据读取契约")
class FormalBomAlternativeMetadataContractTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomRawHierarchy.class);
  }

  @Test
  @DisplayName("正式BOM行透传childType、组键和U9来源行ID")
  void formalBomLineExposesAlternativeMetadata() {
    BomRawHierarchyMapper hierarchyMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchy top = row(1L, "TOP", "TOP", 0, "/TOP/");
    BomRawHierarchy child = row(2L, "TOP", "STD", 1, "/TOP/STD@10@010/");
    child.setChildType("STANDARD");
    child.setAlternativeGroupKey("a".repeat(64));
    child.setSourceU9RowId(287987L);
    when(hierarchyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(top, child));
    when(masterMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of());

    FormalBomReadResult result =
        new FormalBomReadServiceImpl(hierarchyMapper, masterMapper)
            .read(
                "TOP",
                "2026-07",
                "主制造",
                LocalDate.of(2026, 7, 15),
                new QuoteDataOrganization("210", "COMMERCIAL"));

    QuoteBomSourceLineDto line = result.lines().get(1);
    assertThat(line.childType()).isEqualTo("STANDARD");
    assertThat(line.alternativeGroupKey()).isEqualTo("a".repeat(64));
    assertThat(line.sourceU9BomId()).isEqualTo(287987L);
  }

  @Test
  @DisplayName("层级树调试DTO完整展示来源和替代元数据，顶层允许为空")
  void hierarchyDebugTreeExposesAlternativeMetadata() {
    BomRawHierarchyMapper hierarchyMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchy top = row(1L, "TOP", "TOP", 0, "/TOP/");
    BomRawHierarchy child = row(2L, "TOP", "ALT", 1, "/TOP/ALT@10@010/");
    child.setChildType("ALTERNATIVE");
    child.setAlternativeGroupKey("b".repeat(64));
    child.setSourceU9RowId(283417L);
    when(hierarchyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(top, child));
    PlateCommercialMakeBomExpansionService expansionService =
        new PlateCommercialMakeBomExpansionService(hierarchyMapper, masterMapper);
    BomHierarchyQueryServiceImpl queryService =
        new BomHierarchyQueryServiceImpl(hierarchyMapper, expansionService);

    BomHierarchyTreeDto tree =
        queryService.getHierarchyTree(
            "TOP", "主制造", LocalDate.of(2026, 7, 15), "U9", "210");

    assertThat(tree.getChildType()).isNull();
    assertThat(tree.getAlternativeGroupKey()).isNull();
    assertThat(tree.getSourceU9RowId()).isNull();
    BomHierarchyTreeDto childDto = tree.getChildren().get(0);
    assertThat(childDto.getChildType()).isEqualTo("ALTERNATIVE");
    assertThat(childDto.getAlternativeGroupKey()).isEqualTo("b".repeat(64));
    assertThat(childDto.getSourceU9RowId()).isEqualTo(283417L);
  }

  private static BomRawHierarchy row(
      long id, String topProductCode, String materialCode, int level, String path) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode(topProductCode);
    row.setParentCode(topProductCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("NAME-" + materialCode);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(level == 0 ? null : 10);
    row.setProcessSeq(level == 0 ? null : "010");
    row.setQtyPerParent(level == 0 ? null : BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setBomPurpose("主制造");
    row.setBomVersion("F006");
    row.setEffectiveFrom(LocalDate.of(2026, 5, 21));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setIsLeaf(level == 0 ? 0 : 1);
    return row;
  }
}
