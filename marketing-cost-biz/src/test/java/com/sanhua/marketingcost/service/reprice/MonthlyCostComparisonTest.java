package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonthlyCostComparisonTest {
  static final YearMonth A=YearMonth.of(2025,12),B=YearMonth.of(2026,3);
  static final ObjectIdentity OA=new ObjectIdentity("COMMERCIAL","OA-1",11);
  static final Parent PARENT=new Parent("00001","成品","DN25","M1");
  static Line line(ObjectIdentity object,String path,String code,String amount){
    return new Line(new Key(object,List.of(path),path.substring(0,path.lastIndexOf('/')),code),PARENT,"部品"+code,"件",
        LocalDateTime.of(2026,3,1,12,0),new BigDecimal("75.600"),Map.of(Metric.MATERIAL,new BigDecimal(amount)),
        "标准品",new BigDecimal("0.01"),"CMS",null);
  }
  static Panel panel(ObjectIdentity id,YearMonth month,Line... lines){return new Panel(id,month,month.getYear()*100L+month.getMonthValue(),List.of(lines));}
  static Result compare(List<Metric> metrics,Line[] a,Line[] b){return MonthlyCostComparison.compare(A,B,metrics,
      List.of(panel(OA,A,a)),List.of(panel(OA,B,b)));}
  @Test void nonAdjacentCrossYearMonthsHaveCompleteUnionWithoutSyntheticZero(){
    var result=compare(List.of(Metric.MATERIAL),new Line[]{line(OA,"/root/A","000A","100"),line(OA,"/root/B","000B","50")},
        new Line[]{line(OA,"/root/A","000A","120"),line(OA,"/root/C","000C","80")});
    assertThat(result.rows()).extracting(Row::change).containsExactly(Change.CHANGED,Change.REMOVED,Change.ADDED);
    assertThat(result.rows().getFirst().differences().get(Metric.MATERIAL)).isEqualTo(new Difference("20.000000","20.00%",null));
    for(var row:result.rows().subList(1,3)){
      assertThat(row.differences().get(Metric.MATERIAL).amount()).isEqualTo("-");
      assertThat(row.differences().get(Metric.MATERIAL).rate()).isEqualTo("-");
      assertThat(row.baseline()==null || row.comparison()==null).isTrue();
    }
  }
  @Test void reverseComparisonUsesSelectedBaselineAndReversesAdditionRemoval(){
    var before=panel(OA,A,line(OA,"/root/A","A","100"),line(OA,"/root/B","B","50"));
    var after=panel(OA,B,line(OA,"/root/A","A","120"),line(OA,"/root/C","C","80"));
    var result=MonthlyCostComparison.compare(B,A,List.of(Metric.MATERIAL),List.of(after),List.of(before));
    assertThat(result.rows().getFirst().differences().get(Metric.MATERIAL)).isEqualTo(new Difference("-20.000000","-16.67%",null));
    assertThat(result.rows()).extracting(Row::change).containsExactly(Change.CHANGED,Change.ADDED,Change.REMOVED);
  }
  @Test void timestampAndDecimalScaleAloneDoNotMarkChanged(){
    var old=line(OA,"/root/A","A","10.0000");
    var later=new Line(old.key(),old.parent(),old.name(),old.unit(),old.completedAt().plusMonths(3),
        new BigDecimal("75.6"),Map.of(Metric.MATERIAL,new BigDecimal("10")),old.productProperty(),new BigDecimal("0.010"),old.wageSource(),null);
    assertThat(compare(List.of(Metric.MATERIAL),new Line[]{old},new Line[]{later}).rows().getFirst().change()).isEqualTo(Change.UNCHANGED);
  }
  @Test void changingSelectedDifferenceProjectsDoesNotChangeContentStatus(){
    var old=line(OA,"/root/A","A","10");var changed=line(OA,"/root/A","A","11");
    for(var selected:List.of(List.<Metric>of(),List.of(Metric.MANUFACTURE),List.of(Metric.MATERIAL,Metric.MANUFACTURE)))
      assertThat(compare(selected,new Line[]{old},new Line[]{changed}).rows().getFirst().change()).isEqualTo(Change.CHANGED);
  }
  @Test void materialCodeUnderDifferentParentsAndOriginalQuotesNeverMerges(){
    var other=new ObjectIdentity("COMMERCIAL","OA-2",12);
    var a=line(OA,"/root/left/X","000X","5");var b=line(OA,"/root/right/X","000X","6");
    var c=line(other,"/root/left/X","000X","7");
    var result=MonthlyCostComparison.compare(A,B,List.of(),List.of(panel(OA,A,a,b),panel(other,A,c)),List.of(panel(other,B,c),panel(OA,B,b,a)));
    assertThat(result.rows()).hasSize(3);assertThat(result.rows()).extracting(Row::change).containsOnly(Change.UNCHANGED);
    assertThat(result.rows()).extracting(r->r.key().materialCode()).containsOnly("000X");
  }
  @Test void missingEntireObjectMonthIsNotABomAdditionOrRemoval(){
    var other=new ObjectIdentity("COMMERCIAL","OA-2",12);var a=line(OA,"/root/X","X","5");
    var b=line(other,"/root/Y","Y","6");
    var result=MonthlyCostComparison.compare(A,B,List.of(Metric.MATERIAL),List.of(panel(OA,A,a)),List.of(panel(other,B,b)));
    assertThat(result.rows()).extracting(Row::change).containsOnly(Change.MONTH_MISSING);
    assertThat(result.warnings()).hasSize(2);
  }
  @ParameterizedTest @ValueSource(booleans={false,true}) void missingEntireSelectedMonthFailsClearly(boolean missingBaseline){
    var p=panel(OA,A,line(OA,"/root/X","X","5"));var q=panel(OA,B,p.lines().getFirst());
    assertThatThrownBy(()->MonthlyCostComparison.compare(A,B,List.of(),missingBaseline?List.of():List.of(p),missingBaseline?List.of(q):List.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有成功核算结果");
  }
  @Test void sameMonthAndDuplicateIdentitiesDoNotProduceMisleadingComparison(){
    var row=line(OA,"/root/X","X","5");var p=panel(OA,A,row);
    assertThatThrownBy(()->MonthlyCostComparison.compare(A,A,List.of(),List.of(p),List.of(p))).hasMessageContaining("不同");
    assertThatThrownBy(()->MonthlyCostComparison.compare(A,B,List.of(),List.of(p,p),List.of(panel(OA,B,row)))).hasMessageContaining("一个完整");
    assertThatThrownBy(()->compare(List.of(),new Line[]{row,row},new Line[]{row})).hasMessageContaining("身份重复");
  }
  @Test void actualZeroSubitemHasDifferenceAmountButNoInventedPercentage(){
    var result=compare(List.of(Metric.MATERIAL),new Line[]{line(OA,"/root/X","X","0")},new Line[]{line(OA,"/root/X","X","2.35")});
    var diff=result.rows().getFirst().differences().get(Metric.MATERIAL);
    assertThat(diff.amount()).isEqualTo("2.350000");assertThat(diff.rate()).isNull();assertThat(diff.explanation()).contains("分项为0");
  }
  @Test void missingOptionalSubitemNeverBecomesZero(){
    var result=compare(List.of(Metric.MANUFACTURE),new Line[]{line(OA,"/root/X","X","1")},new Line[]{line(OA,"/root/X","X","1")});
    var diff=result.rows().getFirst().differences().get(Metric.MANUFACTURE);
    assertThat(diff.amount()).isNull();assertThat(diff.rate()).isNull();assertThat(diff.explanation()).contains("未记录");
  }
  @Test void savedCmsReferenceAndUnapprovedContentAffectStatusWithoutConsultingOa(){
    var a=line(OA,"/root/X","X","10");
    var b=new Line(a.key(),a.parent(),a.name(),a.unit(),a.completedAt(),a.financeCuPrice(),a.amounts(),a.productProperty(),a.lossRate(),"CMS参考000B","直接人工工资");
    var result=compare(List.of(),new Line[]{a},new Line[]{b});assertThat(result.rows().getFirst().change()).isEqualTo(Change.CHANGED);
    assertThat(result.rows().getFirst().comparison().unapproved()).isEqualTo("直接人工工资");
  }
  @Test void componentMaterialDifferencesRemainVisibleAndProductWagesAreNeverAllocatedToComponents(){
    var b=line(OA,"root/B","B","10");var c=line(OA,"root/C","C","20");
    var newerB=line(OA,"root/B","B","12");var newerC=line(OA,"root/C","C","20");
    var oldCosts=Map.of(Metric.MATERIAL,new BigDecimal("30"),Metric.DIRECT_LABOR,new BigDecimal("5"),Metric.MANUFACTURE_COST,new BigDecimal("40"));
    var newCosts=Map.of(Metric.MATERIAL,new BigDecimal("32"),Metric.DIRECT_LABOR,new BigDecimal("6"),Metric.MANUFACTURE_COST,new BigDecimal("43"));
    var pm=new ProductMonth(1,PARENT,b.completedAt(),b.financeCuPrice(),oldCosts,"标准品",b.lossRate(),"CMS",null);
    var pn=new ProductMonth(2,PARENT,b.completedAt().plusMonths(3),b.financeCuPrice(),newCosts,"标准品",b.lossRate(),"CMS",null);
    var r=MonthlyCostComparison.compare(A,B,List.of(Metric.MATERIAL,Metric.DIRECT_LABOR,Metric.MANUFACTURE_COST),
        List.of(new Panel(OA,A,1,List.of(b,c),pm)),List.of(new Panel(OA,B,2,List.of(newerB,newerC),pn)));
    assertThat(r.rows().getFirst().differences().get(Metric.MATERIAL).amount()).isEqualTo("2.000000");
    assertThat(r.rows().get(1).differences().get(Metric.MATERIAL).amount()).isEqualTo("0.000000");
    assertThat(r.rows()).allSatisfy(row->{
      assertThat(row.baseline().amounts()).containsOnlyKeys(Metric.MATERIAL);
      assertThat(row.differences().get(Metric.DIRECT_LABOR).amount()).isNull();
      assertThat(row.differences().get(Metric.DIRECT_LABOR).explanation()).contains("所属产品");
    });
    assertThat(r.products()).hasSize(1);
    assertThat(r.products().getFirst().differences().get(Metric.DIRECT_LABOR).amount()).isEqualTo("1.000000");
    assertThat(r.products().getFirst().differences().get(Metric.MANUFACTURE_COST).amount()).isEqualTo("3.000000");
    assertThat(r.rows().get(1).change()).isEqualTo(Change.UNCHANGED);
  }
  @Test void aPageWithoutBaselineObjectsCanUseVerifiedGlobalMonthCoverage(){
    var row=line(OA,"root/B","B","10");
    var r=MonthlyCostComparison.comparePage(A,B,List.of(Metric.MATERIAL),List.of(),List.of(panel(OA,B,row)),Set.of(A,B));
    assertThat(r.rows().getFirst().change()).isEqualTo(Change.MONTH_MISSING);
  }

}
