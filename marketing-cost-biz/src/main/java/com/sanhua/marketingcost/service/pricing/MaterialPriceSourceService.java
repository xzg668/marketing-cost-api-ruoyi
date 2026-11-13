package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;

import java.time.YearMonth;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialPriceSourceService {
  private final MaterialPriceSourceRepository repository;
  private final MaterialPriceSourceScanner scanner;
  private final MaterialPriceMonthSelector months;
  public MaterialPriceSourceService(MaterialPriceSourceRepository repository, MaterialPriceSourceScanner scanner, MaterialPriceMonthSelector months) {
    this.repository=repository; this.scanner=scanner; this.months=months;
  }

  @Transactional(readOnly=true)
  public List<String> availableMonths(String unit) { requireUnit(unit); return repository.availableMonths(unit); }

  @Transactional(readOnly=true)
  public Page list(String unit, Filter filter) {
    requireUnit(unit);
    String month=text(filter.month());
    if (month.isEmpty()) {
      var available=repository.availableMonths(unit);
      if (available.isEmpty()) return new Page(null,0,List.of(),new RefreshState(0,null,null,0,0));
      month=available.getFirst();
    }
    var params=parameters(unit,new Filter(month,filter.materialCode(),filter.organization(),filter.materialAttribute(),filter.page(),filter.size()));
    return repository.list(params);
  }

  /** 来源读取与替换处于同一事务；同月份锁行使定时/手动和多实例更新串行。失败保留原表。 */
  @Transactional(rollbackFor=Exception.class)
  public RefreshState refresh(String unit,String month,String actor) {
    if (text(actor).isEmpty()) throw new IllegalArgumentException("缺少更新人");
    var params=parameters(unit,new Filter(month,"","","",1,100));
    long generation=repository.lock(params);
    params.put("multipleOnly",1);
    for (String org:List.of("210","220")) {
      var selected=months.select(unit,org,month);
      params.put("month"+org,selected==null ? month : selected.sourceMonth());
    }
    params.put("asOf",YearMonth.parse(month).atEndOfMonth());
    params.put("monthStart",YearMonth.parse(month).atDay(1));
    repository.replace(params,scanner.scan(params),generation,actor);
    return repository.state(params);
  }

  @Transactional(rollbackFor=Exception.class)
  public Entry change(String unit,Change change,String actor) {
    if (change==null || change.expectedVersion()==null || change.expectedVersion()<0 || text(change.materialCode()).isEmpty()
        || text(change.organization()).isEmpty() || text(change.sourceType()).isEmpty() || text(actor).isEmpty())
      throw new IllegalArgumentException("请提交完整月份、料号、组织、来源和属性版本");
    String attribute=text(change.materialAttribute());
    if (!attribute.isEmpty() && !Set.of("PURCHASE","OUTSOURCE").contains(attribute))
      throw new IllegalArgumentException("手动选择只支持采购件、委外加工件或留空，自制件自动带入");
    sourceLabel(change.sourceType());
    var params=parameters(unit,new Filter(change.month(),change.materialCode(),change.organization(),"",1,1));
    params.put("exact",1); params.put("sourceType",change.sourceType());
    var current=repository.list(params).rows();
    if (current.isEmpty()) throw new IllegalArgumentException("所选月份没有此条来源，请先更新清单");
    if (current.getFirst().automatic()) throw new IllegalArgumentException("自制件由计价BOM自动带入，无需选择");
    if (current.getFirst().version()!=change.expectedVersion()) throw conflict();
    params.put("attribute",attribute.isEmpty() ? null : attribute); params.put("version",change.expectedVersion()); params.put("actor",actor);
    try {
      int affected=change.expectedVersion()==0 ? repository.insert(params) : repository.update(params);
      if (affected!=1) throw conflict();
    } catch (DuplicateKeyException concurrentChange) { throw conflict(); }
    return repository.list(params).rows().getFirst();
  }

  private static Map<String,Object> parameters(String unit,Filter filter) {
    requireUnit(unit);
    String month=FactorPriceScope.month(filter.month()),org=text(filter.organization()),code=text(filter.materialCode()),attribute=text(filter.materialAttribute());
    if (!org.isEmpty() && !Set.of("210","220").contains(org)) throw new IllegalArgumentException("组织仅支持210、220");
    if (code.length()>64) throw new IllegalArgumentException("料号不能超过64个字符");
    if (!attribute.isEmpty() && !Set.of("MANUFACTURE","PURCHASE","OUTSOURCE","EMPTY").contains(attribute)) throw new IllegalArgumentException("未知料号属性");
    int size=Math.max(1,Math.min(100,filter.size()));
    var params=new HashMap<String,Object>();
    params.put("unit",unit);params.put("month",month);params.put("code",code);params.put("organization",org);
    params.put("attributeFilter",attribute);params.put("sourceType","");params.put("exact",0);
    params.put("limit",size);params.put("offset",((long)Math.max(1,filter.page())-1)*size);
    return params;
  }
  private static void requireUnit(String unit) { if (!Set.of("COMMERCIAL","HOUSEHOLD").contains(text(unit))) throw new IllegalArgumentException("请选择业务单元"); }
  private static String text(String value) { return value==null ? "" : value.trim(); }
  private static OptimisticLockingFailureException conflict() { return new OptimisticLockingFailureException("属性已被修改，请刷新后再选择"); }
}
