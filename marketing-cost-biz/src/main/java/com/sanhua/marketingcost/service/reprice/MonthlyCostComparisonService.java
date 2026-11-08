package com.sanhua.marketingcost.service.reprice;

import static com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.MISSING_PATH_REASON;

import com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper;
import com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper.Scope;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One read model for the page and the subsequent workbook renderer. Pages contain whole products. */
@Service
@Transactional(readOnly=true)
public class MonthlyCostComparisonService {
  public record Request(String baselineMonth,String comparisonMonth,List<Metric> metrics,
      String oaNo,Long itemId,String productCode,int page,int pageSize) {}
  public record Page(Result comparison,int page,int pageSize,long totalProducts,List<String> warnings) {
    public Page {warnings=List.copyOf(warnings);}
  }
  public record ExportScope(String unit,YearMonth baseline,YearMonth comparison,List<Metric> metrics,
      Set<YearMonth> available,long totalProducts) {
    public ExportScope {metrics=List.copyOf(metrics);available=Set.copyOf(available);}
  }
  private record Query(YearMonth baseline,YearMonth comparison,List<Metric> metrics,Scope scope) {}
  private final MonthlyCostComparisonMapper mapper;
  private final MonthlyCostComparisonInputs inputs;
  private final MonthlyCostComparisonProjection projection;
  private final PermissionService permissions;
  public MonthlyCostComparisonService(MonthlyCostComparisonMapper mapper,MonthlyCostComparisonInputs inputs,
      MonthlyCostComparisonProjection projection,PermissionService permissions) {
    this.mapper=mapper;this.inputs=inputs;this.projection=projection;this.permissions=permissions;
  }
  public Page compare(Request request) {
    var query=query(request);var available=available(query);
    long total=mapper.countObjects(query.scope());
    return project(query.scope().unit(),query.baseline(),query.comparison(),query.metrics(),available,
        total,request.page(),request.pageSize(),mapper.pageSelections(query.scope()));
  }
  /** Stream only compact saved identities once; snapshots are loaded after the result set closes. */
  public ExportScope selectForExport(Request request,java.util.function.Consumer<MonthlyCostComparisonScope.Selected> sink) {
    Objects.requireNonNull(sink);
    var query=query(request);var available=available(query);
    MonthlyCostComparisonScope.Identity[] previous={null};long[] products={0};
    var months=new HashSet<String>();
    mapper.exportSelections(query.scope(),context->{
      var selected=context.getResultObject();
      validateSelection(query.scope().unit(),query.baseline(),query.comparison(),selected);
      var identity=selected.identity();
      if(!identity.equals(previous[0])) {previous[0]=identity;products[0]++;months.clear();}
      if(!months.add(selected.pricingMonth()))throw new IllegalStateException("导出对象的月份重复");
      sink.accept(selected);
    });
    return new ExportScope(query.scope().unit(),query.baseline(),query.comparison(),query.metrics(),available,products[0]);
  }
  public Page exportPage(ExportScope scope,int page,int size,List<MonthlyCostComparisonScope.Selected> selected) {
    if(!scope.unit().equals(BusinessUnitContext.getCurrentBusinessUnitType()))
      throw new org.springframework.security.access.AccessDeniedException("导出业务单元已变化");
    if(page<1 || size<1 || size>100 || selected.size()>2*size)
      throw new IllegalArgumentException("导出分页超出范围");
    selected.forEach(value->validateSelection(scope.unit(),scope.baseline(),scope.comparison(),value));
    return project(scope.unit(),scope.baseline(),scope.comparison(),scope.metrics(),scope.available(),
        scope.totalProducts(),page,size,selected);
  }
  private Page project(String unit,YearMonth a,YearMonth b,List<Metric> metrics,Set<YearMonth> available,
      long total,int page,int size,List<MonthlyCostComparisonScope.Selected> selected) {
    var ids=selected.stream().map(MonthlyCostComparisonScope.Selected::versionId).filter(Objects::nonNull).distinct().toList();
    var loaded=inputs.load(unit,ids.isEmpty()?List.of():mapper.versions(ids));var warnings=new ArrayList<String>();
    var panels=loaded.stream().map(projection::project).toList();
    for(var panel:panels) {
      if(panel.product().parent().name()==null) warnings.add(panel.object().oaNo()+" / "+panel.product().parent().code()+"：旧版本未保存父件名称/规格，缺失信息留空");
      if(panel.product().productProperty()==null) warnings.add(panel.object().oaNo()+" / "+panel.month()+"：旧版本未保存产品属性名称，未读取当前资料补填");
      if(missingPaths(panel))warnings.add(panel.object().oaNo()+" / "+panel.product().parent().code()+"："+MISSING_PATH_REASON);
    }
    var byVersion=panels.stream().collect(java.util.stream.Collectors.toMap(Panel::versionId,java.util.function.Function.identity()));
    if(!byVersion.keySet().containsAll(ids))throw new IllegalStateException("所选成本版本已不存在或未完整读取");
    var objects=selected.stream().map(s->{
      var panel=s.versionId()==null?null:byVersion.get(s.versionId());
      if(panel!=null && missingPaths(panel)) {
        String reason=s.reason()==null || s.reason().isBlank()?MISSING_PATH_REASON:s.reason()+"；"+MISSING_PATH_REASON;
        s=new MonthlyCostComparisonScope.Selected(s.businessUnitType(),s.productCode(),s.customerSource(),s.customerValue(),s.unmatchedKey(),
            s.pricingMonth(),s.oaNo(),s.oaFormItemId(),s.versionId(),s.repriceNo(),s.resultId(),s.sourceId(),s.sourceCostVersionId(),s.sourceCount(),s.status(),reason);
      }
      return new SelectedPanel(s,panel);
    }).toList();
    var comparison=MonthlyCostComparison.comparePage(a,b,metrics,objects.stream().filter(v->a.equals(v.month())).toList(),
        objects.stream().filter(v->b.equals(v.month())).toList(),available);
    warnings.addAll(comparison.warnings());
    return new Page(comparison,page,size,total,warnings.stream().distinct().toList());
  }
  private static boolean missingPaths(Panel panel){return panel.lines().stream().anyMatch(line->line.key().unmatchedKey()!=null);}
  private Query query(Request request) {
    Objects.requireNonNull(request);
    var a=month(request.baselineMonth());var b=month(request.comparisonMonth());
    if(a.equals(b)) throw new IllegalArgumentException("请选择两个不同的核算月份");
    if(request.page()<1 || request.page()>1_000_000 || request.pageSize()<1 || request.pageSize()>100)
      throw new IllegalArgumentException("分页范围无效，每页最多100个父件");
    var metrics=request.metrics()==null?List.of(Metric.MATERIAL,Metric.MANUFACTURE_COST):List.copyOf(request.metrics());
    if(new HashSet<>(metrics).size()!=metrics.size()) throw new IllegalArgumentException("差异项目重复");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();MonthlyRepriceContract.unit(unit);
    if(request.itemId()!=null && request.itemId()<=0) throw new IllegalArgumentException("产品行ID无效");
    boolean review=MonthlyRepriceContractService.canOperate(unit) || permissions.hasAnyPermi("price:monthly-reprice:review","price:monthly-reprice:operate");
    var scope=new Scope(unit,a.toString(),b.toString(),text(request.oaNo()),request.itemId(),text(request.productCode()),
        review,(request.page()-1)*request.pageSize(),request.pageSize());
    return new Query(a,b,metrics,scope);
  }
  private Set<YearMonth> available(Query query) {
    var available=new HashSet<YearMonth>();mapper.coverage(query.scope()).forEach(v->available.add(YearMonth.parse(v.month())));
    if(!available.contains(query.baseline()))throw new IllegalArgumentException("基准月没有已保存的核算对象");
    if(!available.contains(query.comparison()))throw new IllegalArgumentException("对比月没有已保存的核算对象");
    return Set.copyOf(available);
  }
  private static void validateSelection(String unit,YearMonth a,YearMonth b,MonthlyCostComparisonScope.Selected selected) {
    if(!unit.equals(selected.businessUnitType())
        || !(a.toString().equals(selected.pricingMonth()) || b.toString().equals(selected.pricingMonth())))
      throw new IllegalStateException("导出对象超出所选业务单元或月份");
  }
  public com.sanhua.marketingcost.dto.MonthlyRepricePageResponse<com.sanhua.marketingcost.dto.MonthlyRepriceSourceDto>
      historicalSources(long versionId,int page,int size) {
    if(versionId<=0 || page<1 || page>100000 || size<1 || size>100)throw new IllegalArgumentException("来源分页范围无效");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();MonthlyRepriceContract.unit(unit);
    var versions=mapper.versions(List.of(versionId));
    if(versions.size()!=1 || !unit.equals(versions.getFirst().getBusinessUnitType()))
      throw new org.springframework.security.access.AccessDeniedException("保存来源不可查看");
    var version=versions.getFirst();boolean review=MonthlyRepriceContractService.canOperate(unit)
        || permissions.hasAnyPermi("price:monthly-reprice:review","price:monthly-reprice:operate");
    var scope=new Scope(unit,version.getPricingMonth(),version.getPricingMonth(),null,null,null,review,(page-1)*size,size);
    long total=mapper.countHistoricalSources(scope,versionId);
    if(total==0)throw new IllegalArgumentException("保存来源不可查看或已变化，请刷新对比");
    return new com.sanhua.marketingcost.dto.MonthlyRepricePageResponse<>(total,mapper.historicalSources(scope,versionId));
  }
  private static YearMonth month(String value) {
    if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("月份格式必须为YYYY-MM");
    return YearMonth.parse(value);
  }
  private static String text(String value){return value==null || value.isBlank()?null:value.trim();}
}
