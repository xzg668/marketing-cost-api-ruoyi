package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCheckMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Checks saved output, not today's prices. Work is paged; publication separately fences its revision. */
@Service
public class MonthlyRepriceCheckService {
  public record Report(String repriceNo,long resultRevision,int objects,int checkedSuccess,
      boolean passed,List<String> problems,Map<String,Object> evidence) {
    public Report {
      Objects.requireNonNull(repriceNo);problems=List.copyOf(problems);evidence=Map.copyOf(evidence);
      if(resultRevision<0 || objects<0 || checkedSuccess<0 || checkedSuccess>objects || passed!=problems.isEmpty())
        throw new IllegalArgumentException("检查报告范围或状态不一致");
    }
  }
  private final MonthlyRepriceContractMapper contracts;
  private final MonthlyRepriceCheckMapper query;
  private final MonthlyCostVersionService costs;
  private final ObjectMapper json;

  public MonthlyRepriceCheckService(MonthlyRepriceContractMapper contracts,MonthlyRepriceCheckMapper query,
      MonthlyCostVersionService costs,ObjectMapper json) {
    this.contracts=contracts;this.query=query;this.costs=costs;this.json=json;
  }

  public Report verify(MonthlyRepriceBatch batch) {
    String no=batch.getRepriceNo();
    var problems=new ArrayList<String>();
    var uncalculated=new ArrayList<Map<String,Object>>();
    var versions=new ArrayList<Map<String,Object>>();
    var counts=query.counts(no);
    if(counts.tasks()!=batch.getTotalCount() || counts.results()!=counts.tasks() || counts.duplicateVersions()!=0)
      problem(problems,"调价对象、任务或结果集合不一致");
    if(query.inconsistentSources(batch.getId())!=0) problem(problems,"原报价来源、客户归并或来源数量无法对账");
    var digest=sha256();
    int objects=0,success=0,failed=0,checked=0,pages=0;
    long after=0;
    while(true) {
      if(Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("调价检查已中断，保留待检查状态");
      var tasks=query.tasks(no,after);
      if(tasks.isEmpty()) break;
      pages++;
      var results=query.results(no,tasks.stream().map(CostRunTask::getCalcObjectKey).toList()).stream()
          .collect(Collectors.toMap(MonthlyRepriceResult::getCalcObjectKey,Function.identity()));
      var inputs=new LinkedHashMap<Long,MonthlyRepriceCheckMapper.Input>();
      for(var task:tasks) {
        objects++;after=task.getId();
        if("SUCCESS".equals(task.getStatus())) success++;
        if("FAILED".equals(task.getStatus())) failed++;
        try {
          var input=readInput(batch,task,results.get(task.getCalcObjectKey()));
          if(input==null) {
            if(uncalculated.size()<50) uncalculated.add(Map.of("taskId",task.getId(),"objectKey",task.getCalcObjectKey(),
                "status",task.getStatus(),"reason",Objects.toString(task.getErrorMessage(),"尚未完成核算")));
          } else inputs.put(input.version(),input);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | IllegalStateException failure) {
          problem(problems,task.getOaNo()+" / "+task.getProductCode()+"："+failure.getMessage());
        }
      }
      if(inputs.isEmpty()) continue;
      final Map<Long,MonthlyCostVersionService.Stored> saved;
      try {saved=costs.readBatch(batch.getBusinessUnitType(),List.copyOf(inputs.keySet()));}
      catch(IllegalArgumentException | IllegalStateException failure) {
        problem(problems,"任务页截至"+after+"的已保存成本依据异常："+failure.getMessage());continue;
      }
      var evidence=query.evidence(no,batch.getPricingMonth(),List.copyOf(inputs.values())).stream()
          .collect(Collectors.toMap(MonthlyRepriceCheckMapper.Evidence::version,Function.identity()));
      for(var task:tasks) {
        if(!inputs.containsKey(task.getCostRunVersionId())) continue;
        try {
          var stored=saved.get(task.getCostRunVersionId());var v=stored.version();
          validateSaved(batch,task,results.get(task.getCalcObjectKey()),stored,evidence.get(v.getId()));
          checked++;
          digest.update((task.getId()+":"+task.getCalcObjectKey()+":"+v.getId()+":"+v.getTotalCost()+"\n").getBytes(StandardCharsets.UTF_8));
          if(versions.size()<50) versions.add(Map.of("versionId",v.getId(),"parts",stored.parts().size(),
              "costs",stored.costs().size(),"monthlyFacts",stored.snapshot().calculation().adoptedPrices().size()));
        } catch(IllegalArgumentException | IllegalStateException failure) {
          problem(problems,task.getOaNo()+" / "+task.getProductCode()+"："+failure.getMessage());
        }
      }
    }
    if(objects!=batch.getTotalCount() || success!=batch.getSuccessCount() || failed!=batch.getFailedCount() || checked!=success)
      problem(problems,"实际对象/成功/失败数量与批次进度不一致");
    var current=contracts.readBatch(no);
    if(current==null || !Objects.equals(current.getResultRevision(),batch.getResultRevision()))
      problem(problems,"检查期间结果修订已变化");
    var evidence=new LinkedHashMap<String,Object>();
    evidence.put("schemaVersion",2);evidence.put("objects",objects);evidence.put("resultRevision",batch.getResultRevision());
    evidence.put("checkedSuccess",checked);evidence.put("uncalculatedObjects",objects-success);
    evidence.put("unresolvedSources",Objects.requireNonNullElse(batch.getUnresolvedSourceCount(),0));
    evidence.put("pages",pages);evidence.put("pageSize",64);evidence.put("versions",List.copyOf(versions));
    evidence.put("checkedVersionsSha256",HexFormat.of().formatHex(digest.digest()));
    evidence.put("uncalculatedSample",List.copyOf(uncalculated));evidence.put("sampleLimit",50);
    evidence.put("problems",List.copyOf(problems));
    return new Report(no,batch.getResultRevision(),objects,checked,problems.isEmpty(),problems,evidence);
  }
  private MonthlyRepriceCheckMapper.Input readInput(MonthlyRepriceBatch batch,CostRunTask task,
      MonthlyRepriceResult result) throws com.fasterxml.jackson.core.JsonProcessingException {
    if(result==null || !Objects.equals(result.getOaNo(),task.getOaNo())
        || !Objects.equals(result.getOaFormItemId(),task.getOaFormItemId())
        || !Objects.equals(result.getProductCode(),task.getProductCode())
        || !Objects.equals(result.getBusinessUnitType(),batch.getBusinessUnitType())
        || !Objects.equals(task.getBusinessUnitType(),batch.getBusinessUnitType())
        || !Objects.equals(result.getPricingMonth(),batch.getPricingMonth())
        || !Objects.equals(task.getPricingMonth(),batch.getPricingMonth())
        || (Integer.valueOf(1).equals(batch.getScopeVersion())
          && (!Objects.equals(result.getReuseBatchId(),batch.getId())
            || !Objects.equals(result.getCustomerIdentityValue(),task.getCustomerName()))))
      throw new IllegalStateException("任务、结果与客户产品归属不一致");
    var request=json.readTree(task.getRequestSnapshotJson());
    var source=request.path("sourceCostVersionId");
    if(!source.isIntegralNumber() || !source.canConvertToLong() || source.longValue()<=0
        || !Objects.equals(result.getSourceCostVersionId(),source.longValue()))
      throw new IllegalStateException("原报价版本依据不一致");
    if(!"SUCCESS".equals(task.getStatus())) {
      if(!Set.of("PENDING","RUNNING","FAILED").contains(task.getStatus())
          || task.getCostRunVersionId()!=null || result.getCostRunVersionId()!=null
          || !"PENDING".equals(result.getCalcStatus()) || result.getTotalCost()!=null)
        throw new IllegalStateException("未核算任务错误关联了成本或状态");
      return null;
    }
    var context=request.path("bomInput").path("context");
    long snapshot=request.path("bomReady").path("syncRecordId").asLong();
    if(task.getCostRunVersionId()==null || !Objects.equals(task.getCostRunVersionId(),result.getCostRunVersionId())
        || !"SUCCESS".equals(result.getCalcStatus()) || snapshot<=0
        || !batch.getPricingMonth().equals(context.path("costPeriodMonth").asText()))
      throw new IllegalStateException("成功任务缺少完整版本或目标月BOM");
    return new MonthlyRepriceCheckMapper.Input(task.getCostRunVersionId(),snapshot,
        task.getOaFormItemId(),task.getProductCode(),context.path("organization").path("priceOrgCode").asText());
  }
  private static void validateSaved(MonthlyRepriceBatch batch,CostRunTask task,MonthlyRepriceResult result,
      MonthlyCostVersionService.Stored stored,MonthlyRepriceCheckMapper.Evidence proof) {
    var v=stored.version();
    if(!batch.getRepriceNo().equals(v.getRepriceNo()) || !batch.getPricingMonth().equals(v.getPricingMonth())
        || !task.getOaNo().equals(v.getOaNo()) || !task.getOaFormItemId().equals(v.getOaFormItemId())
        || !task.getProductCode().equals(v.getProductCode()) || !"SUCCESS".equals(v.getStatus())
        || v.getTrialFinishedAt()==null || v.getAlgorithmVersion()==null || v.getFeePolicyVersion()==null
        || result.getTotalCost()==null || v.getTotalCost()==null || result.getTotalCost().compareTo(v.getTotalCost())!=0
        || stored.snapshot()==null || stored.parts().isEmpty() || stored.costs().isEmpty()
        || !Objects.equals(v.getPartItemCount(),stored.parts().size()) || !Objects.equals(v.getCostItemCount(),stored.costs().size()))
      throw new IllegalStateException("对象、月份与完整保存版本不一致");
    if(proof==null || proof.bom()!=1 || proof.parts()!=stored.parts().size())
      throw new IllegalStateException("目标月BOM或原报价依据不完整");
    if(stored.parts().stream().anyMatch(p->!owned(v,p.getOaNo(),p.getOaFormItemId(),p.getProductCode(),p.getBusinessUnitType()))
        || stored.costs().stream().anyMatch(c->!owned(v,c.getOaNo(),c.getOaFormItemId(),c.getProductCode(),c.getBusinessUnitType())))
      throw new IllegalStateException("保存成本明细归属不一致");
    MonthlyCostVersionService.validateStoredRows(stored);
  }
  private static void problem(List<String> problems,String message) {if(problems.size()<50)problems.add(message);}
  private static boolean owned(QuoteCostRunVersion v,String oa,Long item,String product,String unit) {
    return Objects.equals(v.getOaNo(),oa) && Objects.equals(v.getOaFormItemId(),item)
        && Objects.equals(v.getProductCode(),product) && Objects.equals(v.getBusinessUnitType(),unit);
  }
  private static MessageDigest sha256() {
    try{return MessageDigest.getInstance("SHA-256");}
    catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
