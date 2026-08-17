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

@DisplayName("BOM 层级树跨组织展示")
class BomHierarchyQueryServiceCrossOrganizationTreeTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomRawHierarchy.class);
  }

  @Test
  @DisplayName("板换树把商用制造件显示为非叶子并挂载商用 BOM 子件")
  void hierarchyTreeShowsCommercialMakeChildren() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchy top = row(1L, "P", "P", "P", 0, "/P/", "制造件", "1", "220");
    BomRawHierarchy cross =
        row(2L, "P", "P", "C", 1, "/P/C@10/", "采购件", "1", "220");
    BomRawHierarchy commercialChild =
        row(20L, "C", "C", "R", 1, "/C/R@10/", "采购件", "0.25", "210");
    when(bomMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(top, cross), List.of(commercialChild));
    when(masterMapper.selectByLatestBatchAndCodes(
            any(Collection.class), isNull(), eq("PLATE")))
        .thenReturn(List.of(master("P", "制造件"), master("C", "采购件")));
    when(masterMapper.selectByLatestBatchAndCodes(
            any(Collection.class), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(master("C", "制造件"), master("R", "采购件")));
    PlateCommercialMakeBomExpansionService expansionService =
        new PlateCommercialMakeBomExpansionService(bomMapper, masterMapper);
    BomHierarchyQueryServiceImpl queryService =
        new BomHierarchyQueryServiceImpl(bomMapper, expansionService);

    BomHierarchyTreeDto tree =
        queryService.getHierarchyTree(
            "P", "主制造", LocalDate.of(2026, 7, 10), "U9", "220");

    assertThat(tree).isNotNull();
    assertThat(tree.getChildren()).hasSize(1);
    BomHierarchyTreeDto commercialMake = tree.getChildren().get(0);
    assertThat(commercialMake.getMaterialCode()).isEqualTo("C");
    assertThat(commercialMake.getShapeAttr()).isEqualTo("制造件");
    assertThat(commercialMake.getIsLeaf()).isZero();
    assertThat(commercialMake.getChildren()).hasSize(1);
    assertThat(commercialMake.getChildren().get(0).getMaterialCode()).isEqualTo("R");
    assertThat(commercialMake.getChildren().get(0).getQtyPerTop())
        .isEqualByComparingTo("0.25");
  }

  private BomRawHierarchy row(
      Long id,
      String top,
      String parent,
      String code,
      int level,
      String path,
      String shape,
      String qty,
      String priceOrgCode) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(code);
    row.setMaterialName("NAME-" + code);
    row.setShapeAttr(shape);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(10);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    row.setIsLeaf("采购件".equals(shape) ? 1 : 0);
    row.setBomPurpose("主制造");
    row.setSourceType("U9");
    row.setPriceOrgCode(priceOrgCode);
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }

  private MaterialMasterRaw master(String code, String shape) {
    MaterialMasterRaw master = new MaterialMasterRaw();
    master.setMaterialCode(code);
    master.setMaterialName("MASTER-" + code);
    master.setShapeAttr(shape);
    master.setActiveFlag(1);
    return master;
  }
}
