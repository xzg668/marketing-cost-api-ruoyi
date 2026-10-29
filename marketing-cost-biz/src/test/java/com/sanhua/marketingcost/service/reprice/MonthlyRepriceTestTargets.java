package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.*;
import java.time.YearMonth;
import java.util.List;

/** Test-source-only bridge to the explicit target contract; never part of the production artifact. */
public final class MonthlyRepriceTestTargets {
  private MonthlyRepriceTestTargets() {}
  public static MonthlyRepriceBatch enqueue(MonthlyRepriceContractService contracts,String unit,
      YearMonth month,YearMonth launch,List<QuoteCostRunVersion> sources) {
    return contracts.enqueue(unit,month,launch,sources.stream().map(v->new MonthlyRepriceContract.Source(
        new MonthlyRepriceContract.ObjectIdentity(unit,v.getOaNo(),v.getOaFormItemId()),v.getId())).toList());
  }
}
