package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;
import org.springframework.stereotype.Service;

/** 按资料实际月份更新；不以服务器当前月份替代价格月份。 */
@Service
public class MaterialPriceSourceRefresh {
  private final MaterialPriceSourceService service;
  public MaterialPriceSourceRefresh(MaterialPriceSourceService service) { this.service=service; }
  public RefreshReport refresh(String unit,String actor) {
    var months=service.availableMonths(unit); int rows=0;
    for (String month:months) rows+=service.refresh(unit,month,actor).sourceCount();
    return new RefreshReport(months.size(),rows);
  }
}
