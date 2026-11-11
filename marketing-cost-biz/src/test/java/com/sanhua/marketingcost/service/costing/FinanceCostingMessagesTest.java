package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinanceCostingMessagesTest {
  private MakePartPriceCalcRow row(String status) {
    var row = new MakePartPriceCalcRow(); row.setParentMaterialNo("201560042"); row.setChildMaterialNo("301210119");row.setStatus(status);return row;
  }
  @Test void successfulWeightFallbackDoesNotBecomePartOfThePriceFailure() {
    var row=row("MISSING_RAW_PRICE");row.setGrossWeightG(new BigDecimal("0.701"));row.setNetWeightG(new BigDecimal("0.4"));
    row.setRemark("副产品反算失败：缺有效U9主制造副产品(parent_material_no=201560042)；单子料：净重取 parent 理论净重；原材料取价异常(status=MISSING_PRICE, material_code=301210119): 当前料号没有技术补录办理来源；缺原材料价格(child_material_no=301210119)");
    assertThat(FinanceCostingMessages.manufacturingGap(row)).contains("301210119", "价格")
        .doesNotContain("副产品", "parent", "status=", "技术补录", "缺少净重", "缺少毛重");
  }
  @Test void missingNetAndGrossAreIdentifiedSeparatelyAndZeroIsValid() {
    var row=row("MISSING_WEIGHT");row.setGrossWeightG(BigDecimal.ZERO);
    assertThat(FinanceCostingMessages.manufacturingGap(row)).contains("201560042", "301210119", "缺少净重").doesNotContain("缺少毛重");
    row.setGrossWeightG(null);row.setNetWeightG(BigDecimal.ZERO);
    assertThat(FinanceCostingMessages.manufacturingGap(row)).contains("缺少毛重").doesNotContain("缺少净重");
    row.setNetWeightG(null);assertThat(FinanceCostingMessages.manufacturingGap(row)).contains("缺少毛重、净重");
  }
  @Test void meaningfulSourceRestrictionIsNotReplacedByAMissingPriceClaim() {
    var row=row("RAW_PRICE_ERROR");row.setRemark("净重按U9副产品反算(gross_weight_g=1)；原材料取价异常(status=FAILED): 联动公式缺少行情档位120000；按当月适用基础物料单价计算；缺原材料价格(child_material_no=301210119)");
    assertThat(FinanceCostingMessages.manufacturingGap(row)).contains("301210119", "联动公式缺少行情档位120000").doesNotContain("gross_weight_g", "副产品", "status=");
  }
}
