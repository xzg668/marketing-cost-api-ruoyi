package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CostInputRevisionServiceImplTest {

  @Test
  void revisionChangesForBusinessInputButIgnoresCalculationOutputState() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString()))
        .thenAnswer(invocation -> List.of(Map.of("Checksum", invocation.getArgument(0).hashCode())));
    CostInputRevisionServiceImpl service = new CostInputRevisionServiceImpl(jdbcTemplate);
    OaForm form = form();
    OaFormItem item = item(11L, "P-1");

    String original = service.currentRevision(form, item);
    item.setCalcStatus("SUCCESS");
    item.setConfirmedCostVersionId(88L);
    assertThat(service.currentRevision(form, item)).isEqualTo(original);

    form.setCopperPrice(new BigDecimal("90001"));
    assertThat(service.currentRevision(form, item)).isNotEqualTo(original);
  }

  @Test
  void batchRevisionReadsSharedSourceTablesOnlyOnce() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString()))
        .thenReturn(List.of(Map.of("Checksum", 12345L)));
    CostInputRevisionServiceImpl service = new CostInputRevisionServiceImpl(jdbcTemplate);

    Map<Long, String> revisions =
        service.currentRevisions(form(), List.of(item(11L, "P-1"), item(12L, "P-2")));

    assertThat(revisions).hasSize(2);
    assertThat(revisions.get(11L)).isNotEqualTo(revisions.get(12L));
    verify(jdbcTemplate, times(20)).queryForList(anyString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingTechnicalDataIsIsolatedPerProductButSystemFailuresStillAbort() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("Checksum", 12345L)));
    var technical = mock(EffectiveTechnicalDataQueryService.class);
    org.springframework.beans.factory.ObjectProvider<EffectiveTechnicalDataQueryService> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(technical);
    var missing = new EffectiveTechnicalDataException("TECH_DATA_EFFECTIVE_VERSION_MISSING",
        11L, "2026-08", List.of("PACKAGE"), "包装未生效");
    when(technical.effectiveFingerprint(11L, "2026-08")).thenThrow(missing);
    when(technical.effectiveFingerprint(12L, "2026-08")).thenReturn("TECH-12");
    var service = new CostInputRevisionServiceImpl(jdbc, provider);
    var items = List.of(item(11L, "P-1"), item(12L, "P-2"));

    var revisions = service.currentRevisions(form(), items, "2026-08");
    assertThat(revisions).containsOnlyKeys(12L);
    verify(jdbc, times(20)).queryForList(anyString());
    assertThat(revisions.get(12L)).isEqualTo(service.currentRevision(form(), items.get(1), "2026-08"));
    assertThatThrownBy(() -> service.currentRevision(form(), items.get(0), "2026-08"))
        .isSameAs(missing);

    org.mockito.Mockito.doReturn("TECH-11").when(technical).effectiveFingerprint(11L, "2026-08");
    assertThat(service.currentRevisions(form(), items, "2026-08")).containsKeys(11L, 12L);
    org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("DB timeout"))
        .when(technical).effectiveFingerprint(11L, "2026-08");
    assertThatThrownBy(() -> service.currentRevisions(form(), items, "2026-08"))
        .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
  }

  @Test
  void changingAnnualPropertyRulesInvalidatesNewCosting() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("Checksum", 100L)));
    var service = new CostInputRevisionServiceImpl(jdbc);
    var original = service.currentRevision(form(), item(11L,"P-1"));
    when(jdbc.queryForList("CHECKSUM TABLE `lp_product_property_rule`"))
        .thenReturn(List.of(Map.of("Checksum", 200L)));
    assertThat(service.currentRevision(form(), item(11L,"P-1"))).isNotEqualTo(original);
  }

  @Test
  void downstreamReturnRequiresNewCostOnlyForTheSameQuotationAndMonth() {
    var jdbc = mock(JdbcTemplate.class);
    var service = new CostInputRevisionServiceImpl(jdbc);
    String query = "SELECT MAX(id) FROM lp_quote_final_submission WHERE oa_form_id=? AND accounting_month=? AND returned_at IS NOT NULL";
    var original = service.currentRevision(form(), item(11L, "P-1"), "2026-09");
    var otherMonth = service.currentRevision(form(), item(11L, "P-1"), "2026-08");
    when(jdbc.queryForObject(query, Long.class, 1L, "2026-09")).thenReturn(91L);
    assertThat(service.currentRevision(form(), item(11L, "P-1"), "2026-09")).isNotEqualTo(original);
    assertThat(service.currentRevision(form(), item(11L, "P-1"), "2026-08")).isEqualTo(otherMonth);
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setCustomer("ACME");
    form.setBusinessUnitType("COMMERCIAL");
    form.setCopperPrice(new BigDecimal("90000"));
    return form;
  }

  private OaFormItem item(Long id, String materialNo) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setMaterialNo(materialNo);
    item.setPackageMethod("BOX");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }
}
