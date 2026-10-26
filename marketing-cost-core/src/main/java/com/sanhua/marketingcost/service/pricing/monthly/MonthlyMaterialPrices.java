package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/** Saved financial facts, read-only candidates and atomic adoption are distinct operations. */
public interface MonthlyMaterialPrices {
  record Context(String businessUnitType,String oaNo,Long oaFormItemId,String productCode,
      YearMonth month,LocalDateTime priceAsOfTime,String actor,String consumerOrg,
      @com.fasterxml.jackson.annotation.JsonIgnore com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext supplierReviews,
      AnnualUsageInput annualUsage) {
    /** Saved context contains only origin fields; the calculation-local review collector is rebuilt. */
    @JsonCreator(mode=JsonCreator.Mode.PROPERTIES)
    public static Context fromJson(@JsonProperty("businessUnitType") String businessUnitType,
        @JsonProperty("oaNo") String oaNo,@JsonProperty("oaFormItemId") Long oaFormItemId,
        @JsonProperty("productCode") String productCode,@JsonProperty("month") YearMonth month,
        @JsonProperty("priceAsOfTime") LocalDateTime priceAsOfTime,@JsonProperty("actor") String actor,
        @JsonProperty("consumerOrg") String consumerOrg,
        @JsonProperty("annualUsage") AnnualUsageInput annualUsage) {
      return new Context(businessUnitType,oaNo,oaFormItemId,productCode,month,priceAsOfTime,actor,consumerOrg,null,annualUsage);
    }
    public Context(String businessUnitType,String oaNo,Long oaFormItemId,String productCode,
        YearMonth month,LocalDateTime priceAsOfTime,String actor,String consumerOrg) {
      this(businessUnitType,oaNo,oaFormItemId,productCode,month,priceAsOfTime,actor,consumerOrg,null,null);
    }
    public Context(String businessUnitType,String oaNo,Long oaFormItemId,String productCode,
        YearMonth month,LocalDateTime priceAsOfTime,String actor,String consumerOrg,
        com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext supplierReviews) {
      this(businessUnitType,oaNo,oaFormItemId,productCode,month,priceAsOfTime,actor,consumerOrg,supplierReviews,null);
    }
    public Context {
      if (!Set.of("COMMERCIAL","HOUSEHOLD").contains(businessUnitType)) throw new IllegalArgumentException("Explicit business unit required");
      if (oaNo==null || oaNo.isBlank() || productCode==null || productCode.isBlank() || actor==null || actor.isBlank()) throw new IllegalArgumentException("Explicit adoption origin and actor required");
      if (oaFormItemId==null || oaFormItemId<=0) throw new IllegalArgumentException("Explicit product item identity required");
      consumerOrg=com.sanhua.marketingcost.enums.MaterialOrganization.fromPriceOrgCode(consumerOrg).getPriceOrgCode();
      if(supplierReviews==null)supplierReviews=new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(oaFormItemId).forOrganization(consumerOrg);
      if(!Objects.equals(supplierReviews.oaFormItemId(),oaFormItemId))throw new IllegalArgumentException("Supplier reviews must belong to the current product item");
      Objects.requireNonNull(month);Objects.requireNonNull(priceAsOfTime);
    }
  }
  record Lookup(Map<MonthlyPriceRequest,Selection> values,Set<MonthlyPriceRequest> missing) {
    public Lookup {values=Map.copyOf(values);missing=Set.copyOf(missing);}
  }
  record Candidate(Selection selection,Long householdSourceId) {
    public Candidate {Objects.requireNonNull(selection);}
  }
  record Problem(MonthlyAdoptionRetryPolicy.Failure reason,String message) {
    public Problem {Objects.requireNonNull(reason);Objects.requireNonNull(message);}
  }
  record Candidates(Map<MonthlyPriceRequest,Candidate> values,Map<MonthlyPriceRequest,Problem> problems) {
    public Candidates {
      values=Map.copyOf(values);problems=Map.copyOf(problems);
      if (!Collections.disjoint(values.keySet(),problems.keySet())) throw new IllegalArgumentException("A request cannot have both a fact and a source problem");
    }
  }
  /** Result for the current quote; callers persist usage on existing preparation/cost records. */
  record AdoptionResult(MonthlyAdoptionPlan plan,
      List<com.sanhua.marketingcost.service.pricing.SupplierPriceReview> supplierReviews) {
    public AdoptionResult {Objects.requireNonNull(plan);supplierReviews=List.copyOf(supplierReviews);}
  }
  Lookup lookup(Collection<MonthlyPriceRequest> requests);
  Candidates resolveCandidates(Context context,Set<MonthlyPriceRequest> requests);
  Candidates resolveScrapCandidates(Context context,Set<MonthlyPriceRequest> requests);
  default AdoptionResult adopt(Context context,MonthlyAdoptionPlan plan){
    return adopt(context,plan,MonthlyAdoptionRetryPolicy.OPERATION_BUDGET);
  }
  AdoptionResult adopt(Context context,MonthlyAdoptionPlan plan,java.time.Duration remainingBudget);
  Map<Long,Selection> readByIds(String businessUnitType,Collection<Long> ids);
}
