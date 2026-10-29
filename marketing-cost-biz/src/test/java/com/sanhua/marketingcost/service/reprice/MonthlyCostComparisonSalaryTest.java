package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.entity.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyCostComparisonSalaryTest {
  final MonthlyCostComparisonSalary reader=new MonthlyCostComparisonSalary(new ObjectMapper().findAndRegisterModules());
  static EffectiveTechnicalDataInput technical(){return new EffectiveTechnicalDataInput(1L,2L,1,"2026-03","QUOTE_TECH_EFFECTIVE_VERSION","hash",LocalDateTime.now(),false,false,true,null,null,BigDecimal.TEN,List.of(),List.of(),List.of(new EffectiveTechnicalDataInput.SalaryLine(3L,1,"DIRECT",BigDecimal.TEN)),null,null);}
  static QuoteTechSalaryItem row(String source){var r=new QuoteTechSalaryItem();r.setId(3L);r.setVersionId(2L);r.setSourceSnapshotJson(source);return r;}
  static String cms(String code){return "{\"laborType\":\"DIRECT\",\"reference\":{\"materialNo\":\""+code+"\",\"costYear\":2026,\"direct\":{\"sourceId\":15,\"laborType\":\"DIRECT\",\"amountYuan\":\"10\"},\"issues\":[]}}";}
  @Test void directSavedCmsDoesNotRequireAnOaApprovalState(){
    var trace=new CostRunTraceSnapshot();trace.setCostCode("DIRECT_LABOR");trace.setSourceType("CMS");
    assertThat(reader.read("000A",null,List.of(trace),Map.of())).isEqualTo(new MonthlyCostComparisonSalary.Evidence("CMS",null));
  }
  @Test void referenceToAnotherCmsMaterialPreservesLeadingZerosAndMarksOnlyDirectWage(){
    var result=reader.read("000A",technical(),List.of(),Map.of(3L,row(cms("000B"))));
    assertThat(result.source()).isEqualTo("CMS参考料号 000B");assertThat(result.unapproved()).isEqualTo("直接人工工资");
  }
  @Test void referencingOwnSavedCmsMaterialIsNotAnUnapprovedOtherMaterial(){
    assertThat(reader.read("000A",technical(),List.of(),Map.of(3L,row(cms("000A"))))).isEqualTo(new MonthlyCostComparisonSalary.Evidence("CMS",null));
  }
  @Test void uploadedWageDoesNotAutomaticallyBecomeAnUnapprovedItem(){
    var evidence="{\"laborType\":\"DIRECT\",\"upload\":{\"items\":[],\"issues\":[],\"amountYuan\":\"10\"}}";
    assertThat(reader.read("000A",technical(),List.of(),Map.of(3L,row(evidence))))
        .isEqualTo(new MonthlyCostComparisonSalary.Evidence("工资稿件",null));
  }
  @Test void unknownTechnicalSourceDoesNotBecomeAnUnapprovedItem(){
    var result=reader.read("000A",technical(),List.of(),Map.of(3L,row(null)));
    assertThat(result.source()).contains("未保存来源");assertThat(result.unapproved()).isNull();
  }
  @Test void unrelatedOrMissingSalaryVersionCannotSupplyAReferenceLabel(){
    assertThatThrownBy(()->reader.read("000A",technical(),List.of(),Map.of())).hasMessageContaining("有效版本依据");
    var bad=row(cms("000B"));bad.setVersionId(999L);
    assertThatThrownBy(()->reader.read("000A",technical(),List.of(),Map.of(3L,bad))).hasMessageContaining("有效版本依据");
  }
  @Test void absentHistoricalSourceRemainsBlank(){assertThat(reader.read("A",null,List.of(),Map.of())).isEqualTo(new MonthlyCostComparisonSalary.Evidence(null,null));}
}
