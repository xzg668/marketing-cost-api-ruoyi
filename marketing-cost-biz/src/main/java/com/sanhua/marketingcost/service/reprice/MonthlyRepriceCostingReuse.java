package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.costing.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Complete-input grouping before arithmetic; each consumer binds its own provenance afterward. */
@Service
public class MonthlyRepriceCostingReuse {
  public record Bound(long groupId, CostRunObjectResult ordinary, MonthlyQuotationCosting.Prepared monthly,
      MonthlyProductCost.Numbers numbers) {}

  private final CostRunEngine engine;
  private final MonthlyQuotationCosting costing;
  private final MonthlyRepriceInputProjection inputs;
  private final MonthlyRepriceSharedNumbers shared;

  public MonthlyRepriceCostingReuse(CostRunEngine engine, MonthlyQuotationCosting costing,
      MonthlyRepriceInputProjection inputs, MonthlyRepriceSharedNumbers shared) {
    this.engine=engine; this.costing=costing; this.inputs=inputs; this.shared=shared;
  }

  public Bound resolve(MonthlyRepriceCalculation calculation, QuoteCostRunVersion version,
      CostRunContext context, Consumer<CostRunObjectResult> validate) {
    var resolved = engine.resolve(context);
    var materials = costing.materials(version);
    var document = inputs.capture(calculation,resolved,materials,version);
    var published = shared.getOrCalculate(calculation.repriceNo(),document,() -> {
      var ordinary = ResolvedOrdinaryCost.calculate(resolved.inputs());
      var result = resolved.bind(ordinary); validate.accept(result);
      var monthly = materials.complete(result).resolve();
      return new MonthlyRepriceSharedNumbers.Numbers(1,ordinary,MonthlyProductCost.calculate(monthly.inputs()));
    });
    var result = resolved.bind(published.numbers().ordinary());
    var monthly = materials.complete(result);
    // saveCalculated checks monthly inputs and binds provenance inside the consumer's transaction.
    return new Bound(published.groupId(),result,monthly,published.numbers().monthly());
  }

  public void bind(MonthlyRepriceCalculation calculation, long groupId) {
    shared.bind(calculation.repriceNo(),calculation.objectKey(),groupId);
  }
}
