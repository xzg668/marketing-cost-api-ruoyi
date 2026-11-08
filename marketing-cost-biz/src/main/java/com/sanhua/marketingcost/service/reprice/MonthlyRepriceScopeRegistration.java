package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceScopeMapper;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixes M historical sources and U customer/product objects before workers can claim the batch. */
@Service
public class MonthlyRepriceScopeRegistration {
  private static final int CHUNK = 200;
  private final MonthlyRepriceScopeMapper scopes;
  private final MonthlyRepriceContractMapper contracts;
  private final QuoteBomContextResolver customers;
  private final ObjectMapper json;

  public MonthlyRepriceScopeRegistration(MonthlyRepriceScopeMapper scopes,
      MonthlyRepriceContractMapper contracts, QuoteBomContextResolver customers, ObjectMapper json) {
    this.scopes=scopes; this.contracts=contracts; this.customers=customers;
    this.json=json.copy().findAndRegisterModules();
  }

  public record SourceSnapshot(QuoteCostRunVersion version, OaForm form, OaFormItem item,
      com.sanhua.marketingcost.service.pricing.monthly.AnnualUsageInput annualUsage) {}
  public record SelectedInput(OaForm form, OaFormItem item,
      com.sanhua.marketingcost.service.pricing.monthly.AnnualUsageInput annualUsage) {}

  public void register(MonthlyRepriceBatch batch, List<QuoteCostRunVersion> versions) {
    requireTransaction();
    var items = new HashMap<Long,OaFormItem>();
    chunks(versions.stream().map(QuoteCostRunVersion::getOaFormItemId).distinct().toList(),
        ids -> scopes.items(ids).forEach(i -> items.put(i.getId(),i)));
    var forms = new HashMap<Long,OaForm>();
    chunks(items.values().stream().map(OaFormItem::getOaFormId).distinct().toList(),
        ids -> scopes.forms(ids).forEach(f -> forms.put(f.getId(),f)));
    var annualUnits = new HashMap<Long,String>();
    chunks(List.copyOf(items.keySet()), ids -> scopes.annualUnits(ids).forEach(value -> {
      if (annualUnits.containsKey(value.itemId()))
        throw new IllegalStateException("报价年用量单位存在重复配置，不能固定月度来源");
      annualUnits.put(value.itemId(),value.unit());
    }));
    var snapshots=new ArrayList<SourceSnapshot>();
    for (var v:versions) {
      var item=items.get(v.getOaFormItemId());
      var form=item==null ? null : forms.get(item.getOaFormId());
      if (form==null || !Objects.equals(form.getOaNo(),v.getOaNo())
          || !Objects.equals(form.getBusinessUnitType(),batch.getBusinessUnitType())
          || !Objects.equals(item.getBusinessUnitType(),batch.getBusinessUnitType())
          || !Objects.equals(QuoteProductIdentityUtils.resolveCostingCode(item),v.getProductCode()))
        throw new IllegalStateException("成功报价的原始产品身份已变化，不能固定月度来源");
      snapshots.add(new SourceSnapshot(v,form,item,
          new com.sanhua.marketingcost.service.pricing.monthly.AnnualUsageInput(item.getAnnualVolume(),annualUnits.get(item.getId()))));
    }
    persist(batch,snapshots);
  }

  MonthlyRepriceScopeRepair.Changes repairMissingCustomers(MonthlyRepriceBatch batch,List<Long> ids,String actor) {
    requireTransaction();
    return new MonthlyRepriceScopeRepair(scopes,contracts,customers,json).repair(batch,ids,actor);
  }

  private void persist(MonthlyRepriceBatch batch,List<SourceSnapshot> snapshots) {
    var byVersion=new HashMap<Long,SourceSnapshot>();
    var candidates=new ArrayList<MonthlyRepriceObjectScope.Candidate>();
    for (var s:snapshots) {
      var v=s.version();
      byVersion.put(v.getId(),s);
      candidates.add(new MonthlyRepriceObjectScope.Candidate(
          new MonthlyRepriceContract.ObjectIdentity(v.getBusinessUnitType(),v.getOaNo(),v.getOaFormItemId()),
          v.getId(),v.getProductCode(),customers.resolveCustomer(s.form(),null),v.getTrialFinishedAt()));
    }
    var discovery=MonthlyRepriceObjectScope.discover(batch.getBusinessUnitType(),YearMonth.parse(batch.getPricingMonth()),candidates);
    var targets=new ArrayList<MonthlyRepriceContractMapper.Target>();
    var sourceKeys=new HashMap<Long,String>();
    for (var group:discovery.groups()) {
      var selected=byVersion.get(group.selected().costVersionId());
      // This is a local relation id; full identity uniqueness is enforced independently in MySQL.
      var key="CUSTOMER-"+UUID.randomUUID();
      var request=new LinkedHashMap<String,Object>();
      request.put("contractVersion",1); request.put("scopeVersion",1);
      request.put("sourceCostVersionId",selected.version().getId());
      request.put("customerIdentity",group.identity());
      request.put("selectedInput",new SelectedInput(selected.form(),selected.item(),selected.annualUsage()));
      targets.add(new MonthlyRepriceContractMapper.Target(key,selected.version(),batch.getId(),
          group.identity().customerSource().name(),group.identity().customerValue(),group.sources().size(),
          selected.item().getPackageMethod(),write(request)));
      group.sources().forEach(s -> sourceKeys.put(s.costVersionId(),key));
    }
    chunks(targets, chunk -> {
      contracts.tasksBatch(batch.getRepriceNo(),batch.getPricingMonth(),chunk);
      contracts.objectsBatch(batch.getRepriceNo(),batch.getPricingMonth(),chunk);
    });
    var objectIds=new HashMap<String,Long>();
    scopes.objects(batch.getId()).forEach(o -> objectIds.put(o.calcObjectKey(),o.id()));
    var rows=new ArrayList<MonthlyRepriceScopeMapper.SourceRow>();
    for (var s:snapshots) {
      var v=s.version(); var key=sourceKeys.get(v.getId());
      Long id=key==null ? null : Objects.requireNonNull(objectIds.get(key),"月度对象未保存");
      rows.add(new MonthlyRepriceScopeMapper.SourceRow(v.getId(),id,v.getOaNo(),v.getOaFormItemId(),
          id==null ? "MISSING_CUSTOMER" : null,write(s)));
    }
    chunks(rows, chunk -> scopes.sources(batch.getId(),chunk));
    scopes.counts(batch.getId(),discovery.sourceCount(),discovery.unresolved().size(),targets.size());
    scopes.queueCount(batch.getRepriceNo(),targets.size());
    if (!discovery.unresolved().isEmpty()) contracts.state(batch.getRepriceNo(),"FAILED");
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (JsonProcessingException ex) { throw new IllegalStateException("月度来源不可保存",ex); }
  }
  private static <T> void chunks(List<T> values,Consumer<List<T>> action) {
    for (int start=0;start<values.size();start+=CHUNK)
      action.accept(values.subList(start,Math.min(start+CHUNK,values.size())));
  }
  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("月度对象登记必须与批次受理处于同一事务");
  }
}
