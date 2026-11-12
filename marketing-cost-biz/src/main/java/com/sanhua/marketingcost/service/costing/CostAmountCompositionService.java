package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.jjb.JjbVersionAccess;
import java.math.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Read one owned, saved amount. Historical evidence is never replaced by today's source data. */
@Service
@Transactional(readOnly = true)
public class CostAmountCompositionService {
  public record View(Long versionId, String costRunNo, String versionNo, String productCode,
      String pricingMonth, String costCode, String costName, String baseAmount, String rate,
      String amount, String referenceAmount, String difference, String calculation, CostAmountComposition composition) {}
  private final JjbVersionAccess access;
  private final MonthlyCostVersionService versions;
  private final CmsCostSourceEffectiveMapper salaries;
  private final AuxCostItemMapper auxiliary;
  private final CmsCompositionReader raw;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private com.sanhua.marketingcost.service.impl.PackageAmountCompositionReader packaging;
  public CostAmountCompositionService(JjbVersionAccess access, MonthlyCostVersionService versions,
      CmsCostSourceEffectiveMapper salaries, AuxCostItemMapper auxiliary, CmsCompositionReader raw) {
    this.access=access; this.versions=versions; this.salaries=salaries; this.auxiliary=auxiliary; this.raw=raw;
  }

  public View read(String run, Long costItemId, Long partItemId) {
    if ((costItemId == null) == (partItemId == null) || costItemId != null && costItemId <= 0 || partItemId != null && partItemId <= 0)
      throw new IllegalArgumentException("请选择一个保存的费用或部品金额");
    var owned=access.byRun(run); var v=owned.version();
    var saved=versions.read(owned.businessUnit(), v.getId());
    if (partItemId != null) {
      var part=saved.parts().stream().filter(row->partItemId.equals(row.getId())).findFirst()
          .orElseThrow(()->new AccessDeniedException("部品不属于此成本版本"));
      check(v,part.getCostRunVersionId(),part.getCostRunNo(),part.getProductCode(),part.getBusinessUnitType());
      var row=new LinkedHashMap<String,String>();
      row.put("sourceTable","lp_cost_run_part_item"); row.put("sourceId",part.getId().toString());
      row.put("parentCode",v.getProductCode()); row.put("materialCode",part.getPartCode()); row.put("materialName",part.getPartName());
      row.put("quantity",number(part.getQty())); row.put("unitPrice",number(part.getUnitPrice())); row.put("amountYuan",number(part.getAmount()));
      row.put("priceSource",part.getPriceSource()); row.put("period",v.getPricingMonth());
      var evidence=new CostAmountComposition("SAVED_ROWS","本次部品金额＝保存的采用单价×保存的BOM用量；焊料按实际部品逐项列示，只计入一次。",
          number(part.getAmount()),"0",null,List.of(),List.of(row));
      return view(v,part.getPartCode(),part.getPartName(),null,null,part.getAmount(),evidence);
    }
    var item=saved.costs().stream().filter(row->costItemId.equals(row.getId())).findFirst()
        .orElseThrow(()->new AccessDeniedException("费用不属于此成本版本"));
    check(v,item.getCostRunVersionId(),item.getCostRunNo(),item.getProductCode(),item.getBusinessUnitType());
    CostAmountComposition evidence=null;
    var detail=saved.snapshot()==null?null:saved.snapshot().calculation().ordinaryInputs().details().stream()
        .filter(row->item.getCostCode().equals(row.costCode())).findFirst().orElse(null);
    var base=detail==null?item.getBaseAmount():detail.baseAmount();
    var rate=detail==null?item.getRate():detail.rate();
    if(detail!=null)evidence=detail.composition();
    if(Set.of("OTHER_EXP_PACKAGE","BOM_BUCKET_PACKAGE").contains(item.getCostCode()) && packaging!=null)
      evidence=packaging.read(saved,item,evidence);
    if (evidence==null) evidence=currentReference(v,item,saved.costs(),base,rate);
    return view(v,item.getCostCode(),item.getCostName(),base,rate,item.getAmount(),evidence);
  }

  private CostAmountComposition currentReference(QuoteCostRunVersion v, CostRunCostItem item, List<CostRunCostItem> costs,
      BigDecimal savedBase,BigDecimal savedRate) {
    var code=item.getCostCode(); int year=Integer.parseInt(v.getPricingMonth().substring(0,4));
    // A technical override must never be represented as an adopted CMS source.
    if (item.getSourceTable()!=null && !item.getSourceTable().startsWith("cms_"))
      return new CostAmountComposition("SAVED_ROWS","本次金额按保存的基数及费率计算，来源为保存的技术资料或费用配置。",
          number(item.getBaseAmount()),"0",item.getRemark(),List.of(),List.of(Map.of(
              "sourceTable",item.getSourceTable(),"sourceId",String.valueOf(item.getSourceId()))));
    var dto=new CostRunCostItemDto();
    if (code.startsWith("AUX_")) {
      var rows=auxiliary.selectEffectiveAuxCostItems(year,List.of(v.getProductCode()),v.getBusinessUnitType(),v.getPricingMonth());
      rows=rows==null?List.of():rows.stream().filter(row->code.equals("AUX_"+row.getAuxSubjectCode())).toList();
      SinteredBaseSurfaceCosts.apply(rows,v.getBusinessUnitType(),auxiliary);
      var selected=rows.stream().map(CmsCostCompositions::auxiliary).toList();
      var sources=selected.stream().flatMap(row->row.sources().stream()).toList();
      var original=selected.stream().map(row->decimal(row.sourceAmount())).reduce(BigDecimal.ZERO,BigDecimal::add);
      var excluded=selected.stream().map(row->decimal(row.excludedAmount())).reduce(BigDecimal.ZERO,BigDecimal::add);
      dto.setComposition(new CostAmountComposition("CURRENT_REFERENCE",
          selected.isEmpty()?"未找到此科目的当前CMS来源":selected.getFirst().formula(),number(original),number(excluded),
          String.join("；",selected.stream().map(CostAmountComposition::note).filter(Objects::nonNull).toList()),sources,List.of()));
    } else if (Set.of("DIRECT_LABOR","INDIRECT_LABOR","OVERHAUL","TOOLING_REPAIR","WATER_POWER","DEPT_OTHER").contains(code)) {
      var all=salaries.selectSalarySources(year,v.getBusinessUnitType(),List.of(v.getProductCode()));
      var latest=new LinkedHashMap<String,CmsCostSourceEffective>();
      if(all!=null) for(var source:all) latest.put(source.getSourceType()+"|"+source.getParentCode(),source);
      var selected=latest.values().stream().filter(row->!code.endsWith("LABOR")
          || ("DIRECT_LABOR".equals(code)?"SALARY_DIRECT":"SALARY_INDIRECT").equals(row.getSourceType())).toList();
      var labor=CmsCostCompositions.labor(selected);
      if(code.endsWith("LABOR")) dto.setComposition(labor);
      else {
        var basis=new LinkedHashMap<String,String>(); basis.put("sourceTable","lp_cost_run_cost_item");
        basis.put("sourceId",item.getId().toString()); basis.put("subjectName",item.getCostName());
        basis.put("baseAmount",number(savedBase)); basis.put("rate",number(savedRate));
        basis.put("amountYuan",number(item.getAmount()));
        var laborTotal=costs.stream().filter(row->Set.of("DIRECT_LABOR","INDIRECT_LABOR").contains(row.getCostCode()))
            .map(CostRunCostItem::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        basis.put("laborTotal",number(laborTotal));
        costs.stream().filter(row->Set.of("DIRECT_LABOR","INDIRECT_LABOR").contains(row.getCostCode()))
            .forEach(row->basis.put("DIRECT_LABOR".equals(row.getCostCode())?"directLabor":"indirectLabor",number(row.getAmount())));
        var parameters=new ArrayList<Map<String,String>>(); parameters.add(basis);
        parameters.addAll(currentDepartmentParameters(v,code));
        dto.setComposition(new CostAmountComposition("CURRENT_REFERENCE","本次金额＝保存的工时基数×保存的有效费率；工时基数来自人工合计÷工时工资率。",
            number(laborTotal),"0","此版本未保存当时的工时工资率及部门费率原始参数。人工原始分项是当前核对资料。",
            labor.sources(),parameters));
      }
    } else return new CostAmountComposition("UNAVAILABLE","本次金额按保存的核算依据列示。",number(item.getBaseAmount()),"0",
        "此项目未记录CMS来源分项。",List.of(),List.of());
    raw.complete(List.of(dto),v.getBusinessUnitType());
    return dto.getComposition().currentReference();
  }

  private List<Map<String,String>> currentDepartmentParameters(QuoteCostRunVersion v,String code) {
    if(jdbc==null)return List.of();
    var subjects=switch(code) {
      case "OVERHAUL"->List.of("大修费用","大修费");
      case "TOOLING_REPAIR"->List.of("工装零星费用","工装零星修理费","零星工装费用","零星工装费");
      case "WATER_POWER"->List.of("水电费用","水电费","水电");
      default->List.of("其他费用");
    };
    var args=new ArrayList<Object>();args.add(Integer.parseInt(v.getPricingMonth().substring(0,4)));
    args.add(v.getBusinessUnitType());args.add(v.getProductCode());args.addAll(subjects);
    var rows=jdbc.queryForList("SELECT d.id,d.rate_year,d.business_division,d.expense_subject,d.manhour_rate,"
        +"d.quote_ratio,d.uplift_ratio,d.rate_calculation_mode,d.budget_amount,d.total_work_minutes,d.plan_rate,d.source_batch_no "
        +"FROM lp_department_fund_rate d WHERE d.rate_year=? AND d.business_unit_type=? "
        +"AND d.business_division IN (SELECT DISTINCT production_division FROM lp_material_master_raw "
        +"WHERE material_code=? AND active_flag=1) AND d.expense_subject IN ("
        +String.join(",",Collections.nCopies(subjects.size(),"?"))+") ORDER BY d.id DESC LIMIT 16",args.toArray());
    return rows.stream().map(row->{
      var values=new LinkedHashMap<String,String>();values.put("sourceTable","lp_department_fund_rate");
      var fields=Map.ofEntries(Map.entry("id","sourceId"),Map.entry("rate_year","period"),Map.entry("business_division","division"),
          Map.entry("expense_subject","subjectName"),Map.entry("manhour_rate","manhourRate"),Map.entry("quote_ratio","quoteRatio"),
          Map.entry("uplift_ratio","upliftRatio"),Map.entry("rate_calculation_mode","calculationMode"),Map.entry("budget_amount","budgetAmount"),
          Map.entry("total_work_minutes","totalWorkMinutes"),Map.entry("plan_rate","planRate"),Map.entry("source_batch_no","sourceBatch"));
      fields.forEach((field,label)->{var value=row.get(field);values.put(label,value==null?"":value instanceof BigDecimal d?number(d):value.toString());});
      values.put("calculationMode",DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE.equalsIgnoreCase(
          values.getOrDefault("calculationMode","").trim())?"最终费率":"计划费率乘上浮系数");
      values.put("note","当前年度／事业部配置供核对；旧版本没有保存当时参数，本行不代表当时采用值。多条候选分别列出，不自动选用。");
      return (Map<String,String>)values;
    }).toList();
  }

  private static View view(QuoteCostRunVersion v,String code,String name,BigDecimal base,BigDecimal rate,
      BigDecimal amount,CostAmountComposition evidence) {
    BigDecimal reference=null;
    if(!evidence.sources().isEmpty() && evidence.sources().stream().allMatch(s->s.rowIds()!=null && !s.rowIds().isBlank())) {
      if(code.startsWith("AUX_")) reference=decimal(evidence.sourceAmount()).subtract(decimal(evidence.excludedAmount()))
          .setScale(6,RoundingMode.HALF_UP).multiply(rate==null?BigDecimal.ONE:rate).setScale(6,RoundingMode.HALF_UP);
      else if(Set.of("DIRECT_LABOR","INDIRECT_LABOR").contains(code)) reference=decimal(evidence.sourceAmount()).setScale(6,RoundingMode.HALF_UP);
    }
    String calculation="";
    if (Set.of("OTHER_EXP_PACKAGE","BOM_BUCKET_PACKAGE").contains(code)) {
      calculation=String.join("；",evidence.rows().stream()
          .filter(row->Set.of("PACKAGE_PARENT","PACKAGE_TOTAL").contains(row.getOrDefault("kind","")))
          .map(row->row.get("calculation")).filter(Objects::nonNull).toList());
    } else if (code.startsWith("AUX_") && reference!=null) {
      var adoptedBase=decimal(evidence.sourceAmount()).subtract(decimal(evidence.excludedAmount())).setScale(6,RoundingMode.HALF_UP);
      calculation=evidence.sourceAmount()+" − "+evidence.excludedAmount()+" = "+number(adoptedBase)+" 元；"
          +number(adoptedBase)+" × "+number(rate==null?BigDecimal.ONE:rate)+" = "+number(reference)+" 元";
    } else if (reference!=null) calculation="CMS生效来源合计 = "+number(reference)+" 元";
    else if (base!=null && rate!=null && amount!=null) {
      var displayed=base.multiply(rate).setScale(6,RoundingMode.HALF_UP);
      calculation=number(base)+" × "+number(rate)+" = "+number(displayed)+" 元";
      if(displayed.compareTo(amount)!=0) calculation+="（保存金额 "+number(amount)+" 元；旧版保存参数的精度有限，不能据此覆盖保存金额）";
      if(Set.of("OVERHAUL","TOOLING_REPAIR","WATER_POWER","DEPT_OTHER").contains(code)) {
        var parameters=evidence.rows().stream().filter(row->row.containsKey("laborTotal")).findFirst().orElse(Map.of());
        var labor=parameters.get("laborTotal"); var manhour=parameters.get("manhourRate");
        String inputs=parameters.containsKey("directLabor")&&parameters.containsKey("indirectLabor")
            ?"直接人工 "+parameters.get("directLabor")+" + 辅助人工 "+parameters.get("indirectLabor")+" = 人工合计 "+labor+" 元；":"";
        String hours="SAVED".equals(evidence.origin())&&manhour!=null&&!manhour.isBlank()
            ?labor+" ÷ 工时工资率 "+manhour+" = 工时基数 "+number(base)+"；"
            :"保存的工时基数 "+number(base)+"（当时工时工资率未记录）；";
        calculation=inputs+hours+"工时基数 "+calculation;
      }
    }
    return new View(v.getId(),v.getCostRunNo(),v.getVersionNo(),v.getProductCode(),v.getPricingMonth(),code,name,
        number(base),number(rate),number(amount),number(reference),reference==null||amount==null?"":number(reference.subtract(amount)),calculation,evidence);
  }
  private static String number(BigDecimal value) { return CmsCostCompositions.number(value); }
  private static BigDecimal decimal(String value) { return value==null||value.isBlank()?BigDecimal.ZERO:new BigDecimal(value); }
  private static void check(QuoteCostRunVersion v,Long id,String run,String product,String unit) {
    if(!Objects.equals(v.getId(),id)||!Objects.equals(v.getCostRunNo(),run)||!Objects.equals(v.getProductCode(),product)
        ||!Objects.equals(v.getBusinessUnitType(),unit)) throw new AccessDeniedException("保存明细的版本归属不一致");
  }
}
