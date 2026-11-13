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

  @Transactional(readOnly=true)
  public List<Entry> quotationSources(String unit,String month,String organization,String materialCode) {
    var params=parameters(unit,new Filter(month,materialCode,organization,"",1,100));
    params.put("exact",1);
    return repository.list(params).rows();
  }

  @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public void export(String unit,Filter filter,java.io.OutputStream output) {
    requireUnit(unit);
    String month=text(filter.month());
    if(month.isEmpty()) month=repository.availableMonths(unit).stream().findFirst().orElse(null);
    var headers=List.of("价格月份","料号","型号","规格","名称","组织","组织名称","价格来源","价格","单位","币种","税口径","供应商编码","供应商名称","料号属性","适用条件／依据","价格状态")
        .stream().map(List::of).toList();
    try(var writer=com.alibaba.excel.EasyExcel.write(output).autoCloseStream(false).head(headers).build()) {
      var sheet=com.alibaba.excel.EasyExcel.writerSheet("物料多形态属性价格表").build();
      if(month==null) {writer.write(List.of(),sheet);return;}
      var params=parameters(unit,new Filter(month,filter.materialCode(),filter.organization(),filter.materialAttribute(),1,100));
      params.put("limit",500);
      for(long offset=0;;offset+=500) {
        params.put("offset",offset);
        var page=repository.list(params);var data=new ArrayList<List<Object>>();
        for(var entry:page.rows()) {
          var prices=entry.prices().isEmpty() ? java.util.Collections.singletonList(new Price(null,null,null,null,null,null,null,null)) : entry.prices();
          for(var price:prices) data.add(java.util.Arrays.asList(month,entry.materialCode(),entry.materialModel(),entry.materialSpec(),
              entry.materialName(),entry.organization(),entry.organizationName(),entry.sourceLabel(),
              price.value()==null?null:new java.math.BigDecimal(price.value()),price.unit(),price.currency(),
              "INCLUDED".equals(price.taxBasis())?"含税":"EXCLUDED".equals(price.taxBasis())?"不含税":"未注明",
              price.supplierCode(),price.supplierName(),"MANUFACTURE".equals(entry.materialAttribute())?"自制件":
                  "PURCHASE".equals(entry.materialAttribute())?"采购件":"OUTSOURCE".equals(entry.materialAttribute())?"委外加工件":"",
              price.condition(),price.value()==null?("MAKE".equals(entry.sourceType())?"自制缺价":"待生成"):"已有价格"));
        }
        writer.write(data,sheet);
        if(offset+page.rows().size()>=page.total() || page.rows().isEmpty()) break;
      }
    }
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

  /** Discovery must survive rejection of the quotation choice so the quoter can classify it. */
  @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
  public List<Entry> discoverForQuotation(String unit,String month,String organization,String code,String actor) {
    var params=quotationParameters(unit,month,organization,code);
    var stored=quotationRows(params);
    if(!stored.isEmpty()) return stored;
    var found=scanner.scan(params);
    if(found.stream().map(c -> c.key().sourceType()).distinct().count()>1) {
      long generation=repository.lock(params);
      stored=quotationRows(params);
      if(!stored.isEmpty()) return stored;
      repository.append(params,found,generation,actor);
      return quotationRows(params);
    }
    return found.stream().map(c -> new Entry(0,c.key().materialCode(),c.materialName(),c.materialModel(),c.materialSpec(),
        c.key().organization(),c.organizationName(),c.key().sourceType(),sourceLabel(c.key().sourceType()),c.prices(),
        c.autoAttribute(),c.autoAttribute()!=null,0)).toList();
  }

  /** One bounded scan for quotation members, retaining existing classifications and MAKE refresh. */
  @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
  public void discoverForQuotation(String unit,String month,String organization,Collection<String> codes,
      Set<String> manufacturedCodes,String actor) {
    var requested=new LinkedHashSet<String>();
    for(String code:codes) {
      String value=text(code);
      if(value.isEmpty() || value.length()>64) throw new IllegalArgumentException("缺少或无效取价料号");
      requested.add(value);
    }
    if(requested.isEmpty()) return;
    var all=new ArrayList<>(requested);
    var scope=quotationParameters(unit,month,organization,all.getFirst());
    scope.put("code","");
    // Bound placeholders across all original source branches and keep each request's scope local.
    for(int start=0;start<all.size();start+=200) {
      var params=new HashMap<String,Object>(scope);
      var members=List.copyOf(all.subList(start,Math.min(start+200,all.size())));
      params.put("materialCodes",members);
      var stored=repository.quotationRows(params);
      var existingCodes=stored.stream().map(Entry::materialCode).collect(java.util.stream.Collectors.toSet());
      var needed=members.stream().filter(code -> !existingCodes.contains(code) || manufacturedCodes.contains(code)).toList();
      if(needed.isEmpty()) continue;
      params.put("materialCodes",needed);
      var scanned=scanner.scan(params).stream().collect(java.util.stream.Collectors.groupingBy(c -> c.key().materialCode()));
      var multiple=scanned.entrySet().stream().filter(e -> e.getValue().stream().map(c -> c.key().sourceType()).distinct().count()>1).toList();
      if(multiple.isEmpty()) continue;
      long generation=repository.lock(params);
      // Recheck after acquiring the original monthly lock; finance attributes live in their own table.
      var current=repository.quotationRows(params).stream().collect(java.util.stream.Collectors.groupingBy(Entry::materialCode));
      var added=new ArrayList<Candidate>();
      for(var entry:multiple) {
        var existing=current.getOrDefault(entry.getKey(),List.of());
        if(existing.isEmpty()) added.addAll(entry.getValue());
        else if(manufacturedCodes.contains(entry.getKey()) && entry.getValue().stream().anyMatch(c -> "MAKE".equals(c.key().sourceType()))) {
          var types=existing.stream().map(Entry::sourceType).collect(java.util.stream.Collectors.toSet());
          entry.getValue().stream().filter(c -> !types.contains(c.key().sourceType()) || "MAKE".equals(c.key().sourceType())).forEach(added::add);
        }
      }
      if(!added.isEmpty()) repository.append(params,added,generation,actor);
    }
  }

  /** Publish a computed make price, or retain its unpriced BOM source, before offering a choice. */
  @Transactional(rollbackFor=Exception.class)
  public void includeCalculatedMake(String unit,String month,String organization,String code) {
    if(!repository.installed()) return;
    var params=quotationParameters(unit,month,organization,code);
    var found=scanner.scan(params);
    if(found.stream().noneMatch(c -> "MAKE".equals(c.key().sourceType())) || found.size()<2) return;
    long generation=repository.lock(params);
    var stored=repository.list(params).rows();
    var existing=stored.stream().map(Entry::sourceType).collect(java.util.stream.Collectors.toSet());
    var added=found.stream().filter(c -> !existing.contains(c.key().sourceType()) || "MAKE".equals(c.key().sourceType())).toList();
    repository.append(params,added,generation,"MAKE_CALCULATED");
  }

  @Transactional(rollbackFor=Exception.class)
  public void includeUnpricedManufactures(String unit,String month,long itemId) {
    if(!repository.installed()) return;
    for(var material:repository.manufacturedMaterials(unit,month,itemId))
      includeCalculatedMake(unit,month,material.organization(),material.materialCode());
  }

  @Transactional(rollbackFor=Exception.class)
  public List<Entry> changeBatch(String unit,List<Change> changes,String actor) {
    if(changes==null || changes.isEmpty() || changes.size()>1000) throw new IllegalArgumentException("请选择1至1000条价格来源");
    var keys=new HashSet<String>();var result=new ArrayList<Entry>();
    for(var change:changes) {
      if(change==null || !keys.add(change.month()+"|"+change.organization()+"|"+change.materialCode()+"|"+change.sourceType()))
        throw new IllegalArgumentException("批量属性包含重复或无效来源");
      result.add(change(unit,change,actor));
    }
    return List.copyOf(result);
  }

  private List<Entry> quotationRows(Map<String,Object> params) {
    return repository.list(params).rows();
  }

  private Map<String,Object> quotationParameters(String unit,String month,String organization,String code) {
    var params=parameters(unit,new Filter(month,code,organization,"",1,100));
    if(text(code).isEmpty() || text(organization).isEmpty()) throw new IllegalArgumentException("缺少取价料号或组织");
    params.put("exact",1);params.put("multipleOnly",0);
    for(String org:List.of("210","220")) {
      var selected=months.select(unit,org,month);
      params.put("month"+org,selected==null ? month : selected.sourceMonth());
    }
    params.put("asOf",YearMonth.parse(month).atEndOfMonth());params.put("monthStart",YearMonth.parse(month).atDay(1));
    return params;
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
