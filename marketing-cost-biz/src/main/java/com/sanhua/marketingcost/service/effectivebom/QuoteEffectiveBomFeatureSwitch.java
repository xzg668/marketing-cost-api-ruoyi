package com.sanhua.marketingcost.service.effectivebom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 最终有效 BOM 主链运行时开关；默认关闭，便于按单产品灰度和快速回退旧工作台。 */
@Component
public class QuoteEffectiveBomFeatureSwitch {

  private final boolean enabled;

  public QuoteEffectiveBomFeatureSwitch(
      @Value("${cost.quote-bom.effective-bom.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
