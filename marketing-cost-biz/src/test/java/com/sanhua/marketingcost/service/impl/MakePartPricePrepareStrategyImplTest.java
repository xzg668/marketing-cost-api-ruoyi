package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartPricePrepareStrategyImplTest {

  private MakePartPriceGenerationService generationService;
  private MakePartPriceCalcRowMapper calcRowMapper;
  private MakePartPricePrepareStrategyImpl strategy;

  @BeforeEach
  void setUp() {
    generationService = mock(MakePartPriceGenerationService.class);
    calcRowMapper = mock(MakePartPriceCalcRowMapper.class);
    strategy = new MakePartPricePrepareStrategyImpl(generationService, calcRowMapper);
  }

  @Test
  @DisplayName("自制件：已有当期生成结果时直接写 READY")
  void existingGeneratedResultWritesReady() {
    when(calcRowMapper.selectList(any())).thenReturn(List.of(okRow(501L, "BATCH-OLD")));

    MakePartPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem("MAKE-001"));

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("18.60");
    assertThat(result.getAmount()).isEqualByComparingTo("46.500");
    assertThat(result.getPriceSource()).isEqualTo("自制件价格生成");
    assertThat(result.getResultRefType()).isEqualTo("MAKE_PART_PRICE");
    assertThat(result.getResultRefId()).isEqualTo(501L);
    verify(generationService, never()).generateByOa(any(), any(), any());
  }

  @Test
  @DisplayName("自制件：缺当期结果时触发按 OA 生成后写 READY")
  void missingResultTriggersGeneration() {
    MakePartPriceGenerateResponse response =
        new MakePartPriceGenerateResponse("BATCH-GEN", 1, 1, 1, 0, 0);
    when(generationService.generateByOa("OA-001", "COMMERCIAL", "2026-05")).thenReturn(response);
    when(calcRowMapper.selectList(any()))
        .thenReturn(List.of())
        .thenReturn(List.of(okRow(502L, "BATCH-GEN")));

    MakePartPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem("MAKE-001"));

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getResultRefId()).isEqualTo(502L);
    assertThat(result.getMessage()).contains("已触发生成");
    verify(generationService).generateByOa("OA-001", "COMMERCIAL", "2026-05");
  }

  @Test
  @DisplayName("自制件：缺原材料价格写 MISSING_PRICE 缺口")
  void missingRawPriceWritesGap() {
    MakePartPriceGenerateResponse response =
        new MakePartPriceGenerateResponse("BATCH-MISS", 1, 1, 0, 0, 1);
    when(generationService.generateByOa("OA-001", "COMMERCIAL", "2026-05")).thenReturn(response);
    when(calcRowMapper.selectList(any()))
        .thenReturn(List.of())
        .thenReturn(List.of())
        .thenReturn(List.of(row("BATCH-MISS", MakePartPriceCalculator.STATUS_MISSING_RAW_PRICE,
            "RAW-001", null, "缺原材料价格")));

    MakePartPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem("MAKE-001"));

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGaps()).hasSize(1);
    assertThat(result.getGaps().get(0).getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGaps().get(0).getGapMaterialCode()).isEqualTo("RAW-001");
    assertThat(result.getGaps().get(0).getSourceTable()).isEqualTo("lp_make_part_price_gap_item");
  }

  @Test
  @DisplayName("自制件：缺废料价格写 MISSING_PRICE 缺口，指向废料料号")
  void missingScrapPriceWritesGap() {
    MakePartPriceGenerateResponse response =
        new MakePartPriceGenerateResponse("BATCH-MISS", 1, 1, 0, 0, 1);
    when(generationService.generateByOa("OA-001", "COMMERCIAL", "2026-05")).thenReturn(response);
    when(calcRowMapper.selectList(any()))
        .thenReturn(List.of())
        .thenReturn(List.of())
        .thenReturn(List.of(row("BATCH-MISS", MakePartPriceCalculator.STATUS_MISSING_SCRAP_PRICE,
            "RAW-001", "SCRAP-001", "缺回收价格")));

    MakePartPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem("MAKE-001"));

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGaps()).hasSize(1);
    assertThat(result.getGaps().get(0).getGapMaterialCode()).isEqualTo("SCRAP-001");
    assertThat(result.getGaps().get(0).getMessage()).contains("缺回收价格");
  }

  @Test
  @DisplayName("自制件：缺 BOM 写 MISSING_STRUCTURE 缺口")
  void missingBomWritesStructureGap() {
    MakePartPriceGenerateResponse response =
        new MakePartPriceGenerateResponse("BATCH-BOM", 1, 1, 0, 0, 1);
    when(generationService.generateByOa("OA-001", "COMMERCIAL", "2026-05")).thenReturn(response);
    when(calcRowMapper.selectList(any()))
        .thenReturn(List.of())
        .thenReturn(List.of())
        .thenReturn(List.of(row("BATCH-BOM", "MISSING_BOM", null, null, "缺 U9 直接子项")));

    MakePartPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem("MAKE-001"));

    assertThat(result.getStatus()).isEqualTo("MISSING_STRUCTURE");
    assertThat(result.getGaps()).hasSize(1);
    assertThat(result.getGaps().get(0).getGapType()).isEqualTo("MISSING_STRUCTURE");
    assertThat(result.getGaps().get(0).getSourceTable()).isEqualTo("lp_bom_u9_source");
  }

  @Test
  @DisplayName("自制件：策略实现不读取旧 lp_make_part_spec 人工维护价")
  void doesNotReferenceOldMakePartSpec() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/com/sanhua/marketingcost/service/impl/MakePartPricePrepareStrategyImpl.java"));

    assertThat(source).doesNotContain("lp_make_part_spec", "MakePartSpec", "raw_unit_price", "recycle_unit_price");
  }

  private PricePreparePlanItem planItem(String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setTopProductCode("TOP-001");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setQtyPerTop(new BigDecimal("2.5"));
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(row.getTopProductCode());
    item.setMaterialCode(materialCode);
    item.setMaterialName(row.getMaterialName());
    item.setItemType("MAKE_PART");
    item.setStatus("READY");
    return item;
  }

  private MakePartPriceCalcRow okRow(Long id, String calcBatchId) {
    MakePartPriceCalcRow row = row(calcBatchId, MakePartPriceCalculator.STATUS_OK, "RAW-001", "SCRAP-001", "OK");
    row.setId(id);
    row.setPriceComplete(true);
    row.setParentTotalCostPrice(new BigDecimal("18.60"));
    return row;
  }

  private MakePartPriceCalcRow row(
      String calcBatchId,
      String status,
      String childMaterialNo,
      String scrapCode,
      String remark) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setCalcBatchId(calcBatchId);
    row.setOaNo("OA-001");
    row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-05");
    row.setParentMaterialNo("MAKE-001");
    row.setChildMaterialNo(childMaterialNo);
    row.setScrapCode(scrapCode);
    row.setStatus(status);
    row.setRemark(remark);
    row.setPriceComplete(false);
    return row;
  }
}
