package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("正式 BOM 只读服务")
class FormalBomReadServiceImplTest {

  @Test
  @DisplayName("按产品料号、期间、BOM 用途读取 lp_bom_raw_hierarchy 并补齐主档字段")
  void readsFormalBomRows() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    when(bomMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(rawRow(2L, 1, "P-001", "C-002"), rawRow(1L, 0, "P-001", "P-001")));
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(master("P-001"), master("C-002")));

    FormalBomReadServiceImpl service = new FormalBomReadServiceImpl(bomMapper, rawMapper);
    FormalBomReadResult result = service.read(" P-001 ", "2026-05", "主制造");

    assertThat(result.found()).isTrue();
    assertThat(result.productCode()).isEqualTo("P-001");
    assertThat(result.periodMonth()).isEqualTo("2026-05");
    assertThat(result.lines()).extracting(QuoteBomSourceLineDto::materialCode)
        .containsExactly("P-001", "C-002");
    assertThat(result.lines().get(0).materialModel()).isEqualTo("MODEL-P-001");
    assertThat(result.lines().get(1).drawingNo()).isEqualTo("DRAW-C-002");
    assertThat(result.lines().get(1).sourceRawHierarchyId()).isEqualTo(2L);
  }

  @Test
  @DisplayName("正式 BOM 无数据时返回明确缺口")
  void returnsGapWhenFormalBomMissing() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    FormalBomReadServiceImpl service = new FormalBomReadServiceImpl(bomMapper, rawMapper);
    FormalBomReadResult result = service.read("P-MISS", "2026-05", "主制造");

    assertThat(result.found()).isFalse();
    assertThat(result.lines()).isEmpty();
    assertThat(result.gapMessage()).contains("lp_bom_raw_hierarchy");
  }

  private BomRawHierarchy rawRow(Long id, int level, String topCode, String materialCode) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode(topCode);
    row.setParentCode(level == 0 ? topCode : topCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("BOM-" + materialCode);
    row.setMaterialSpec("SPEC-" + materialCode);
    row.setLevel(level);
    row.setPath(level == 0 ? "/P-001/" : "/P-001/C-002/");
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
}
