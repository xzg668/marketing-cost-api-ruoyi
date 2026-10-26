package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScopeTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.Node;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Frozen MPP-01 budget: 5 warmups + 30 samples, 10,000 nodes/depth 32, p95 <= 200ms. */
class MonthlyBomPricingScopePerformanceTest {
  @Test void mixedDeepAndRepeatedPathsMeetTheFrozenPurePlannerBudget() throws Exception {
    var measurements=new ArrayList<Map<String,Object>>();
    for(int count:List.of(10,100,1000,10000)) {
      var nodes=new ArrayList<Node>();nodes.add(node("TOP","TOP",null,"1"));
      var prices=new LinkedHashMap<MonthlyPriceRequest,Selection>();var leafPaths=new HashSet<String>();
      for(int branch=0;nodes.size()<count;branch++) {
        String path="TOP";String parent="TOP";
        for(int depth=1;depth<=32 && nodes.size()<count;depth++) {
          path+="/b"+branch+"d"+depth;String material="M"+(branch%20)+"-"+depth;
          nodes.add(node(path,material,parent,"0.125"));parent=material;
          prices.put(request(material),price(depth==3 && branch%20%3==0 || depth==32 && branch%20%3==1,Set.of()));
          if(depth==32 || nodes.size()==count)leafPaths.add(path);
        }
      }
      var candidates=candidates(prices);var samples=new ArrayList<Double>();MonthlyAdoptionPlan last=null;
      var memory=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
      long allocationBefore=memory.getThreadAllocatedBytes(Thread.currentThread().threadId()),peakHeap=0;
      for(int sample=-5;sample<30;sample++) {
        long start=System.nanoTime();var scope=MonthlyBomPricingScope.select(nodes,candidates);
        var ordinary=scope.ordinary().stream().filter(node -> leafPaths.contains(node.path()))
            .map(node -> new Use(node.path(),node.request(),node.quantityPerTop())).toList();
        last=MonthlyBomPricingScope.adoption(scope,ordinary,candidates);
        MonthlyBomPricingScope.quantities(last);
        double millis=(System.nanoTime()-start)/1_000_000.0;
        if(sample>=0)samples.add(millis);
        peakHeap=Math.max(peakHeap,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
      }
      var sorted=new ArrayList<>(samples);Collections.sort(sorted);double p95=sorted.get(28);
      assertThat(nodes).hasSize(count);assertThat(last.uses()).isNotEmpty();
      if(count==10000) {
        assertThat(last.suppressedPaths()).isNotEmpty();
        assertThat(p95).as("10,000-node preloaded planner p95 in milliseconds").isLessThanOrEqualTo(200);
      }
      measurements.add(Map.ofEntries(Map.entry("nodes",count),Map.entry("uniqueRequests",nodes.stream().map(Node::request).distinct().count()),
          Map.entry("pricedUses",last.uses().size()),Map.entry("monthlyCandidates",last.selections().size()),Map.entry("suppressedPaths",last.suppressedPaths().size()),
          Map.entry("p50",sorted.get(14)),Map.entry("p95",p95),Map.entry("p99",sorted.get(29)),Map.entry("max",sorted.get(29)),
          Map.entry("elapsedMs",samples),Map.entry("databaseCalls",0),Map.entry("observedHeapUsedMaxBytes",peakHeap),
          Map.entry("threadAllocatedBytes",memory.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocationBefore),
          Map.entry("nodesPerSecond",1000.0*count*samples.size()/samples.stream().mapToDouble(Double::doubleValue).sum())));
    }
    var result=Map.of("warmups",5,"samples",30,"maxDepth",32,"javaVersion",System.getProperty("java.version"),
        "architecture",System.getProperty("os.arch"),"processors",Runtime.getRuntime().availableProcessors(),"measurements",measurements);
    var out=Path.of("target/mpp06-scope-performance.json");Files.createDirectories(out.getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),result);
  }
}
