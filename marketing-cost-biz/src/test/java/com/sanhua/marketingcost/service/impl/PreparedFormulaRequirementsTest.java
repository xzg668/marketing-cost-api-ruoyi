package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import java.util.*;
import org.junit.jupiter.api.Test;

class PreparedFormulaRequirementsTest {
  static PreparedFormulaInputs.Catalog catalog(PriceVariable... variables){return new PreparedFormulaInputs.Catalog(List.of(variables),List.of(),List.of());}
  @Test void constantFormulaDoesNotRequireUnrelatedBadDefinitionsOrFinancialPublication(){
    var bad=PreparedFormulaInputsTest.variable("unused","未使用","FINANCE","{bad json");var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("0");rule.setTaxIncluded(1);var needs=catalog(bad).requirements(rule);assertThat(needs.financialRequired()).isFalse();assertThat(needs.scrapRequired()).isFalse();assertThat(needs.variables()).isEmpty();assertThat(needs.factorIdentityIds()).isEmpty();
  }
  @Test void nestedFormulaCollectsExactFinancialIdentityAndImplicitTaxInputsWithoutEvaluatingIt(){
    var cost=PreparedFormulaInputsTest.variable("cost","成本","FORMULA","{\"expr\":\"[metal]*[weight]\"}");var metal=PreparedFormulaInputsTest.variable("metal","金属","FINANCE","{\"factorIdentityId\":44}");var weight=PreparedFormulaInputsTest.variable("weight","重量","ENTITY","{\"entity\":\"linkedItem\",\"field\":\"blankWeight\",\"unitScale\":0.001}");var vat=PreparedFormulaInputsTest.variable("vat_rate","税率","CONST","{\"value\":\"0.13\"}");var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("成本");var needs=catalog(cost,metal,weight,vat).requirements(rule);assertThat(needs.normalizedExpression()).isEqualTo("[cost]");assertThat(needs.financialRequired()).isTrue();assertThat(needs.scrapRequired()).isFalse();assertThat(needs.factorIdentityIds()).containsExactly(44L);assertThat(needs.variables()).containsExactlyInAnyOrder("cost","metal","weight","vat_rate");
  }
  @Test void derivedFormulaFindsItsScrapAndVatDependencies(){
    var wrapper=PreparedFormulaInputsTest.variable("cost","成本","DERIVED","{\"strategy\":\"FORMULA_REF\",\"formulaRef\":\"[scrap]*2\"}");var scrap=PreparedFormulaInputsTest.variable("scrap","废料","DERIVED","{\"strategy\":\"SCRAP_REF\"}");var vat=PreparedFormulaInputsTest.variable("vat_rate","税率","CONST","{\"value\":\"0.13\"}");var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("成本");rule.setTaxIncluded(1);var needs=catalog(wrapper,scrap,vat).requirements(rule);assertThat(needs.scrapRequired()).isTrue();assertThat(needs.financialRequired()).isFalse();assertThat(needs.variables()).containsExactlyInAnyOrder("cost","scrap","vat_rate");
  }
  @Test void rowLocalIdentityBindingIsResolvedFromTheSameCapturedCatalog(){
    var placeholder=new RowLocalPlaceholder();placeholder.setId(1L);placeholder.setCode("__material");placeholder.setTokenNamesJson("[\"材料含税价格\"]");placeholder.setStatus("active");placeholder.setDeleted(0);placeholder.setSortOrder(1);var binding=new PriceVariableBinding();binding.setId(12L);binding.setLinkedItemId(11L);binding.setTokenName("材料含税价格");binding.setFactorIdentityId(77L);binding.setDeleted(0);var catalog=new PreparedFormulaInputs.Catalog(List.of(),List.of(placeholder),List.of(binding));var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("材料含税价格");rule.setTaxIncluded(1);var needs=catalog.requirements(rule);assertThat(needs.normalizedExpression()).isEqualTo("[__material]");assertThat(needs.factorIdentityIds()).containsExactly(77L);assertThat(needs.financialRequired()).isTrue();
  }
  @Test void cyclesAndUnknownReferencedVariablesStopBeforeAnySourceReads(){
    var a=PreparedFormulaInputsTest.variable("a","甲","FORMULA","{\"expr\":\"[b]\"}");var b=PreparedFormulaInputsTest.variable("b","乙","FORMULA","{\"expr\":\"[a]\"}");var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("甲");rule.setTaxIncluded(1);assertThatThrownBy(()->catalog(a,b).requirements(rule)).isInstanceOf(RuntimeException.class).hasMessageContaining("循环");rule.setFormulaExpr("[missing]");assertThatThrownBy(()->catalog(a,b).requirements(rule)).isInstanceOf(RuntimeException.class).hasMessageContaining("missing");
  }
  @Test void excludedTaxScrapDoesNotInventAVatDependency(){
    var scrap=PreparedFormulaInputsTest.variable("scrap","废料","DERIVED","{\"strategy\":\"SCRAP_REF\"}");scrap.setTaxMode("EXCL");var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("废料");rule.setTaxIncluded(1);var needs=catalog(scrap).requirements(rule);assertThat(needs.variables()).containsExactly("scrap");assertThat(needs.scrapRequired()).isTrue();
  }
  @Test void fractionalFinanceIdentityIsNeverTruncatedDuringPlanning(){
    var value=PreparedFormulaInputsTest.variable("metal","金属","FINANCE","{\"factorIdentityId\":1.9}");var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("金属");rule.setTaxIncluded(1);assertThatThrownBy(()->catalog(value).requirements(rule)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void technicalFormulaKeepsTheExistingImplicitFinancialContextRequirement(){
    var rule=PreparedFormulaInputsTest.rule();rule.setSourceKind("TECH_SUPPLEMENTAL");rule.setFormulaExpr("0");rule.setTaxIncluded(1);
    assertThat(catalog().requirements(rule).financialRequired()).isTrue();
  }
}
