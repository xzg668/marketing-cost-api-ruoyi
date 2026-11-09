package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.*;
import java.math.*;
import java.util.Objects;

/** Captured Cu/Zn inputs reproduce the inquiry W value without changing original E. */
public final class HouseholdMetalCalculation {
  public static final String FORMULA = "W = E + ((客户Cu - 基价Cu) × 铜重/1000 + (客户Zn - 基价Zn) × 锌重/1000) / 1.13";
  private HouseholdMetalCalculation() {}
  public record Inputs(BigDecimal copperGrams,BigDecimal zincGrams,PriceFactorContext financial) {
    public Inputs {
      for(var weight:new BigDecimal[]{copperGrams,zincGrams})
        if(weight==null || weight.signum()<0)throw new IllegalArgumentException("家用铜锌重量无效");
      if(copperGrams.signum()!=0 || zincGrams.signum()!=0) {
        Objects.requireNonNull(financial,"家用差异缺少商用财务基价");
        if(!"210".equals(financial.financial().scope().priceOrgCode()) || financial.hasCustomerMaterialPrices())
          throw new IllegalArgumentException("家用差异必须保存商用财务基价");
        if(copperGrams.signum()!=0)financial.requiredPrice(PriceFactorContext.COPPER,FactorPriceUnit.CNY_KG,true);
        if(zincGrams.signum()!=0)financial.requiredPrice(PriceFactorContext.ZINC,FactorPriceUnit.CNY_KG,true);
      }
    }
  }
  public record Trace(String formula,String metalPriceUnit,String weightUnit,BigDecimal taxDivisor,
      BigDecimal originalPrice,BigDecimal copperGrams,BigDecimal zincGrams,
      BigDecimal baseCopper,BigDecimal baseZinc,BigDecimal customerCopper,BigDecimal customerZinc,
      boolean copperFromCustomer,boolean zincFromCustomer,BigDecimal copperDifference,
      BigDecimal zincDifference,BigDecimal calculatedW,String pricingMonth,String financeMonth) {}
  public static Trace calculate(BigDecimal original,Inputs input,CustomerMaterialPrices customer) {
    BigDecimal cu=base(input,PriceFactorContext.COPPER,input.copperGrams());
    BigDecimal zn=base(input,PriceFactorContext.ZINC,input.zincGrams());
    var projected=input.financial()==null?null:input.financial().withCustomerMaterials(customer);
    BigDecimal customerCu=input.copperGrams().signum()==0?cu:projected.requiredPrice(PriceFactorContext.COPPER,FactorPriceUnit.CNY_KG,true);
    BigDecimal customerZn=input.zincGrams().signum()==0?zn:projected.requiredPrice(PriceFactorContext.ZINC,FactorPriceUnit.CNY_KG,true);
    BigDecimal cuDiff=delta(customerCu,cu,input.copperGrams()),znDiff=delta(customerZn,zn,input.zincGrams());
    var divisor=new BigDecimal("1.13");
    var result=original.add(cuDiff.add(znDiff).divide(divisor,18,RoundingMode.HALF_UP));
    if(result.signum()<0)throw new IllegalArgumentException("客户铜锌代入后家用询价为负数");
    return new Trace(FORMULA,"CNY/KG","G",divisor,original,input.copperGrams(),input.zincGrams(),
        cu,zn,customerCu,customerZn,customer.copper()!=null,customer.zinc()!=null,cuDiff,znDiff,result,
        input.financial()==null?null:input.financial().months().requestedMonth(),
        input.financial()==null?null:input.financial().months().sourceMonth());
  }
  private static BigDecimal base(Inputs input,String key,BigDecimal weight) {
    if(input.financial()==null)return null;
    if(weight.signum()==0 && !input.financial().financial().factors().containsKey(key))return null;
    return input.financial().requiredPrice(key,FactorPriceUnit.CNY_KG,true);
  }
  private static BigDecimal delta(BigDecimal customer,BigDecimal base,BigDecimal weight) {
    return weight.signum()==0?BigDecimal.ZERO:customer.subtract(base).multiply(weight).movePointLeft(3);
  }
}
