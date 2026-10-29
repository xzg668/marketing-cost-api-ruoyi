package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceWorkflowMapper;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.util.*;
import org.springframework.stereotype.Service;

/** Checks saved output and its immutable inputs. Public-source refreshes do not rewrite old results. */
@Service
public class MonthlyRepriceCheckService {
  public record Report(boolean passed,List<String> problems,Map<String,Object> evidence) {}
  private final MonthlyRepriceContractMapper contracts;
  private final MonthlyRepriceWorkflowMapper workflow;
  private final MonthlyCostVersionService costs;
  private final ObjectMapper json;

  public MonthlyRepriceCheckService(MonthlyRepriceContractMapper contracts,MonthlyRepriceWorkflowMapper workflow,
      MonthlyCostVersionService costs,ObjectMapper json) {
    this.contracts=contracts;this.workflow=workflow;this.costs=costs;this.json=json;
  }

  public Report verify(MonthlyRepriceBatch batch) {
    var problems=new ArrayList<String>();
    var tasks=contracts.tasks(batch.getRepriceNo());
    var results=contracts.results(batch.getRepriceNo());
    var versions=new ArrayList<Map<String,Object>>();
    if (tasks.isEmpty() || tasks.size()!=batch.getTotalCount() || results.size()!=tasks.size())
      problems.add("调价对象、任务或结果不齐全");
    var seen=new HashSet<Long>();
    for (var task:tasks) {
      String object=task.getOaNo()+" / "+task.getProductCode();
      try {
        if (!"SUCCESS".equals(task.getStatus()) || task.getCostRunVersionId()==null)
          throw new IllegalStateException("尚有失败、等待资料或未完成任务");
        var result=results.stream().filter(r->r.getCalcObjectKey().equals(task.getCalcObjectKey())).findFirst().orElseThrow();
        var saved=costs.read(batch.getBusinessUnitType(),task.getCostRunVersionId());
        var version=saved.version();
        if (!seen.add(version.getId()) || !batch.getRepriceNo().equals(version.getRepriceNo())
            || !batch.getPricingMonth().equals(version.getPricingMonth())
            || !task.getOaNo().equals(version.getOaNo()) || !task.getOaFormItemId().equals(version.getOaFormItemId())
            || !Objects.equals(result.getCostRunVersionId(),version.getId())
            || result.getTotalCost()==null || result.getTotalCost().compareTo(version.getTotalCost())!=0
            || contracts.completeVersion(version.getId())!=1)
          throw new IllegalStateException("对象、月份与完整保存版本不一致");
        var request=json.readTree(task.getRequestSnapshotJson());
        long source=request.path("sourceCostVersionId").asLong();
        long snapshot=request.path("bomReady").path("syncRecordId").asLong();
        var context=request.path("bomInput").path("context");
        if (source<=0 || !Objects.equals(result.getSourceCostVersionId(),source)
            || !batch.getPricingMonth().equals(context.path("costPeriodMonth").asText())
            || workflow.bom(snapshot,batch.getPricingMonth(),task.getProductCode(),
                context.path("organization").path("priceOrgCode").asText())!=1
            || workflow.partEvidence(version.getId(),batch.getRepriceNo(),batch.getPricingMonth(),
                task.getOaFormItemId(),snapshot)!=saved.parts().size())
          throw new IllegalStateException("目标月BOM或原报价依据不完整");
        MonthlyCostVersionService.validateStoredRows(saved);
        versions.add(Map.of("versionId",version.getId(),"parts",saved.parts().size(),
            "costs",saved.costs().size(),"monthlyFacts",saved.snapshot().calculation().adoptedPrices().size()));
      } catch (Exception failure) {
        if (problems.size()<50) problems.add(object+"："+failure.getMessage());
      }
    }
    return new Report(problems.isEmpty(),List.copyOf(problems),Map.of("objects",tasks.size(),
        "resultRevision",batch.getResultRevision(),"versions",versions,"problems",problems));
  }
}
