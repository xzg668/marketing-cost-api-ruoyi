package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Component;

/** Each component retains its saved material amount; payroll and manufacturing stay with its product. */
@Component
public class MonthlyCostComparisonProjection {
  private final MonthlyCostComparisonSalary salaries;
  public MonthlyCostComparisonProjection(MonthlyCostComparisonSalary salaries){this.salaries=salaries;}
  public Panel project(MonthlyCostComparisonInputs.Loaded input) {
    var saved=input.saved();var v=saved.version();
    var object=new ObjectIdentity(v.getBusinessUnitType(),v.getOaNo(),v.getOaFormItemId());
    var parent=parent(input);var costs=new HashMap<String,CostRunCostItem>();
    for(var cost:saved.costs()) if(Arrays.stream(Metric.values()).anyMatch(m->m.name().equals(cost.getCostCode()))
        || "ADJUSTED_MANUFACTURE_COST".equals(cost.getCostCode())) {
      if(costs.putIfAbsent(cost.getCostCode(),cost)!=null) throw new IllegalStateException("保存的产品成本项目重复");
    }
    var amounts=new EnumMap<Metric,BigDecimal>(Metric.class);
    for(var metric:Metric.values()) amounts.put(metric,costs.containsKey(metric.name())?costs.get(metric.name()).getAmount():null);
    var calc=saved.snapshot()==null?null:saved.snapshot().calculation();
    var technical=calc==null?null:calc.technicalInput();
    var salary=salaries.read(v.getProductCode(),technical,input.traces(),input.salarySources());
    var loss=costs.get("LOSS");var adjusted=costs.get("ADJUSTED_MANUFACTURE_COST");
    String property=technical==null?null:technical.productProperty();
    if(property==null && adjusted!=null && adjusted.getRemark()!=null) {
      var match=java.util.regex.Pattern.compile("(?:^|[；;])属性=([^；;]+)").matcher(adjusted.getRemark());
      if(match.find() && !"null".equals(match.group(1))) property=match.group(1);
    }
    var product=new ProductMonth(v.getId(),parent,v.getTrialFinishedAt(),v.getFinanceCuPrice(),amounts,
        property,loss==null?null:loss.getRate(),salary.source(),salary.unapproved());
    var rows=new ArrayList<Line>();
    if(calc!=null) {
      var byPreparation=new HashMap<Long,CostRunPartItem>();
      for(var part:saved.parts()) if(byPreparation.putIfAbsent(part.getPricePrepareItemId(),part)!=null)
        throw new IllegalStateException("保存部品准备身份重复");
      for(var line:calc.ordinaryInputs().materials()) if(line.explanationOf()==null) {
        var binding=saved.snapshot().preparations().get(line.path());
        if(binding==null) throw new IllegalStateException("保存部品缺少真实计价路径");
        var part=byPreparation.get(binding.financialItemId());
        rows.add(line(object,product,part,List.of(line.path()),input));
      }
      for(var line:calc.household()) {
        var path=MonthlyBomPricingScope.pathKey(line.paths().getFirst());
        var binding=saved.snapshot().preparations().get(path);
        if(binding==null) throw new IllegalStateException("家用整体部品缺少保存计价路径");
        rows.add(line(object,product,byPreparation.get(binding.financialItemId()),line.paths(),input));
      }
    } else for(var part:saved.parts()) {
      var row=input.bomRows().get(part.getBomRowId());
      // SET:v1 hashes may have been made from a physical path, a raw id or a transient row id.
      // Without the saved path, treating that opaque key as a BOM path can invent additions/removals.
      String path=row==null?null:row.getPath();
      if(path==null || path.isBlank()) throw new IllegalStateException("历史部品未保存计价路径，无法可靠跨月对应："+part.getPartCode());
      rows.add(line(object,product,part,List.of(path),input));
    }
    return new Panel(object,YearMonth.parse(v.getPricingMonth()),v.getId(),rows,product);
  }
  private Line line(ObjectIdentity object,ProductMonth product,CostRunPartItem part,List<String> paths,
      MonthlyCostComparisonInputs.Loaded input) {
    if(part==null || part.getAmount()==null) throw new IllegalStateException("保存部品或实际材料金额缺失");
    paths=paths.stream().map(MonthlyBomPricingScope::pathKey).sorted().toList();
    var parents=paths.stream().map(p->{int i=p.lastIndexOf('/');return i<0?"":p.substring(0,i);}).distinct().toList();
    String unit=null;var bom=input.bomRows().get(part.getBomRowId());if(bom!=null) unit=bom.getUnit();
    if(unit==null && input.saved().snapshot()!=null) {
      final String first=paths.getFirst();
      unit=input.saved().snapshot().calculation().effectiveUses().stream().filter(u->MonthlyBomPricingScope.pathKey(u.path()).equals(first))
          .map(u->u.request().family().unit()).filter(Objects::nonNull).findFirst().orElse(null);
    }
    return new Line(new Key(object,paths,String.join(";",parents),part.getPartCode()),product.parent(),part.getPartName(),unit,
        product.completedAt(),product.financeCuPrice(),Map.of(Metric.MATERIAL,part.getAmount()),
        product.productProperty(),product.lossRate(),product.wageSource(),product.unapproved());
  }
  private Parent parent(MonthlyCostComparisonInputs.Loaded input) {
    String code=input.saved().version().getProductCode();
    if(input.quotation()!=null) {
      var item=input.quotation().item();return new Parent(code,item.getProductName(),item.getSpec(),item.getSunlModel());
    }
    var candidates=input.roots().stream().filter(r->code.equals(r.getMaterialCode())).toList();
    if(candidates.isEmpty() && input.roots().size()==1) candidates=input.roots();
    if(candidates.size()>1) {
      var first=candidates.getFirst();
      if(candidates.stream().anyMatch(r->!Objects.equals(first.getMaterialName(),r.getMaterialName())
          || !Objects.equals(first.getMaterialSpec(),r.getMaterialSpec()) || !Objects.equals(first.getMaterialModel(),r.getMaterialModel())))
        throw new IllegalStateException("保存父件表头存在多个不同依据");
    }
    var root=candidates.isEmpty()?null:candidates.getFirst();
    return new Parent(code,root==null?null:root.getMaterialName(),root==null?null:root.getMaterialSpec(),root==null?null:root.getMaterialModel());
  }
}
