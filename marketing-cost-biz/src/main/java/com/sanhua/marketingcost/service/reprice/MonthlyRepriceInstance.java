package com.sanhua.marketingcost.service.reprice;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One identity per application process, shared by its customer and calculation-group leases. */
@Component
public final class MonthlyRepriceInstance {
  private final String value;

  public MonthlyRepriceInstance(@Value("${cost.run.worker.id:monthly-reprice}") String configuredLabel) {
    String label=configuredLabel == null || configuredLabel.isBlank() ? "monthly-reprice" : configuredLabel.strip();
    if(label.length()>90) throw new IllegalArgumentException("调价实例标识不能超过90个字符");
    value=label+"-"+UUID.randomUUID();
  }

  public String value() { return value; }
}
