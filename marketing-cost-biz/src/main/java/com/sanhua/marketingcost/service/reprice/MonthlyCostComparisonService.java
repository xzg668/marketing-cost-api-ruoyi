package com.sanhua.marketingcost.service.reprice;

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
  private final MonthlyCostComparisonMapper mapper;
  private final MonthlyCostComparisonInputs inputs;
  private final MonthlyCostComparisonProjection projection;
  private final PermissionService permissions;
  public MonthlyCostComparisonService(MonthlyCostComparisonMapper mapper,MonthlyCostComparisonInputs inputs,
      MonthlyCostComparisonProjection projection,PermissionService permissions) {
    this.mapper=mapper;this.inputs=inputs;this.projection=projection;this.permissions=permissions;
  }
  public Page compare(Request request) {
    Objects.requireNonNull(request);
    var a=month(request.baselineMonth());var b=month(request.comparisonMonth());
    if(a.equals(b)) throw new IllegalArgumentException("请选择两个不同的核算月份");
    if(request.page()<1 || request.page()>1_000_000 || request.pageSize()<1 || request.pageSize()>100)
      throw new IllegalArgumentException("分页范围无效，每页最多100个父件");
    var metrics=request.metrics()==null?List.of(Metric.MATERIAL,Metric.MANUFACTURE_COST):List.copyOf(request.metrics());
    if(new HashSet<>(metrics).size()!=metrics.size()) throw new IllegalArgumentException("差异项目重复");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();MonthlyRepriceContract.unit(unit);
    if(request.itemId()!=null && request.itemId()<=0) throw new IllegalArgumentException("产品行ID无效");
    boolean review=permissions.hasRole("BU_DIRECTOR") || permissions.hasAnyPermi("price:monthly-reprice:review","price:monthly-reprice:operate");
    var scope=new Scope(unit,a.toString(),b.toString(),text(request.oaNo()),request.itemId(),text(request.productCode()),
        review,(request.page()-1)*request.pageSize(),request.pageSize());
    var available=new HashSet<YearMonth>();mapper.coverage(scope).forEach(v->available.add(YearMonth.parse(v.month())));
    if(!available.contains(a)) throw new IllegalArgumentException("基准月没有成功核算结果，不能作为部品新增比较");
    if(!available.contains(b)) throw new IllegalArgumentException("对比月没有成功核算结果，不能作为部品减少比较");
    long total=mapper.countObjects(scope);
    var selected=mapper.pageVersions(scope);var loaded=inputs.load(unit,selected);var warnings=new ArrayList<String>();
    var panels=loaded.stream().map(projection::project).toList();
    for(var panel:panels) {
      if(panel.product().parent().name()==null) warnings.add(panel.object().oaNo()+" / "+panel.product().parent().code()+"：旧版本未保存父件名称/规格，缺失信息留空");
      if(panel.product().productProperty()==null) warnings.add(panel.object().oaNo()+" / "+panel.month()+"：旧版本未保存产品属性名称，未读取当前资料补填");
    }
    var comparison=MonthlyCostComparison.comparePage(a,b,metrics,panels.stream().filter(v->a.equals(v.month())).toList(),
        panels.stream().filter(v->b.equals(v.month())).toList(),available);
    warnings.addAll(comparison.warnings());
    return new Page(comparison,request.page(),request.pageSize(),total,warnings.stream().distinct().toList());
  }
  private static YearMonth month(String value) {
    if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("月份格式必须为YYYY-MM");
    return YearMonth.parse(value);
  }
  private static String text(String value){return value==null || value.isBlank()?null:value.trim();}
}
