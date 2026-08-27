package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
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
    verify(jdbcTemplate, times(19)).queryForList(anyString());
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
