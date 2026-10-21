package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.normalize.*;
import com.sanhua.marketingcost.formula.registry.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实技术计算、公式预览、变量解析和选商链路，仅替换数据访问。 */
class ScrapPriceFormulaReviewTest extends ScrapPriceTestFixture {
  @Test
  void technicalFormulaReportsAndReusesOnlyTheCurrentProductsApproval() throws Exception {
    var publicRow = row("S1", "甲", null, "90");
    load(List.of(publicRow, row("S2", "乙", "95", null)));
    var variables = mock(PriceVariableMapper.class);
    var variable = new PriceVariable();
    variable.setVariableCode("scrap_price");
    variable.setVariableName("废料价格");
    variable.setFactorType("PART_CONTEXT");
    variable.setStatus("active");
    variable.setTaxMode("EXCL");
    variable.setResolverKind("DERIVED");
    variable.setResolverParams("{\"strategy\":\"SCRAP_REF\"}");
    when(variables.selectList(any())).thenReturn(List.of(variable));
    var aliases = new VariableAliasIndex(variables);
    aliases.init();
    var placeholders = RowLocalPlaceholderTestSupport.defaultRegistry();
    var finance = mock(FinanceFactorVariableQuery.class);
    var financial = evidence.publicFactors(row(null, null, "95", null));
    when(finance.context(any()))
        .thenAnswer(
            invocation -> {
              VariableContext context = invocation.getArgument(0);
              context.setPriceFactors(financial);
              return financial;
            });
    var references = mock(MaterialScrapRefMapper.class);
    var reference = new MaterialScrapRef();
    reference.setScrapCode("scrap");
    when(references.selectList(any())).thenReturn(List.of(reference));
    var registry = new FactorVariableRegistryImpl(variables, finance, json, placeholders);
    registry.setDerivedContextResolver(new DerivedResolver(finance, references, service));
    var linked = mock(PriceLinkedItemMapper.class);
    var preview =
        new PriceLinkedFormulaPreviewServiceImpl(
            new FormulaNormalizer(aliases, placeholders),
            registry,
            variables,
            linked,
            new FormulaUnitConsistencyChecker(),
            json,
            null,
            finance);
    var calculations = mock(PriceLinkedCalcItemMapper.class);
    var calculator = new PriceLinkedCalcServiceImpl(calculations, linked, forms, json, preview);
    var technical =
        new TechnicalPriceSourceResolverImpl(
            mock(PriceFixedItemMapper.class),
            linked,
            calculations,
            calculator,
            forms,
            mock(OaFormItemMapper.class),
            mock(MaterialMasterRawMapper.class),
            mock(JdbcTemplate.class),
            mock(TechnicalPriceSourceMapper.class));
    var source = new PriceLinkedItem();
    source.setId(91L);
    source.setTechnicalVersionId(88L);
    source.setSourceKind("TECH_SUPPLEMENTAL");
    source.setBusinessUnitType("COMMERCIAL");
    source.setOrgCode("220");
    source.setMaterialCode("part");
    source.setPricingMonth("2026-09");
    source.setFormulaExpr("100-[scrap_price]*0.1");
    var form = new OaForm();
    form.setOaNo("CURRENT-OA");
    form.setCopperPrice(new BigDecimal("120000"));
    when(forms.selectList(any())).thenReturn(List.of(form));
    var context = new CostRunContext();
    context.setOaNo("CURRENT-OA");
    context.setBusinessUnitType("COMMERCIAL");
    context.setPricingMonth("2026-10");
    context.setPriceAsOfTime(LocalDateTime.of(2026, 10, 19, 12, 0));
    var reviews = new SupplierPriceReviewContext(171L).forOrganization("220");
    context.setSupplierPriceReviewContext(reviews);

    assertThat(technical.calculate(source, context).getCalcStatus()).isEqualTo("FAILED");
    assertThat(reviews.reviews()).hasSize(1);
    var pending = reviews.reviews().getFirst();
    assertThat(pending.scope().oaFormItemId()).isEqualTo(171L);
    assertThat(pending.scope().oaNo()).isEqualTo("CURRENT-OA");
    assertThat(pending.scope().periodMonth()).isEqualTo("2026-10");
    assertThat(pending.scope().pricingDate()).isEqualTo(context.getPriceAsOfTime().toLocalDate());
    var decision = new SupplierPriceDecision();
    decision.setDecision("FALLBACK_HIGH");
    decision.setFingerprint(pending.fingerprint());
    when(decisions.latest(pending.scopeKey())).thenReturn(decision);
    for (String scenario : List.of("FINANCE_QUOTE_BASE", "OA_LOCKED")) {
      context.setPriceScenarioType(scenario);
      var result = technical.calculate(source, context);
      assertThat(result.getCalcStatus()).isEqualTo("OK");
      assertThat(result.getPartUnitPrice()).isEqualByComparingTo("91");
      var snapshot = json.readTree(result.getInputSnapshotJson());
      var scrap = evidence.read(snapshot.path("scrapPriceInputs").path("scrap_price").asText());
      assertThat(scrap.supplier().code()).isEqualTo("S1");
      assertThat(scrap.consumerOrgCode()).isEqualTo("220");
    }
    context.setSupplierPriceReviewContext(
        new SupplierPriceReviewContext(189L).forOrganization("220"));
    var other = technical.calculate(source, context);
    assertThat(other.getCalcStatus()).isEqualTo("FAILED");
    assertThat(other.getCalcMessage()).contains("供货比率不足");
  }
}
