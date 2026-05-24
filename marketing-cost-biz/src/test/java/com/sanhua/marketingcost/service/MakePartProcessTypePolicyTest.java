package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.MakePartProcessTypeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartProcessTypePolicyTest {

  private final MakePartProcessTypePolicy policy = new MakePartProcessTypePolicy();

  @Test
  @DisplayName("stock_unit = '只' 判定为毛坯加工")
  void stockUnitPieceMeansBlankProcess() {
    MakePartProcessTypeResult result = policy.resolve(" 只 ");

    assertThat(result.getItemProcessType()).isEqualTo("毛坯加工");
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getRemark()).isEmpty();
  }

  @Test
  @DisplayName("stock_unit 非'只'的非空单位判定为原材料加工")
  void nonPieceStockUnitMeansRawProcess() {
    for (String stockUnit : new String[] {"kg", "千克", "米", "PCS"}) {
      MakePartProcessTypeResult result = policy.resolve(stockUnit);

      assertThat(result.getItemProcessType()).isEqualTo("原材料加工");
      assertThat(result.getStatus()).isEqualTo("OK");
      assertThat(result.getRemark()).isEmpty();
    }
  }

  @Test
  @DisplayName("stock_unit 空值输出异常状态和备注")
  void blankStockUnitReturnsMissingStatus() {
    MakePartProcessTypeResult result = policy.resolve(" ");

    assertThat(result.getItemProcessType()).isEqualTo("原材料加工");
    assertThat(result.getStatus()).isEqualTo("MISSING_STOCK_UNIT");
    assertThat(result.getRemark()).contains("stock_unit 为空");
  }
}
