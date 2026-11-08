package com.sanhua.marketingcost.service.reprice;

import java.time.YearMonth;
import java.util.*;

/** Comparison identity is independent of the OA that supplied either month's saved cost. */
public final class MonthlyCostComparisonScope {
  private MonthlyCostComparisonScope() {}
  public record Identity(String businessUnitType,String productCode,String customerSource,String customerValue,String unmatchedKey) {
    public Identity {
      MonthlyRepriceContract.unit(businessUnitType);
      if(productCode==null || productCode.isBlank())throw new IllegalArgumentException("比较对象缺少料号");
      if(unmatchedKey==null) {
        if(!Set.of("VERIFIED_CUSTOMER_CODE","OA_HEADER_CUSTOMER").contains(Objects.toString(customerSource,""))
            || customerValue==null || customerValue.isBlank())throw new IllegalArgumentException("比较对象缺少可靠客户身份");
      } else if(unmatchedKey.isBlank() || customerSource!=null || customerValue!=null)
        throw new IllegalArgumentException("未识别客户不能猜测匹配身份");
    }
    public boolean matched(){return unmatchedKey==null;}
    public String key(){return MonthlyRepriceContract.hash(matched()
        ?List.of("CUSTOMER",businessUnitType,productCode,customerSource,customerValue)
        :List.of("UNMATCHED",businessUnitType,productCode,unmatchedKey));}
  }
  public record Selected(String businessUnitType,String productCode,String customerSource,String customerValue,String unmatchedKey,
      String pricingMonth,String oaNo,long oaFormItemId,Long versionId,String repriceNo,Long resultId,Long sourceId,
      long sourceCostVersionId,int sourceCount,String status,String reason) {
    public Identity identity(){return new Identity(businessUnitType,productCode,customerSource,customerValue,unmatchedKey);}
    public YearMonth month(){return YearMonth.parse(pricingMonth);}
    public MonthlyRepriceContract.ObjectIdentity owner(){return new MonthlyRepriceContract.ObjectIdentity(businessUnitType,oaNo,oaFormItemId);}
  }
}
