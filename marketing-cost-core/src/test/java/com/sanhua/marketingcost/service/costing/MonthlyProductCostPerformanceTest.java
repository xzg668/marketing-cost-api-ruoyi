package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** The MPP-01 budget was fixed before implementation: 1000 ordinary + 100 household, p95 <=100ms. */
@Tag("slow")
class MonthlyProductCostPerformanceTest {
  static BigDecimal d(String value){return new BigDecimal(value);}
  @Test void exactRuleResultsMeetTheFixedPureCostingBudget() throws Exception {
    var selected=new HashMap<MonthlyPriceRequest,Selection>();var uses=new ArrayList<Use>();var rows=new ArrayList<MaterialLine>();var suppressed=new HashSet<String>();
    var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()));
    for(int i=0;i<1100;i++){
      boolean household=i>=1000;String code=(household?"H":"N")+i;String path="root/"+code;
      var request=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
      var evidence=SourceEvidence.ofJson("{\"controlledPureInput\":true,\"source\":\""+code+"\"}");
      var fact=household?PriceFact.household(d("20.9853"),d(".085"),evidence):PriceFact.normal(d("1.23456789"),evidence,Set.of());
      selected.put(request,new Selection((long)i+1,coverage,fact));uses.add(new Use(path,request,d("2.5")));
      if(household)suppressed.add(path+"/included");else rows.add(new MaterialLine(path,code,d("2.5"),List.of((long)i+1),d("3.08641973"),d("3.08641973"),null));
    }
    var plan=new MonthlyAdoptionPlan(selected,uses,suppressed);var ordinary=new Ordinary(rows,d("100"),d("10"),d("2"),d(".01"),d(".2"),d("1.05"),d(".1"),d(".25"),d(".02"),BigDecimal.ZERO);
    for(int i=0;i<5;i++)MonthlyProductCost.calculate(plan,ordinary,null);
    var times=new ArrayList<Double>();MonthlyProductCost.Result result=null;
    var memory=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
    long allocatedBefore=memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
    for(int i=0;i<30;i++){long start=System.nanoTime();result=MonthlyProductCost.calculate(plan,ordinary,null);times.add((System.nanoTime()-start)/1000000.0);}
    var sorted=times.stream().sorted().toList();double p95=sorted.get((int)Math.ceil(times.size()*.95)-1);
    var json=new ObjectMapper();var expected=json.readTree(getClass().getResourceAsStream("/monthly-cost/performance-golden.json"));
    assertThat(result.financeMaterialCost()).isEqualByComparingTo(expected.path("material").asText());assertThat(result.ordinary().manufactureCost()).isEqualByComparingTo(expected.path("F").asText());
    assertThat(result.householdSubtotal()).isEqualByComparingTo(expected.path("AiSum").asText());assertThat(result.totalCost()).isEqualByComparingTo(expected.path("total1").asText());assertThat(result.household()).hasSize(100);
    System.out.println("MPP08_PURE_PERFORMANCE_JSON="+json.writeValueAsString(Map.ofEntries(Map.entry("timesMs",times),Map.entry("p50Ms",sorted.get(14)),Map.entry("p95Ms",p95),Map.entry("p99Ms",sorted.get(29)),Map.entry("maxMs",sorted.get(29)),Map.entry("threadAllocatedBytes",memory.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocatedBefore),Map.entry("observedHeapUsedBytes",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()),Map.entry("rowsPerSecond",1100*30*1000.0/times.stream().mapToDouble(Double::doubleValue).sum()),Map.entry("warmup",5),Map.entry("observations",30),Map.entry("ordinaryRows",1000),Map.entry("householdRows",100),Map.entry("databaseCalls",0),Map.entry("thresholdMs",100),Map.entry("java",System.getProperty("java.version")),Map.entry("osArch",System.getProperty("os.arch")),Map.entry("logicalCpus",Runtime.getRuntime().availableProcessors()))));
    assertThat(p95).as("MPP-01 immutable pure-costing p95 budget").isLessThanOrEqualTo(100);
  }
}
