package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartWeightServiceImplTest {

  @Test
  @DisplayName("原材料加工毛重 = qty_per_parent * 1000g，净重取 parent 理论净重")
  void rawProcessGrossWeightUsesQtyPerParentKgConvertedToGram() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("P-001", "55"), raw("RAW-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights("P-001", child("RAW-001", "0.080"), "原材料加工");

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("80.000");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("55");
  }

  @Test
  @DisplayName("毛坯加工毛重取 child 理论净重，净重取 parent 理论净重")
  void blankProcessGrossWeightUsesChildTheoreticalNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("P-001", "55"), raw("BLANK-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights(
            "P-001", child("BLANK-001", "0.080"), MakePartProcessTypePolicy.PROCESS_TYPE_BLANK);

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("80");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("55");
  }

  @Test
  @DisplayName("缺理论净重或用量时返回 MISSING_WEIGHT，不按 0 静默计算")
  void missingWeightReturnsMissingStatus() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(mapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("P-001", ""), raw("RAW-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights("P-001", child("RAW-001", null), "原材料加工");

    assertThat(result.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(result.getGrossWeightG()).isNull();
    assertThat(result.getNetWeightG()).isNull();
    assertThat(result.getRemark()).contains("缺 qty_per_parent", "缺 parent 理论净重");
  }

  private BomU9Source child(String childCode, String qtyPerParent) {
    BomU9Source child = new BomU9Source();
    child.setChildMaterialNo(childCode);
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
}
