package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MakePartChildNetWeight;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MakePartChildNetWeightMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartWeightServiceImplTest {

  @Test
  @DisplayName("单子料原材料加工毛重 = qty_per_parent * 1000g，净重可兼容 parent 理论净重")
  void rawProcessGrossWeightUsesQtyPerParentKgConvertedToGram() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", "55"), raw("RAW-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights(
            "P-001", child("RAW-001", "0.080"), "原材料加工", "2026-07", false);

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("80.000");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("55");
  }

  @Test
  @DisplayName("毛坯加工毛重取 child 理论净重，净重取 parent 理论净重")
  void blankProcessGrossWeightUsesChildTheoreticalNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", "55"), raw("BLANK-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights(
            "P-001",
            child("BLANK-001", "0.080"),
            MakePartProcessTypePolicy.PROCESS_TYPE_BLANK,
            "2026-07",
            false);

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("80");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("55");
  }

  @Test
  @DisplayName("缺理论净重或用量时返回 MISSING_WEIGHT，不按 0 静默计算")
  void missingWeightReturnsMissingStatus() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", ""), raw("RAW-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights(
            "P-001", child("RAW-001", null), "原材料加工", "2026-07", false);

    assertThat(result.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(result.getGrossWeightG()).isNull();
    assertThat(result.getNetWeightG()).isNull();
    assertThat(result.getRemark()).contains("缺 qty_per_parent", "缺 parent 理论净重");
  }

  @Test
  @DisplayName("BOM 子行缺 priceOrgCode 时直接报错，不默认读商用料品档案")
  void missingPriceOrgCodeFailsFast() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper, childWeightMapper);
    BomU9Source child = child("RAW-001", "0.080");
    child.setPriceOrgCode(null);

    assertThatThrownBy(
            () -> service.resolveWeights("P-001", child, "原材料加工", "2026-07", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceOrgCode");
  }

  @Test
  @DisplayName("多原材料制造件按父料号 + 子料号分别读取净重")
  void multipleRawMaterialsUseTheirOwnParentChildNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(raw("1053000301687", "45.896")));
    when(childWeightMapper.selectEffective(
            "PLATE", "1053000301687", "301240299", "F001", "2026-07"))
        .thenReturn(childNetWeight("37.89999570"));
    when(childWeightMapper.selectEffective(
            "PLATE", "1053000301687", "301070047", "F001", "2026-07"))
        .thenReturn(childNetWeight("5.59999940"));

    BomU9Source stainless = child("301240299", "0.0379");
    stainless.setPriceOrgCode("220");
    BomU9Source copper = child("301070047", "0.0056");
    copper.setPriceOrgCode("220");

    MakePartWeightResult stainlessResult = service.resolveWeights(
        "1053000301687", stainless, "原材料加工", "2026-07", true);
    MakePartWeightResult copperResult = service.resolveWeights(
        "1053000301687", copper, "原材料加工", "2026-07", true);

    assertThat(stainlessResult.getGrossWeightG()).isEqualByComparingTo("37.9");
    assertThat(stainlessResult.getNetWeightG()).isEqualByComparingTo("37.89999570");
    assertThat(copperResult.getGrossWeightG()).isEqualByComparingTo("5.6");
    assertThat(copperResult.getNetWeightG()).isEqualByComparingTo("5.59999940");
  }

  @Test
  @DisplayName("多原材料缺父子净重时禁止回退父件总净重")
  void multipleRawMaterialsDoNotReuseParentTotalNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", "45.896")));

    MakePartWeightResult result = service.resolveWeights(
        "P-001", child("RAW-001", "0.0379"), "原材料加工", "2026-07", true);

    assertThat(result.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(result.getNetWeightG()).isNull();
    assertThat(result.getRemark()).contains("缺父子材料净重", "P-001", "RAW-001");
  }

  private BomU9Source child(String childCode, String qtyPerParent) {
    BomU9Source child = new BomU9Source();
    child.setChildMaterialNo(childCode);
    child.setPriceOrgCode("210");
    child.setBomVersion("F001");
    if (qtyPerParent != null) {
      child.setQtyPerParent(new BigDecimal(qtyPerParent));
    }
    return child;
  }

  private MaterialMasterRaw raw(String materialCode, String theoreticalNetWeight) {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode(materialCode);
    row.setGlobalSeg3TheoreticalNetWeight(theoreticalNetWeight);
    return row;
  }

  private MakePartChildNetWeight childNetWeight(String netWeightG) {
    MakePartChildNetWeight row = new MakePartChildNetWeight();
    row.setNetWeightG(new BigDecimal(netWeightG));
    return row;
  }
}
