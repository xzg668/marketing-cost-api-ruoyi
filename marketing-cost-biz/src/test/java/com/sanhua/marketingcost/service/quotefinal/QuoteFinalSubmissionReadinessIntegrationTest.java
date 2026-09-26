package com.sanhua.marketingcost.service.quotefinal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.costing.ProductCostingContextResolver;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class QuoteFinalSubmissionReadinessIntegrationTest extends BomMapperTestBase {
  @Autowired QuoteFinalSubmissionService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @SpyBean ProductCostingContextResolver contexts;

  @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

  @Test void missingTechnicalInputReturnsNotReadyAfterItsTransactionRollsBack() {
    String oaNo = "TW18-READINESS-" + UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", oaNo);
    long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oaNo);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,'TW18-MISSING','COMMERCIAL')", formId);
    long itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    String month = CostPricingPeriodUtils.currentPricingMonth();
    var auth = new UsernamePasswordAuthenticationToken("finance", "unused", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    doAnswer(call -> new TransactionTemplate(transactions).execute(tx -> {
      throw new EffectiveTechnicalDataException("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED", itemId,
          month, List.of("PACKAGE"), "原包装资料尚未完成财务确认");
    })).when(contexts).resolveRevision(any());

    var result = service.status(oaNo, month, new TechnicalDataActor(1L, "报价员", Set.of("*:*:*")));

    assertThat(result.status()).isEqualTo("NOT_READY");
    assertThat(result.error()).isEqualTo("原包装资料尚未完成财务确认");
    assertThat(result.canConfirm()).isFalse();
    assertThat(result.costs()).hasSize(1);
    assertThat(result.costs().getFirst().totalCost()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_final_submission WHERE oa_form_id=?", Integer.class, formId)).isZero();
  }
}
