package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScrapPriceTrialServiceTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final ScrapPriceRuleCodec rules = new ScrapPriceRuleCodec(json);
  final ScrapPriceCalculator calculator = new ScrapPriceCalculator(rules);
  final ScrapPriceQueryService query = mock(ScrapPriceQueryService.class);
  final ScrapPriceTrialService service = new ScrapPriceTrialService(query, calculator, new ScrapPriceCalculationEvidence(json));

  @Test void changesTheFormulaFactorValueNotTheCuBandAndLeavesPublishedPriceUntouched() throws Exception {
    var row = row("Brass", "AVG|美国柜装黄铜", "52.6232791440377", "[Brass]*1.039/1.13", "CNY/KG");
    String before = json.writeValueAsString(row);
    var initial = service.trial("COMMERCIAL", 1, request(Map.of()));
    assertThat(initial.schemeCode()).isEqualTo("CU_90000");
    assertThat(initial.factors().getFirst().financeInput()).isEqualByComparingTo("52.6232791440377");
    var result = service.trial("COMMERCIAL", 1, request(Map.of("Brass", d("55"))));
    assertThat(result.financePrice()).isEqualByComparingTo(initial.financePrice());
    assertThat(result.trialPrice()).isEqualByComparingTo(d("55").multiply(d("1.039")).divide(d("1.13"),18,RoundingMode.HALF_UP));
    assertThat(result.factors().getFirst().trialInput()).isEqualByComparingTo("55");
    assertThat(result.difference()).isEqualByComparingTo(result.trialPrice().subtract(result.financePrice()));
    assertThat(json.writeValueAsString(row)).isEqualTo(before);
  }

  @Test void normalizedPerKgInputsAreExplicitAndDoNotConvertTheUserValueTwice() {
    row("Cu", "AVG|1#CU", "90000", "[Cu]*0.982/1.13", "CNY/TON");
    var result = service.trial("COMMERCIAL",1,request(Map.of("Cu",d("100"))));
    assertThat(result.factors().getFirst().financeInput()).isEqualByComparingTo("90");
    assertThat(result.factors().getFirst().trialInput()).isEqualByComparingTo("100");
    assertThat(result.trialPrice()).isEqualByComparingTo(d("100").multiply(d("0.982")).divide(d("1.13"),18,RoundingMode.HALF_UP));
  }

  @Test void aluminumAndMultipleFactorsCanBeChangedIndependentlyAndZeroIsValid() {
    row("Al", "AVG|A00", "23.691", "([Cu]*0.2+[Al]*0.8)/1.13", "CNY/KG");
    var result = service.trial("COMMERCIAL",1,request(Map.of("Al",d("24"))));
    assertThat(result.trialPrice()).isEqualByComparingTo(d("37.2").divide(d("1.13"),18,RoundingMode.HALF_UP));
    assertThat(result.factors()).anySatisfy(f -> {
      assertThat(f.variable()).isEqualTo("Cu"); assertThat(f.trialInput()).isEqualByComparingTo("90");
    });
    row("Al", "AVG|A00", "23.691", "[Al]*0.76/1.13", "CNY/KG");
    var zero = service.trial("COMMERCIAL",1,request(Map.of("Al",BigDecimal.ZERO)));
    assertThat(zero.trialPrice()).isZero();
    assertThat(zero.difference()).isEqualByComparingTo(zero.financePrice().negate());
  }

  @Test void invalidUnknownMissingAndStaleInputsCannotProduceAMisleadingTrial() {
    var row = row("Cu", "AVG|1#CU", "90", "[Cu]*0.982/1.13", "CNY/KG");
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,request(Map.of("Brass",d("55")))))
        .hasMessageContaining("公式未使用");
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,request(Map.of("Cu",d("-1")))))
        .hasMessageContaining("不小于0");
    var inputs=new HashMap<String,BigDecimal>();inputs.put("Cu",null);
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,request(inputs))).hasMessageContaining("不小于0");
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,new ScrapPriceTrialService.Request(2L,Map.of())))
        .hasMessageContaining("已更新");
    row.setCalcStatus("WAITING_BASE");
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,request(Map.of()))).hasMessageContaining("尚不可试算");
    row.setCalcStatus("READY");row.setCalculationJson(null);
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,request(Map.of()))).hasMessageContaining("缺少");
  }

  @Test void corruptedBaselineIsRejectedAndDecimalsAreReturnedAsText() throws Exception {
    var row=row("Cu", "AVG|1#CU", "90.123456789123456789", "[Cu]*0.982/1.13", "CNY/KG");
    var result=service.trial("COMMERCIAL",1,request(Map.of("Cu",d("99.123456789123456789"))));
    var tree=json.valueToTree(result);
    assertThat(tree.path("financePrice").isTextual()).isTrue();
    assertThat(tree.path("factors").get(0).path("trialInput").asText()).isEqualTo("99.123456789123456789");
    row.setUnitPrice(row.getUnitPrice().add(BigDecimal.ONE));
    assertThatThrownBy(() -> service.trial("COMMERCIAL",1,request(Map.of()))).hasMessageContaining("不一致");
  }

  @Test void businessUnitScopeAndReadOnlyTransactionArePreserved() {
    when(query.detail("HOUSEHOLD",1)).thenThrow(new IllegalArgumentException("废料价格不存在或无权访问"));
    assertThatThrownBy(() -> service.trial("HOUSEHOLD",1,request(Map.of()))).hasMessageContaining("无权");
    assertThat(ScrapPriceTrialService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
  }

  ScrapPriceTrialService.Request request(Map<String,BigDecimal> values) { return new ScrapPriceTrialService.Request(1L,values); }
  ScrapPrice row(String variable,String key,String value,String expression,String unit) {
    var row=new ScrapPrice();row.setId(1L);row.setBusinessUnitType("COMMERCIAL");row.setPriceMonth("2026-11");
    row.setRuleSourceMonth("2026-11");row.setRuleRevision(1);row.setRowVersion(1L);
    row.setFinanceMonth("2026-11");row.setSchemeCode("CU_90000");
    var bindings=new TreeMap<String,String>();bindings.put(variable,key);
    if(expression.contains("[Cu]"))bindings.put("Cu","AVG|1#CU");
    rules.write(new ScrapPriceRule("SCRAP","废料","","KG",null,null,"LINKED",null,expression,expression,bindings),row);
    var factors=new TreeMap<String,FactorPriceSnapshot.FactorValue>();
    factors.put("AVG|1#CU",new FactorPriceSnapshot.FactorValue(1L,1L,"铜","Cu",d("90"),"CNY/KG",true,List.of()));
    factors.put(key,new FactorPriceSnapshot.FactorValue(2L,2L,key,variable,d(value),unit,true,List.of(
        new FactorPriceSnapshot.Source(1L,"财务基价","E63",value,unit,null,null,"IDENTITY"))));
    var snapshot=new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL","210","2026-11","CU_90000",FactorPriceScope.Condition.GENERAL),
        58L,2,"finance.xlsx","hash","content",factors,List.of("CU_90000","CU_100000"),"finance",null,null);
    var input=calculator.prepare(row,snapshot);row.setCalculationJson(input.evidenceJson());row.setInputFingerprint(input.fingerprint());
    row.setUnitPrice(calculator.calculate(input));row.setCalcStatus("READY");
    when(query.detail("COMMERCIAL",1)).thenReturn(row);return row;
  }
  static BigDecimal d(String value) { return new BigDecimal(value); }
}
