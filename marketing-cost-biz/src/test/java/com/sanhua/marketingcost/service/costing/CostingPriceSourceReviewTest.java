package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostingPriceSourceReviewTest {
  @Test void onlyAnEmptyInventoryAcrossSourcesIsARealMissingPrice() {
    var sources=List.of(new CostingPriceSourceReview.Check("固定价",0,"无记录"),new CostingPriceSourceReview.Check("订单价",0,"无记录"),new CostingPriceSourceReview.Check("询价",0,"无记录"));
    var result=CostingPriceSourceReview.summarize(sources);
    assertThat(result.absent()).isTrue();assertThat(result.reason()).contains("各价格来源均无", "财务导入").doesNotContain("技术补录");
  }
  @Test void existingUnusableRecordsMustNotBeCalledMissingPrices() {
    var result=CostingPriceSourceReview.summarize(List.of(new CostingPriceSourceReview.Check("固定价",0,"无记录"),new CostingPriceSourceReview.Check("联动价",2,"记录组织：220；包含尚未发布的价格版本")));
    assertThat(result.absent()).isFalse();assertThat(result.reason()).contains("已有价格记录", "联动价", "220", "尚未发布").doesNotContain("均无该料号记录");
  }
  @Test void anAdoptedMonthlyFactIsNotLostWhenTheOriginalPriceTableIsEmpty() {
    var result=CostingPriceSourceReview.summarize(List.of(new CostingPriceSourceReview.Check("固定价",0,"无记录"),new CostingPriceSourceReview.Check("当月月价（已采用结果）",1,"来源首次采用后保存")));
    assertThat(result.absent()).isFalse();assertThat(result.reason()).contains("当月月价").doesNotContain("均无该料号记录");
  }
}
