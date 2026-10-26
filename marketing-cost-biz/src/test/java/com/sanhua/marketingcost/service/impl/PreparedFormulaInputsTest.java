package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.normalize.*;
import com.sanhua.marketingcost.formula.registry.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A captured-input seam must use the real normalizer/registry/preview without source I/O. */
class PreparedFormulaInputsTest {
  final PriceVariableMapper variables=mock(PriceVariableMapper.class);
  final PriceLinkedItemMapper rules=mock(PriceLinkedItemMapper.class);
  final PriceVariableBindingMapper bindings=mock(PriceVariableBindingMapper.class);
  final RowLocalPlaceholderMapper placeholders=mock(RowLocalPlaceholderMapper.class);
  final FactorIdentityMapper identities=mock(FactorIdentityMapper.class);
  final FactorMonthlyPriceMapper prices=mock(FactorMonthlyPriceMapper.class);
  final FinanceFactorPriceReader finance=mock(FinanceFactorPriceReader.class);
  final MaterialScrapRefMapper scrapReferences=mock(MaterialScrapRefMapper.class);
  final com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService scrapPrices=mock(com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService.class);
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();

  PriceLinkedFormulaPreviewServiceImpl service(){
    var rowLocal=new RowLocalPlaceholderRegistry(placeholders,json);
    var query=new FinanceFactorVariableQuery(finance,identities,prices,new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var registry=new FactorVariableRegistryImpl(variables,query,json,rowLocal);
    registry.setPriceVariableBindingMapper(bindings);registry.setFactorMonthlyPriceMapper(prices);
    registry.setDerivedContextResolver(new DerivedResolver(query,scrapReferences,scrapPrices));
    return new PriceLinkedFormulaPreviewServiceImpl(new FormulaNormalizer(new VariableAliasIndex(variables),rowLocal),registry,variables,rules,new FormulaUnitConsistencyChecker(),json,null,query);
  }
  static PriceVariable variable(String code,String alias,String kind,String params){
    var row=new PriceVariable();row.setId((long)code.hashCode());row.setVariableCode(code);row.setVariableName(code);row.setAliasesJson("[\""+alias+"\"]");row.setResolverKind(kind);row.setResolverParams(params);row.setStatus("active");row.setTaxMode("INCL");return row;
  }
  static PriceLinkedItem rule(){
    var rule=new PriceLinkedItem();rule.setId(11L);rule.setBusinessUnitType("COMMERCIAL");rule.setOrgCode("210");rule.setMaterialCode("RAW-001");rule.setUnit("件");rule.setPricingMonth("2026-10");rule.setSourceKind("PUBLIC");rule.setExpressionVersion("LEGACY");rule.setTaxIncluded(0);rule.setBlankWeight(new BigDecimal("20"));rule.setFormulaExpr("电解铜 * 下料重量");return rule;
  }
  static PriceFactorContext financial(){
    var factor=new FactorPriceSnapshot.FactorValue(1L,101L,"铜","1#Cu",new BigDecimal("100"),"CNY/KG",true,List.of(new FactorPriceSnapshot.Source(1L,"财务基价","D3","100","CNY/KG",null,"0.000000","IDENTITY")));
    var snapshot=new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL","210","2026-10","DEFAULT",FactorPriceScope.Condition.GENERAL),1L,1,"finance-base.xlsx","a".repeat(64),"b".repeat(64),Map.of(PriceFactorContext.COPPER,factor),List.of("DEFAULT"),"finance",LocalDateTime.of(2026,10,1,0,0),null);
    return new PriceFactorContext("RAW-001",new PriceMonthSelection("2026-10","2026-10"),snapshot,null);
  }
  static List<PriceVariable> definitions(){return List.of(variable("Cu","电解铜","FINANCE","{\"factorCode\":\"Cu\",\"priceSource\":\"平均价\"}"),variable("blank_weight","下料重量","ENTITY","{\"entity\":\"linkedItem\",\"field\":\"blankWeight\",\"unitScale\":0.001}"),variable("vat_rate","税率","CONST","{\"value\":\"0.13\"}"));}
  static PreparedFormulaInputs.Financial capturedFinancial(){return new PreparedFormulaInputs.Financial(financial(),Map.of(),Set.of(PriceFactorContext.COPPER));}
  @Test void capturedPublicFormulaKeepsItsFinancialP0EntityScaleAndTaxArithmeticWithoutQueries(){
    var definitions=List.of(variable("Cu","电解铜","FINANCE","{\"factorCode\":\"Cu\",\"priceSource\":\"平均价\"}"),variable("blank_weight","下料重量","ENTITY","{\"entity\":\"linkedItem\",\"field\":\"blankWeight\",\"unitScale\":0.001}"),variable("vat_rate","税率","CONST","{\"value\":\"0.13\"}"));
    var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(definitions,List.of(),List.of()),new PreparedFormulaInputs.Financial(financial(),Map.of(),Set.of(PriceFactorContext.COPPER)));
    var result=service().previewPrepared(rule(),"2026-10",LocalDate.of(2026,10,23),prepared);
    assertThat(result.getError()).isNull();assertThat(result.getNormalizedExpr()).isEqualTo("[Cu] * [blank_weight]");assertThat(result.getResult()).isEqualByComparingTo("1.7699115044247787611");
    assertThat(result.getCalculatorVersion()).isEqualTo("MONTHLY_LINKED_FORMULA_V1");assertThat(result.getInputSnapshotJson()).contains("finance-base.xlsx","AVG|1#CU","blankWeight","0.001","vat_rate");assertThat(result.getCalcFingerprint()).hasSize(64);
    verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void capturedRowLocalIdentityUsesItsCapturedBindingAndNormalFormulaEngine(){
    var placeholder=new RowLocalPlaceholder();placeholder.setId(1L);placeholder.setCode("__material");placeholder.setDisplayName("材料含税价格");placeholder.setTokenNamesJson("[\"材料含税价格\"]");placeholder.setStatus("active");placeholder.setDeleted(0);placeholder.setSortOrder(1);
    var binding=new PriceVariableBinding();binding.setId(12L);binding.setLinkedItemId(11L);binding.setTokenName("材料含税价格");binding.setFactorIdentityId(1L);binding.setDeleted(0);
    var identity=new FactorIdentity();identity.setId(1L);identity.setBusinessUnitType("COMMERCIAL");identity.setStatus("ACTIVE");identity.setCanonicalFactorKey("AVG|1#CU");
    var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(definitions(),List.of(placeholder),List.of(binding)),new PreparedFormulaInputs.Financial(financial(),Map.of(1L,identity),Set.of(PriceFactorContext.COPPER)));
    var rule=rule();rule.setFormulaExpr("材料含税价格 * 下料重量");var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);
    assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("1.7699115044247787611");assertThat(result.getInputSnapshotJson()).contains("capturedBindings","factorIdentityId","材料含税价格","AVG|1#CU");verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void callerBusinessBoundaryStillAppliesToCapturedFinancialInputs(){
    var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(definitions(),List.of(),List.of()),capturedFinancial());
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("fixture","test",List.of());auth.setDetails(Map.of("businessUnitType","HOUSEHOLD"));org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try{for(String expression:List.of("电解铜 * 下料重量","0")){var rule=rule();rule.setFormulaExpr(expression);var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);assertThat(result.getError()).contains("业务单元");assertThat(result.getResult()).isNull();}verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
  @Test void missingTaxInputCannotReturnThePartiallyComputedPrice(){
    var noVat=definitions().stream().filter(row->!"vat_rate".equals(row.getVariableCode())).toList();var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(noVat,List.of(),List.of()),capturedFinancial());var result=service().previewPrepared(rule(),"2026-10",LocalDate.of(2026,10,23),prepared);assertThat(result.getError()).contains("税率");assertThat(result.getResult()).isNull();assertThat(result.getInputSnapshotJson()).isNull();verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void inactivePlaceholderCannotBeSmuggledIntoACapturedCalculation(){
    var placeholder=new RowLocalPlaceholder();placeholder.setId(1L);placeholder.setCode("__material");placeholder.setTokenNamesJson("[\"材料含税价格\"]");placeholder.setStatus("inactive");placeholder.setDeleted(0);
    assertThatThrownBy(()->new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(definitions(),List.of(placeholder),List.of()),capturedFinancial())).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void explicitZeroConstantNeedsNoFinancialPublicationAndNeverQueriesOne(){
    var rule=rule();rule.setFormulaExpr("0");rule.setTaxIncluded(1);var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(),List.of(),List.of()),new PreparedFormulaInputs.Financial(null,Map.of(),Set.of()));var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);assertThat(result.getError()).isNull();assertThat(result.getResult()).isZero();assertThat(result.getInputSnapshotJson()).isNotBlank();verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void customerCopperCannotEnterTheCapturedPublicP0(){
    assertThatThrownBy(()->new PreparedFormulaInputs.Financial(financial().withCustomerCopperPerTon(new BigDecimal("200000")),Map.of(),Set.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cu");
  }
  @Test void uncapturedScrapDependencyMustStopBeforeReadingItsCurrentMappingOrPrice(){
    var definition=variable("scrap_price","废料价","DERIVED","{\"strategy\":\"SCRAP_REF\"}");var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(definition),List.of(),List.of()),capturedFinancial());var rule=rule();rule.setFormulaExpr("废料价");rule.setTaxIncluded(1);var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);assertThat(result.getError()).isNotBlank();assertThat(result.getResult()).isNull();verifyNoInteractions(scrapReferences,scrapPrices,variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void rowLocalCaptureDoesNotDependOnContextBuilderCallOrder(){
    var placeholder=new RowLocalPlaceholder();placeholder.setId(1L);placeholder.setCode("__material");placeholder.setTokenNamesJson("[\"材料含税价格\"]");placeholder.setStatus("active");placeholder.setDeleted(0);placeholder.setSortOrder(1);
    var binding=new PriceVariableBinding();binding.setId(12L);binding.setLinkedItemId(11L);binding.setTokenName("材料含税价格");binding.setFactorIdentityId(1L);binding.setDeleted(0);
    var identity=new FactorIdentity();identity.setId(1L);identity.setBusinessUnitType("COMMERCIAL");identity.setStatus("ACTIVE");identity.setCanonicalFactorKey("AVG|1#CU");
    var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(definitions(),List.of(placeholder),List.of(binding)),new PreparedFormulaInputs.Financial(financial(),Map.of(1L,identity),Set.of(PriceFactorContext.COPPER)));
    var query=new FinanceFactorVariableQuery(finance,identities,prices,new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));var registry=new FactorVariableRegistryImpl(variables,query,json,new RowLocalPlaceholderRegistry(placeholders,json));registry.setPriceVariableBindingMapper(bindings);
    var context=new VariableContext().preparedInputs(prepared).linkedItem(rule()).materialCode("RAW-001").pricingMonth("2026-10").financeQuoteBase(true);assertThat(registry.resolve("__material",context)).contains(new BigDecimal("100"));verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  PreparedFormulaInputs capturedConstant(PriceVariable definition){return new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(definition),List.of(),List.of()),new PreparedFormulaInputs.Financial(null,Map.of(),Set.of()));}
  void assertCapturedConstant(PreparedFormulaInputs inputs){var rule=rule();rule.setFormulaExpr("费用");rule.setTaxIncluded(1);var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),inputs);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("2");assertThat(result.getInputSnapshotJson()).contains("fee","费用","capturedDefinitions");verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance,scrapReferences,scrapPrices);}
  @Test void changingTheOriginalVariableCannotChangeCapturedPriceOrDefinition(){
    var definition=variable("fee","费用","CONST","{\"value\":\"2\"}");var prepared=capturedConstant(definition);definition.setResolverParams("{\"value\":\"99\"}");definition.setVariableCode("changed");definition.setAliasesJson("[\"另一个费用\"]");assertCapturedConstant(prepared);
  }
  @Test void changingReturnedVariableObjectsCannotChangeTheCapturedCalculation(){
    var prepared=capturedConstant(variable("fee","费用","CONST","{\"value\":\"2\"}"));prepared.catalog().variables().getFirst().setResolverParams("{\"value\":\"88\"}");prepared.variables().get("fee").setResolverParams("{\"value\":\"77\"}");assertCapturedConstant(prepared);
  }
  @Test void changingOriginalAndReturnedIdentityMetadataCannotRetargetCapturedFinance(){
    var identity=new FactorIdentity();identity.setId(1L);identity.setBusinessUnitType("COMMERCIAL");identity.setStatus("ACTIVE");identity.setCanonicalFactorKey("AVG|1#CU");var financial=new PreparedFormulaInputs.Financial(financial(),Map.of(1L,identity),Set.of(PriceFactorContext.COPPER));identity.setCanonicalFactorKey("AVG|CHANGED");financial.identities().get(1L).setStatus("INACTIVE");var definition=variable("bound","绑定因素","FINANCE","{\"factorIdentityId\":1}");var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(definition),List.of(),List.of()),financial);prepared.financial().identities().get(1L).setBusinessUnitType("HOUSEHOLD");var rule=rule();rule.setFormulaExpr("绑定因素");rule.setTaxIncluded(1);var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("100");verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void capturedNumericParametersRetainTheExactStoredDecimalLiteral(){
    var prepared=capturedConstant(variable("fee","费用","CONST","{\"value\":0.1000000000000000001}"));var rule=rule();rule.setFormulaExpr("费用");rule.setTaxIncluded(1);var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("0.1000000000000000001");verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
  @Test void capturedDefinitionKeepsTheSameTrimmedLookupCodeDuringPlanningAndEvaluation(){
    var prepared=capturedConstant(variable(" fee ","费用","CONST","{\"value\":\"2.5\"}"));var rule=rule();rule.setFormulaExpr("费用");rule.setTaxIncluded(1);
    assertThat(prepared.catalog().requirements(rule).variables()).containsExactly("fee");
    var result=service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),prepared);
    assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("2.5");verifyNoInteractions(variables,rules,bindings,placeholders,identities,prices,finance);
  }
}
