package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.ingest.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceScopeRegistration.SourceSnapshot;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Incremental membership repair under the existing batch lock. Unselected rows and saved costs stay intact. */
final class MonthlyRepriceScopeRepair {
  record Changes(int resolved,int addedObjects,int successDelta,int failedDelta) {}
  private final MonthlyRepriceScopeMapper scopes;
  private final MonthlyRepriceContractMapper contracts;
  private final QuoteBomContextResolver customers;
  private final ObjectMapper json;
  MonthlyRepriceScopeRepair(MonthlyRepriceScopeMapper scopes,MonthlyRepriceContractMapper contracts,
      QuoteBomContextResolver customers,ObjectMapper json) {
    this.scopes=scopes;this.contracts=contracts;this.customers=customers;this.json=json;
  }
  Changes repair(MonthlyRepriceBatch batch,List<Long> ids,String actor) {
    if(ids.isEmpty())return new Changes(0,0,0,0);
    var rows=scopes.selectedSources(batch.getId(),ids);
    if(rows.size()!=ids.size())throw new IllegalArgumentException("所选原报价不属于该调价批次");
    var pending=rows.stream().filter(s->s.objectId()==null).toList();
    var saved=new LinkedHashMap<Long,SourceSnapshot>();
    for(var row:pending) {
      if(!"MISSING_CUSTOMER".equals(row.issue()) || row.snapshot()==null)
        throw new IllegalStateException("原报价依据不完整，请先检查批次");
      var s=read(row.snapshot());
      if(s.version().getId()!=row.versionId() || !Objects.equals(s.version().getOaNo(),row.oaNo())
          || s.version().getOaFormItemId()!=row.itemId())throw new IllegalStateException("原报价依据与来源身份不一致");
      saved.put(row.id(),s);
    }
    var forms=saved.isEmpty()?Map.<Long,OaForm>of():scopes.forms(saved.values().stream().map(s->s.form().getId()).distinct().toList())
        .stream().collect(Collectors.toMap(OaForm::getId,Function.identity()));
    var groups=new LinkedHashMap<MonthlyRepriceObjectScope.Identity,List<MonthlyRepriceScopeMapper.SavedSource>>();
    for(var row:pending) {
      var s=saved.get(row.id());var current=forms.get(s.form().getId());
      if(current==null || !Objects.equals(current.getOaNo(),s.form().getOaNo())
          || !Objects.equals(current.getBusinessUnitType(),batch.getBusinessUnitType()))
        throw new IllegalStateException("待修正客户的原报价身份已变化");
      s.form().setCustomer(current.getCustomer()); // No other mutable OA inputs may replace the accepted snapshot.
      var customer=customers.resolveCustomer(s.form(),null);
      if(customer.source()==ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK) {
        audit(batch,row,actor,Map.of(),"仍缺少客户资料，请完善原报价客户后再次核算");continue;
      }
      var identity=new MonthlyRepriceObjectScope.Identity(batch.getBusinessUnitType(),YearMonth.parse(batch.getPricingMonth()),
          s.version().getProductCode(),customer.source(),customer.value());
      groups.computeIfAbsent(identity,k->new ArrayList<>()).add(row);
    }
    int resolved=0,added=0,successDelta=0,failedDelta=0;
    for(var entry:groups.entrySet()) {
      var identity=entry.getKey();var members=entry.getValue();
      var winner=members.stream().map(r->saved.get(r.id())).max(ORDER).orElseThrow();
      var existing=scopes.customerObject(batch.getId(),identity.productCode(),identity.customerSource().name(),identity.customerValue());
      CostRunTask task=null;Long previousCost=null;
      if(existing!=null) {
        var previous=read(scopes.snapshot(batch.getId(),existing.getSourceCostVersionId()));
        task=scopes.objectTask(batch.getRepriceNo(),existing.getCalcObjectKey());
        if(task==null)throw new IllegalStateException("已登记客户对象缺少任务");
        if(ORDER.compare(previous,winner)>=0)winner=previous;
        if(!Objects.equals(existing.getSourceCostVersionId(),winner.version().getId()) && "RUNNING".equals(task.getStatus())) {
          for(var row:members)audit(batch,row,actor,Map.of("objectId",existing.getId()),"同料号、同客户正在核算，请本条结束后再试");
          continue;
        }
      }
      String key=existing==null?"CUSTOMER-"+UUID.randomUUID():existing.getCalcObjectKey();
      var target=target(batch,identity,winner,key,members.size());
      if(existing==null) {
        contracts.tasksBatch(batch.getRepriceNo(),batch.getPricingMonth(),List.of(target));
        contracts.objectsBatch(batch.getRepriceNo(),batch.getPricingMonth(),List.of(target));
        existing=scopes.customerObject(batch.getId(),identity.productCode(),identity.customerSource().name(),identity.customerValue());
        added++;
      } else {
        if(!Objects.equals(existing.getSourceCostVersionId(),winner.version().getId())) {
          previousCost=existing.getCostRunVersionId();
          if("SUCCESS".equals(task.getStatus()))successDelta--;
          if("FAILED".equals(task.getStatus()))failedDelta--;
          if(scopes.replaceSelectionTask(batch.getRepriceNo(),task.getId(),target)!=1
              || scopes.replaceSelectionResult(existing.getId(),target)!=1)throw new IllegalStateException("客户资料选择发生冲突");
        } else if("FAILED".equals(task.getStatus())) {
          failedDelta-=contracts.retry(batch.getRepriceNo(),List.of(task.getId()));
        }
        if(scopes.addMembers(existing.getId(),members.size())!=1)throw new IllegalStateException("客户来源归并发生冲突");
      }
      for(var row:members) {
        if(scopes.resolve(batch.getId(),row.id(),existing.getId(),write(saved.get(row.id())))!=1)
          throw new IllegalStateException("原报价来源归并发生冲突");
        var after=new LinkedHashMap<String,Object>();after.put("objectId",existing.getId());
        after.put("selectedSourceVersionId",winner.version().getId());after.put("previousCostVersionId",previousCost);
        audit(batch,row,actor,after,"客户资料已补齐，按该客户最新成功OA资料归并；既有保存成本版本保留");resolved++;
      }
    }
    if(resolved>0) {
      scopes.counts(batch.getId(),batch.getSourceCount(),batch.getUnresolvedSourceCount()-resolved,batch.getTotalCount()+added);
      scopes.queueCount(batch.getRepriceNo(),batch.getTotalCount()+added);
    }
    return new Changes(resolved,added,successDelta,failedDelta);
  }
  private MonthlyRepriceContractMapper.Target target(MonthlyRepriceBatch b,MonthlyRepriceObjectScope.Identity identity,
      SourceSnapshot s,String key,int members) {
    return new MonthlyRepriceContractMapper.Target(key,s.version(),b.getId(),identity.customerSource().name(),identity.customerValue(),members,
        s.item().getPackageMethod(),write(Map.of("contractVersion",1,"scopeVersion",1,"sourceCostVersionId",s.version().getId(),
            "customerIdentity",identity,"selectedInput",new MonthlyRepriceScopeRegistration.SelectedInput(s.form(),s.item(),s.annualUsage()))));
  }
  private void audit(MonthlyRepriceBatch b,MonthlyRepriceScopeMapper.SavedSource row,String actor,Map<String,Object> after,String message) {
    scopes.recordSourceAttempt(b,row.id(),actor,write(Map.of("sourceVersionId",row.versionId())),write(after),message);
  }
  private static final Comparator<SourceSnapshot> ORDER=Comparator.comparing((SourceSnapshot s)->s.version().getTrialFinishedAt())
      .thenComparing(s->s.version().getId());
  private SourceSnapshot read(String value) {
    try{return json.readValue(value,SourceSnapshot.class);}
    catch(JsonProcessingException | IllegalArgumentException ex){throw new IllegalStateException("固定来源不可读取",ex);}
  }
  private String write(Object value) {
    try{return json.writeValueAsString(value);}
    catch(JsonProcessingException ex){throw new IllegalStateException("来源修复记录不可保存",ex);}
  }
}
