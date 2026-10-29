package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
import java.math.*;
import java.time.*;
import java.util.*;

/** Pure comparison of complete saved panels. It neither adopts prices nor calculates new costs. */
public final class MonthlyCostComparison {
  private MonthlyCostComparison() {}
  public enum Metric {
    MATERIAL("材料费"), DIRECT_LABOR("直接人工工资"), INDIRECT_LABOR("辅助人工工资"),
    MANUFACTURE("制造费用"), LOSS("净损失成本"), MANUFACTURE_COST("制造成本");
    public final String label; Metric(String label){this.label=label;}
  }
  public enum Change {
    UNCHANGED(""), CHANGED("变化"), ADDED("新增"), REMOVED("减少"), MONTH_MISSING("该月无成功核算");
    public final String label; Change(String label){this.label=label;}
  }
  public record Key(ObjectIdentity object,List<String> pricingPaths,String parentPath,String materialCode) {
    public Key {
      Objects.requireNonNull(object);Objects.requireNonNull(pricingPaths);
      if(pricingPaths.isEmpty() || new HashSet<>(pricingPaths).size()!=pricingPaths.size()
          || pricingPaths.stream().anyMatch(p->p==null || p.isBlank()) || materialCode==null || materialCode.isBlank())
        throw new IllegalArgumentException("比较行必须具有真实保存路径和物料身份");
      pricingPaths=pricingPaths.stream().sorted().toList();
      parentPath=parentPath==null ? "" : parentPath;
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
  public record Row(Key key,Parent parent,Line baseline,Line comparison,Change change,
      Map<Metric,Difference> differences) {
    public Row {differences=Collections.unmodifiableMap(new LinkedHashMap<>(differences));}
  }
  public record Product(String objectKey,ObjectIdentity object,Parent parent,ProductMonth baseline,
      ProductMonth comparison,Change change,Map<Metric,Difference> differences) {
    public Product {differences=Collections.unmodifiableMap(new LinkedHashMap<>(differences));}
  }
  public record Result(YearMonth baselineMonth,YearMonth comparisonMonth,List<Metric> metrics,
      List<Row> rows,List<Product> products,List<String> warnings) {
    public Result {metrics=List.copyOf(metrics);rows=List.copyOf(rows);products=List.copyOf(products);warnings=List.copyOf(warnings);}
  }

  public static Result compare(YearMonth baselineMonth,YearMonth comparisonMonth,List<Metric> metrics,
      List<Panel> baseline,List<Panel> comparison) {
    var available=new HashSet<YearMonth>();
    if(!baseline.isEmpty()) available.add(baselineMonth);if(!comparison.isEmpty()) available.add(comparisonMonth);
    return comparePage(baselineMonth,comparisonMonth,metrics,baseline,comparison,available);
  }

  /** Global coverage is checked before product pagination, so a page can contain a missing object month. */
  public static Result comparePage(YearMonth baselineMonth,YearMonth comparisonMonth,List<Metric> metrics,
      List<Panel> baseline,List<Panel> comparison,Set<YearMonth> available) {
    Objects.requireNonNull(baselineMonth);Objects.requireNonNull(comparisonMonth);
    if(baselineMonth.equals(comparisonMonth)) throw new IllegalArgumentException("请选择两个不同的核算月份");
    if(metrics==null || new HashSet<>(metrics).size()!=metrics.size() || metrics.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("差异项目重复或无效");
    if(!available.contains(baselineMonth)) throw new IllegalArgumentException("基准月没有成功核算结果，不能作为部品新增比较");
    if(!available.contains(comparisonMonth)) throw new IllegalArgumentException("对比月没有成功核算结果，不能作为部品减少比较");
    var before=panels(baseline,baselineMonth);var after=panels(comparison,comparisonMonth);
    var objects=new LinkedHashSet<>(before.keySet());objects.addAll(after.keySet());
    var rows=new ArrayList<Row>();var products=new ArrayList<Product>();var warnings=new ArrayList<String>();
    for(var object:objects) {
      var a=before.get(object);var b=after.get(object);
      boolean missingMonth=a==null || b==null;
      if(missingMonth) warnings.add(object.oaNo()+" / 产品行"+object.oaFormItemId()+" 在"+(a==null?baselineMonth:comparisonMonth)+"没有成功核算结果，未判为BOM新增或减少");
      var ap=a==null?null:a.product();var bp=b==null?null:b.product();
      if(ap!=null || bp!=null) {
        if(!missingMonth && (ap==null || bp==null)) throw new IllegalArgumentException("产品保存依据缺失");
        var differences=new LinkedHashMap<Metric,Difference>();
        for(var metric:metrics) differences.put(metric,amountDifference(ap==null?null:ap.amounts().get(metric),
            bp==null?null:bp.amounts().get(metric),ap==null || bp==null));
        var change=missingMonth?Change.MONTH_MISSING:ap.content().equals(bp.content())?Change.UNCHANGED:Change.CHANGED;
        products.add(new Product(object.key(),object,bp==null?ap.parent():bp.parent(),ap,bp,change,differences));
      }
      var left=lines(a);var right=lines(b);var keys=new LinkedHashSet<>(left.keySet());keys.addAll(right.keySet());
      for(var key:keys) {
        var old=left.get(key);var current=right.get(key);
        Change change=missingMonth ? Change.MONTH_MISSING : old==null ? Change.ADDED : current==null ? Change.REMOVED
            : old.content().equals(current.content()) ? Change.UNCHANGED : Change.CHANGED;
        var differences=new LinkedHashMap<Metric,Difference>();
        for(var metric:metrics) differences.put(metric,old!=null && current!=null && metric!=Metric.MATERIAL && (ap!=null || bp!=null)
            ? new Difference(null,null,"该项目按所属产品比较") : difference(old,current,metric));
        rows.add(new Row(key,current==null?old.parent():current.parent(),old,current,change,differences));
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
  private static Map<ObjectIdentity,Panel> panels(List<Panel> panels,YearMonth month) {
    var result=new LinkedHashMap<ObjectIdentity,Panel>();
    for(var panel:panels) if(!month.equals(panel.month()) || result.putIfAbsent(panel.object(),panel)!=null)
      throw new IllegalArgumentException("每个原报价产品行每月只能使用一个完整成功版本");
    return result;
  }
  private static Map<Key,Line> lines(Panel panel) {
    var rows=new LinkedHashMap<Key,Line>();
    if(panel!=null) for(var line:panel.lines()) if(rows.putIfAbsent(line.key(),line)!=null)
      throw new IllegalArgumentException("保存的计价行身份重复，不能覆盖或合并");
    return rows;
  }
  private static Difference difference(Line before,Line after,Metric metric) {
    return amountDifference(before==null?null:before.amounts().get(metric),after==null?null:after.amounts().get(metric),before==null || after==null);
  }
  private static Difference amountDifference(BigDecimal a,BigDecimal b,boolean missing) {
    if(missing) return new Difference("-","-","仅一个月份存在，未补零计算");
    if(a==null || b==null) return new Difference(null,null,"该分项未记录金额，不能按零计算");
    var amount=b.subtract(a).setScale(6,RoundingMode.HALF_UP).toPlainString();
    if(a.signum()==0) return new Difference(amount,null,"基准分项为0，差异率无可用分母");
    return new Difference(amount,b.subtract(a).multiply(BigDecimal.valueOf(100)).divide(a,2,RoundingMode.HALF_UP).toPlainString()+"%",null);
  }
  private static BigDecimal decimal(BigDecimal amount){return amount==null?null:amount.stripTrailingZeros();}
}
