package com.sanhua.marketingcost.service.bomalternative;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 报价 BOM 标准/替代选择运行时开关。 */
@Component
public class QuoteBomAlternativeFeatureSwitch {

  public static final String DISABLED_CODE = "ALT_SELECTION_DISABLED";

  private final boolean enabled;

  public QuoteBomAlternativeFeatureSwitch(
      @Value("${cost.quote-bom.alternative-selection.enabled:true}")
          boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void requireEnabled() {
    if (!enabled) {
      throw new QuoteBomAlternativeSelectionException(
          DISABLED_CODE,
          "标准/替代选择功能当前已关闭；原报价、上卷和历史记录不受影响");
    }
  }
}
