package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryContent;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import java.util.*;
import org.springframework.stereotype.Component;

/** Payroll labels use saved CMS/technical evidence, never OA approval or current CMS mappings. */
@Component
public class MonthlyCostComparisonSalary {
  public record Evidence(String source,String unapproved) {}
  private final ObjectMapper json;
  public MonthlyCostComparisonSalary(ObjectMapper json){this.json=json;}
  public Evidence read(String product,EffectiveTechnicalDataInput technical,List<CostRunTraceSnapshot> traces,
      Map<Long,QuoteTechSalaryItem> salaryRows) {
    if(technical!=null && technical.salaryRequired()) {
      var sources=new TreeSet<String>();boolean reference=false;
      for(var line:technical.salaryItems()) if("DIRECT".equalsIgnoreCase(line.laborType()) || Objects.toString(line.laborType(),"").contains("直接")) {
        var row=salaryRows.get(line.id());
        if(row==null || !Objects.equals(row.getVersionId(),technical.sourceVersionId("SALARY")))
          throw new IllegalStateException("保存的人工明细缺少对应有效版本依据");
        if(row.getSourceSnapshotJson()==null || row.getSourceSnapshotJson().isBlank()) {
          sources.add("技术工资（未保存来源明细）");continue;
        }
        try {
          var evidence=json.readValue(row.getSourceSnapshotJson(),TechnicalDataSalaryContent.ItemEvidence.class);
          if(evidence.upload()!=null) sources.add("工资稿件");
          else if(evidence.reference()!=null) {
            String code=evidence.reference().materialNo();
            if(code==null || code.isBlank() || evidence.reference().direct()==null)
              throw new IllegalStateException("保存的CMS工资参照依据不完整");
            if(product.equals(code)) sources.add("CMS");
            else {sources.add("CMS参考料号 "+code);reference=true;}
          } else sources.add("技术工资（未保存来源明细）");
        } catch(java.io.IOException failure){throw new IllegalStateException("保存的人工来源依据不可读取",failure);}
      }
      return new Evidence(sources.isEmpty()?null:String.join("；",sources),reference?"直接人工工资":null);
    }
    boolean cms=traces.stream().anyMatch(t->"DIRECT_LABOR".equals(t.getCostCode()) && "CMS".equals(t.getSourceType()));
    return new Evidence(cms?"CMS":null,null);
  }
}
