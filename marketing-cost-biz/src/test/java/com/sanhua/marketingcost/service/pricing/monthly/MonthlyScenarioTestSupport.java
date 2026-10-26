package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.scrap.*;
import com.sanhua.marketingcost.service.settlement.*;

/** Only calculation methods are used: accidental source I/O fails against null dependencies. */
public final class MonthlyScenarioTestSupport {
  private MonthlyScenarioTestSupport() {}
  public static MonthlyPriceScenarios create(PriceLinkedFormulaPreviewServiceImpl formulas) {
    var json=new ObjectMapper().findAndRegisterModules();
    var ranges=new RangePriceResolver(null,null,null,new SupplierPreferredPriceSelector(null),null,null,null,null,null);
    return new MonthlyPriceScenarios(new MonthlyPriceDocumentCodec(json),new OriginalLinkedRuleCalculator(),
        new OriginalRangeRuleCalculator(),new OriginalRangeRuleSnapshot(json),ranges,formulas,
        new SettlementPriceCalculator(),new ScrapPriceCalculator(new ScrapPriceRuleCodec(json)));
  }
}
