package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Last fallback for repricing: a proven historical supplemental unit price, never a prior product cost. */
@Component
public final class MonthlyRepriceSupplementalPriceSource {
  private final MonthlyPriceStorage storage;
  private final MonthlyRepriceBomReadScope scope;
  private final MonthlyPriceDocumentCodec documents;

  public MonthlyRepriceSupplementalPriceSource(MonthlyPriceStorage storage,
      MonthlyRepriceBomReadScope scope,MonthlyPriceDocumentCodec documents) {
    this.storage=storage;this.scope=scope;this.documents=documents;
  }

  public Candidates fill(Context context,Candidates current) {
    if(current.problems().isEmpty() || !scope.priceContext(context))return current;
    var missing=new LinkedHashSet<MonthlyPriceRequest>();
    current.problems().forEach((request,problem)->{
      if(problem.reason()==Failure.MISSING || problem.reason()==Failure.WAITING)missing.add(request);
    });
    if(missing.isEmpty())return current;
    var codes=missing.stream().map(request->request.family().materialCode()).distinct().toList();
    var previous=storage.previousSupplemental(context.businessUnitType(),context.month(),codes).stream()
        .sorted(Comparator.comparing((MonthlyPriceStorage.StoredPrice price)->price.family().month())
            .thenComparingLong(MonthlyPriceStorage.StoredPrice::id).reversed()).toList();
    var values=new LinkedHashMap<>(current.values());var problems=new LinkedHashMap<>(current.problems());
    for(var request:missing)for(var price:previous) {
      var old=price.family();
      if(!old.month().isBefore(context.month()) || !new MonthlyPriceFamilyKey(context.month(),old.businessUnitType(),
          old.materialCode(),old.specification(),old.unit(),old.currency(),old.taxBasis(),old.requestedSupplierCode(),old.specificationOnly())
          .equals(request.family()) || !price.applicability().matches(request))continue;
      var evidence=documents.decode(documents.wrap(price.evidence().json()),ObjectNode.class);
      if(price.pricingType()!=PricingType.NORMAL || !"TECH_FIXED".equals(evidence.path("sourceKind").asText()))
        throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"历史补录价格的保存依据不一致");
      // Keep the original source/approval snapshot; later draft or flow edits cannot invalidate that past use.
      evidence.put("invoiceRevision",0);
      evidence.putObject("carriedSupplemental").put("monthlyPriceId",price.id())
          .put("month",old.month().toString()).put("sourceEvidenceHash",price.evidence().sha256());
      var fact=PriceFact.normal(price.unitPrice(),SourceEvidence.ofJson(documents.unwrap(documents.encode(evidence))),Set.of());
      values.put(request,new Candidate(new Selection(null,price.applicability(),fact),null));
      problems.remove(request);break;
    }
    return new Candidates(values,problems);
  }
}
