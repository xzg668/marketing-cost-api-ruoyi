package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Identity;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected;
import java.math.*;
import java.time.*;
import java.util.*;

/** Pure comparison of complete saved panels. It neither adopts prices nor calculates new costs. */
public final class MonthlyCostComparison {
  private MonthlyCostComparison() {}
  public static final String MISSING_PATH_REASON="历史部品未保存计价路径，保留原金额，相关部品未自动跨月匹配";
  public enum Metric {
    MATERIAL("材料费"), DIRECT_LABOR("直接人工工资"), INDIRECT_LABOR("辅助人工工资"),
    MANUFACTURE("制造费用"), LOSS("净损失成本"), MANUFACTURE_COST("制造成本");
    public final String label; Metric(String label){this.label=label;}
  }
  public enum Change {
    UNCHANGED(""), CHANGED("变化"), ADDED("新增"), REMOVED("减少"), MONTH_MISSING("该月未核算"),
    IDENTITY_UNMATCHED("客户身份缺失，无法匹配"), PATH_UNMATCHED("历史部品待匹配");
    public final String label; Change(String label){this.label=label;}
  }
  public record Key(ObjectIdentity object,List<String> pricingPaths,String parentPath,String materialCode,String unmatchedKey) {
    public Key(ObjectIdentity object,List<String> pricingPaths,String parentPath,String materialCode) {
      this(object,pricingPaths,parentPath,materialCode,null);
    }
    public Key {
      Objects.requireNonNull(object);Objects.requireNonNull(pricingPaths);
      parentPath=parentPath==null ? "" : parentPath;
      boolean missingPath=unmatchedKey!=null;
      if((missingPath?(unmatchedKey.isBlank() || !pricingPaths.isEmpty() || !parentPath.isEmpty()):pricingPaths.isEmpty())
          || new HashSet<>(pricingPaths).size()!=pricingPaths.size()
          || pricingPaths.stream().anyMatch(p->p==null || p.isBlank()) || materialCode==null || materialCode.isBlank())
        throw new IllegalArgumentException("比较行必须具有物料身份，以及保存路径或独立历史行身份");
      pricingPaths=pricingPaths.stream().sorted().toList();
    }
  }
  public record Parent(String code,String name,String specification,String model) {}
  public record Line(Key key,Parent parent,String name,String unit,LocalDateTime completedAt,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal financeCuPrice,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(contentUsing=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Map<Metric,BigDecimal> amounts,String productProperty,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal lossRate,String wageSource,String unapproved) {
    public Line {
      Objects.requireNonNull(key);Objects.requireNonNull(parent);Objects.requireNonNull(completedAt);
      var copy=new EnumMap<Metric,BigDecimal>(Metric.class);copy.putAll(amounts);amounts=Collections.unmodifiableMap(copy);
    }
    /** Completion time and record/source IDs are provenance, not a content change. */
    private List<Object> content() {
      var fields=new ArrayList<Object>(Arrays.asList(parent,name,unit,decimal(financeCuPrice),productProperty,
          decimal(lossRate),wageSource,unapproved));
      for(var metric:Metric.values()) fields.add(decimal(amounts.get(metric)));
      return fields;
    }
  }
  public record ProductMonth(long versionId,Parent parent,LocalDateTime completedAt,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal financeCuPrice,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(contentUsing=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Map<Metric,BigDecimal> amounts,String productProperty,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal lossRate,String wageSource,String unapproved) {
    public ProductMonth {
      var copy=new EnumMap<Metric,BigDecimal>(Metric.class);copy.putAll(amounts);amounts=Collections.unmodifiableMap(copy);
    }
    private List<Object> content() {
      var result=new ArrayList<Object>(Arrays.asList(parent,decimal(financeCuPrice),productProperty,decimal(lossRate),wageSource,unapproved));
      for(var metric:Metric.values()) result.add(decimal(amounts.get(metric)));return result;
    }
  }
  public record Panel(ObjectIdentity object,YearMonth month,long versionId,List<Line> lines,ProductMonth product) {
    public Panel(ObjectIdentity object,YearMonth month,long versionId,List<Line> lines){this(object,month,versionId,lines,null);}
    public Panel {
      if(versionId<=0) throw new IllegalArgumentException("完整保存版本标识必填");
      Objects.requireNonNull(object);Objects.requireNonNull(month);lines=List.copyOf(lines);
      if(lines.stream().anyMatch(line->!object.equals(line.key().object())))
        throw new IllegalArgumentException("比较部品不能跨原报价产品行");
    }
  }
  public record Difference(String amount,String rate,String explanation) {}
  /** A pending selection has no saved panel; its cost is never filled from another version. */
  public record SelectedPanel(Selected selection,Panel panel) {
    public SelectedPanel {
      Objects.requireNonNull(selection);selection.identity();
      if(panel==null && selection.versionId()!=null)throw new IllegalArgumentException("所选保存成本缺失");
      if(panel!=null && (!"SUCCESS".equals(selection.status()) || !Objects.equals(selection.versionId(),panel.versionId())
          || !selection.owner().equals(panel.object()) || !selection.month().equals(panel.month())
          || (panel.product()!=null && !selection.productCode().equals(panel.product().parent().code()))
          || panel.lines().stream().anyMatch(line->!selection.productCode().equals(line.parent().code()))))
        throw new IllegalArgumentException("所选客户月份与保存成本归属不一致");
    }
    public YearMonth month(){return selection.month();}
  }
  public record Row(String objectKey,Key key,Parent parent,Line baseline,Line comparison,Change change,
      Map<Metric,Difference> differences) {
    public Row {differences=Collections.unmodifiableMap(new LinkedHashMap<>(differences));}
  }
  public record Product(String objectKey,ObjectIdentity object,Parent parent,ProductMonth baseline,
      ProductMonth comparison,Change change,Map<Metric,Difference> differences,Identity identity,Selected baselineState,Selected comparisonState) {
    public Product {differences=Collections.unmodifiableMap(new LinkedHashMap<>(differences));}
  }
  public record Result(YearMonth baselineMonth,YearMonth comparisonMonth,List<Metric> metrics,
      List<Row> rows,List<Product> products,List<String> warnings) {
    public Result {metrics=List.copyOf(metrics);rows=List.copyOf(rows);products=List.copyOf(products);warnings=List.copyOf(warnings);}
  }

  public static Result compare(YearMonth baselineMonth,YearMonth comparisonMonth,List<Metric> metrics,
      List<SelectedPanel> baseline,List<SelectedPanel> comparison) {
    var available=new HashSet<YearMonth>();
    if(!baseline.isEmpty()) available.add(baselineMonth);if(!comparison.isEmpty()) available.add(comparisonMonth);
    return comparePage(baselineMonth,comparisonMonth,metrics,baseline,comparison,available);
  }

  /** Global coverage is checked before product pagination, so a page can contain a missing object month. */
  public static Result comparePage(YearMonth baselineMonth,YearMonth comparisonMonth,List<Metric> metrics,
      List<SelectedPanel> baseline,List<SelectedPanel> comparison,Set<YearMonth> available) {
    Objects.requireNonNull(baselineMonth);Objects.requireNonNull(comparisonMonth);
    if(baselineMonth.equals(comparisonMonth)) throw new IllegalArgumentException("请选择两个不同的核算月份");
    if(metrics==null || new HashSet<>(metrics).size()!=metrics.size() || metrics.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("差异项目重复或无效");
    if(!available.contains(baselineMonth)) throw new IllegalArgumentException("基准月没有已保存的核算对象");
    if(!available.contains(comparisonMonth)) throw new IllegalArgumentException("对比月没有已保存的核算对象");
    var before=panels(baseline,baselineMonth);var after=panels(comparison,comparisonMonth);
    var objects=new LinkedHashSet<>(before.keySet());objects.addAll(after.keySet());
    var rows=new ArrayList<Row>();var products=new ArrayList<Product>();var warnings=new ArrayList<String>();
    for(var object:objects) {
      var a=before.get(object);var b=after.get(object);
      boolean missingMonth=a==null || b==null || a.panel()==null || b.panel()==null;
      var representative=b==null?a.selection():b.selection();
      if(!object.matched())warnings.add(representative.oaNo()+"：客户身份缺失，无法跨月匹配，未猜测归并");
      else if(missingMonth) warnings.add(object.productCode()+" / "+object.customerValue()+"：存在未核算月份，未判为BOM新增或减少");
      var ap=a==null || a.panel()==null?null:a.panel().product();var bp=b==null || b.panel()==null?null:b.panel().product();
      {
        if(!missingMonth && ((ap==null)!=(bp==null))) throw new IllegalArgumentException("产品保存依据缺失");
        var differences=new LinkedHashMap<Metric,Difference>();
        for(var metric:metrics) differences.put(metric,amountDifference(ap==null?null:ap.amounts().get(metric),
            bp==null?null:bp.amounts().get(metric),ap==null || bp==null));
        var change=!object.matched()?Change.IDENTITY_UNMATCHED:missingMonth?Change.MONTH_MISSING:
            ap==null || ap.content().equals(bp.content())?Change.UNCHANGED:Change.CHANGED;
        Parent parent=bp!=null?bp.parent():ap!=null?ap.parent():new Parent(object.productCode(),null,null,null);
        products.add(new Product(object.key(),representative.owner(),parent,ap,bp,change,differences,object,
            a==null?null:a.selection(),b==null?null:b.selection()));
      }
      var left=lines(a);var right=lines(b);var keys=new LinkedHashSet<>(left.keySet());keys.addAll(right.keySet());
      boolean incompletePaths=left.keySet().stream().anyMatch(key->key.unmatchedKey()!=null)
          || right.keySet().stream().anyMatch(key->key.unmatchedKey()!=null);
      for(var key:keys) {
        var old=left.get(key);var current=right.get(key);
        boolean pathUnmatched=key.unmatchedKey()!=null || (incompletePaths && (old==null || current==null));
        Change change=!object.matched()?Change.IDENTITY_UNMATCHED:missingMonth ? Change.MONTH_MISSING : old==null ? Change.ADDED : current==null ? Change.REMOVED
            : old.content().equals(current.content()) ? Change.UNCHANGED : Change.CHANGED;
        if(pathUnmatched && object.matched() && !missingMonth)change=Change.PATH_UNMATCHED;
        var differences=new LinkedHashMap<Metric,Difference>();
        for(var metric:metrics) differences.put(metric,pathUnmatched?new Difference("-","-",MISSING_PATH_REASON)
            :old!=null && current!=null && metric!=Metric.MATERIAL && (ap!=null || bp!=null)
            ? new Difference(null,null,"该项目按所属产品比较") : difference(old,current,metric));
        rows.add(new Row(object.key(),current==null?old.key():current.key(),current==null?old.parent():current.parent(),old,current,change,differences));
      }
    }
    rows.sort(Comparator.comparing((Row r)->Objects.toString(r.parent().code(),""))
        .thenComparing(r->r.key().object().oaNo()).thenComparingLong(r->r.key().object().oaFormItemId())
        .thenComparing(r->r.key().parentPath()).thenComparing(r->String.join("\u0000",r.key().pricingPaths()))
        .thenComparing(r->r.key().materialCode()));
    products.sort(Comparator.comparing((Product p)->Objects.toString(p.parent().code(),""))
        .thenComparing(p->p.object().oaNo()).thenComparingLong(p->p.object().oaFormItemId()));
    return new Result(baselineMonth,comparisonMonth,metrics,rows,products,warnings);
  }
  private static Map<Identity,SelectedPanel> panels(List<SelectedPanel> panels,YearMonth month) {
    var result=new LinkedHashMap<Identity,SelectedPanel>();
    for(var panel:panels) if(!month.equals(panel.month()) || result.putIfAbsent(panel.selection().identity(),panel)!=null)
      throw new IllegalArgumentException("每个客户产品每月只能使用一个完整核算对象");
    return result;
  }
  private record PartIdentity(List<String> pricingPaths,String parentPath,String materialCode,String unmatchedKey) {}
  private static Map<PartIdentity,Line> lines(SelectedPanel selected) {
    var rows=new LinkedHashMap<PartIdentity,Line>();
    if(selected!=null && selected.panel()!=null) for(var line:selected.panel().lines()) {
      var key=line.key();
      if(rows.putIfAbsent(new PartIdentity(key.pricingPaths(),key.parentPath(),key.materialCode(),key.unmatchedKey()),line)!=null)
      throw new IllegalArgumentException("保存的计价行身份重复，不能覆盖或合并");
    }
    return rows;
  }
  private static Difference difference(Line before,Line after,Metric metric) {
    return amountDifference(before==null?null:before.amounts().get(metric),after==null?null:after.amounts().get(metric),before==null || after==null);
  }
  private static Difference amountDifference(BigDecimal a,BigDecimal b,boolean missing) {
    if(missing) return new Difference("-","-","至少一个月份没有已核算金额，未补零计算");
    if(a==null || b==null) return new Difference(null,null,"该分项未记录金额，不能按零计算");
    var amount=b.subtract(a).setScale(6,RoundingMode.HALF_UP).toPlainString();
    if(a.signum()==0) return new Difference(amount,null,"基准分项为0，差异率无可用分母");
    return new Difference(amount,b.subtract(a).multiply(BigDecimal.valueOf(100)).divide(a,2,RoundingMode.HALF_UP).toPlainString()+"%",null);
  }
  private static BigDecimal decimal(BigDecimal amount){return amount==null?null:amount.stripTrailingZeros();}
}
