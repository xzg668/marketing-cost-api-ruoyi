package com.sanhua.marketingcost.service.bomalternative;

/** BOM 分支改变后各下游环节的失效数量。 */
public record QuoteBomAlternativeWorkflowInvalidationResult(
    int priceTypeCount,
    int pricePrepareCount,
    int costRunCount) {
}
