package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MakePartSourceDataServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
    TableInfoHelper.initTableInfo(assistant, BomU9Source.class);
  }

  @Test
  @DisplayName("制造件候选只返回 shape_attr = '制造件'")
  void listManufacturedParentsOnlyReturnsManufacturedRows() {
    BomCostingRowMapper costingMapper = mock(BomCostingRowMapper.class);
    BomU9SourceMapper u9Mapper = mock(BomU9SourceMapper.class);
    MakePartSourceDataServiceImpl service =
        new MakePartSourceDataServiceImpl(costingMapper, u9Mapper);
    when(costingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(costingRow("MAKE-001", "制造件"), costingRow("BUY-001", "采购件")));

    List<BomCostingRow> result = service.listManufacturedParents("OA-001", "COMMERCIAL", "B-1");

    assertThat(result).extracting(BomCostingRow::getMaterialCode).containsExactly("MAKE-001");
    assertThat(result).allMatch(row -> "制造件".equals(row.getShapeAttr()));
  }

  @Test
  @DisplayName("U9 子项只查主制造有效期内记录，再按 parent + child 去重")
  void listDedupedChildrenKeepsMainPurposeEffectiveChildren() {
    BomCostingRowMapper costingMapper = mock(BomCostingRowMapper.class);
    BomU9SourceMapper u9Mapper = mock(BomU9SourceMapper.class);
    MakePartSourceDataServiceImpl service =
        new MakePartSourceDataServiceImpl(costingMapper, u9Mapper);
    when(u9Mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                child("P-001", "RAW-B", 3, 11L, "主制造", LocalDate.parse("9999-12-31")),
                child("P-001", "RAW-A", 1, 12L, "主制造", LocalDate.parse("9999-12-31"))));

    List<BomU9Source> result = service.listDedupedChildren("P-001", LocalDate.parse("2026-05-31"));

    assertThat(result).hasSize(2);
    assertThat(result).extracting(BomU9Source::getChildMaterialNo).containsExactly("RAW-A", "RAW-B");
    BomU9Source rawA =
        result.stream().filter(row -> "RAW-A".equals(row.getChildMaterialNo())).findFirst().orElseThrow();
    assertThat(rawA.getId()).isEqualTo(12L);

    ArgumentCaptor<Wrapper<BomU9Source>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(u9Mapper).selectList(captor.capture());
    String sqlSegment = captor.getValue().getSqlSegment();
    assertThat(sqlSegment)
        .contains("parent_material_no", "bom_purpose", "effective_from", "effective_to");
  }

  private BomCostingRow costingRow(String materialCode, String shapeAttr) {
    BomCostingRow row = new BomCostingRow();
    row.setMaterialCode(materialCode);
    row.setShapeAttr(shapeAttr);
    return row;
  }

  private BomU9Source child(
      String parent, String child, Integer seq, Long id, String purpose, LocalDate effectiveTo) {
    BomU9Source row = new BomU9Source();
    row.setId(id);
    row.setParentMaterialNo(parent);
    row.setChildMaterialNo(child);
    row.setChildSeq(seq);
    row.setBomPurpose(purpose);
    row.setEffectiveTo(effectiveTo);
    return row;
  }
}
