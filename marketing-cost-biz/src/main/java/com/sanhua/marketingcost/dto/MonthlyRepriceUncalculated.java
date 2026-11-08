package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue;
import java.time.LocalDateTime;
import java.util.List;

/** Customer results and unresolved original sources have separate identities. Costs are never fabricated. */
public final class MonthlyRepriceUncalculated {
  private MonthlyRepriceUncalculated() {}
  public record Statistics(int sources,int objects,long groups,long calculationAttempts,
      int successful,int failed,long running,long pending,int unresolvedSources) {}
  public record Row(String kind,long id,Long resultId,String repriceNo,String pricingMonth,
      String productCode,String customer,String oaNo,Long oaFormItemId,Long sourceVersionId,
      int sourceCount,String status,String reasonCode,String reason,List<Issue> materials,
      int retryCount,LocalDateTime lastAttemptAt,boolean canRetry) {
    public Row {materials=List.copyOf(materials);}
  }
  public record Page(String repriceNo,String pricingMonth,Statistics statistics,boolean canOperate,
      long total,List<Row> list,List<String> batchProblems) {
    public Page {list=List.copyOf(list);batchProblems=List.copyOf(batchProblems);}
  }
  public record RetrySelection(List<Long> taskIds,List<Long> sourceIds) {
    public RetrySelection {
      taskIds=taskIds==null?List.of():List.copyOf(taskIds);
      sourceIds=sourceIds==null?List.of():List.copyOf(sourceIds);
      if(taskIds.size()+sourceIds.size()<1 || taskIds.size()+sourceIds.size()>200
          || taskIds.stream().anyMatch(id->id<=0) || sourceIds.stream().anyMatch(id->id<=0)
          || new java.util.HashSet<>(taskIds).size()!=taskIds.size() || new java.util.HashSet<>(sourceIds).size()!=sourceIds.size())
        throw new IllegalArgumentException("请勾选1～200条不同的未核算记录");
    }
  }
}
