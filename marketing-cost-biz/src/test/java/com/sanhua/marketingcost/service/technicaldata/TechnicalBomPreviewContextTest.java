package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TechnicalBomPreviewContextTest {
  final OaFormItemMapper items = mock(OaFormItemMapper.class);
  final OaFormMapper forms = mock(OaFormMapper.class);
  final QuoteBomContextResolver organizations = mock(QuoteBomContextResolver.class);
  final TechnicalBomContextQuery query = new TechnicalBomContextQuery(items, forms, organizations);
  final TechnicalDataActor admin = new TechnicalDataActor(1L, "admin", Set.of("ROLE_admin"));

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  private void fixture() {
    var form = new OaForm(); form.setId(10L); form.setOaNo("OA-1"); form.setBusinessUnitType("COMMERCIAL"); form.setDeleted(0);
    var item = new OaFormItem(); item.setId(20L); item.setOaFormId(10L); item.setMaterialNo("TARGET");
    item.setProductName("当前产品"); item.setSunlModel("MODEL"); item.setSpec("SPEC"); item.setDeleted(0);
    when(items.selectById(20L)).thenReturn(item); when(forms.selectById(10L)).thenReturn(form);
    when(organizations.resolveOrganization(form, item)).thenReturn(new QuoteDataOrganization("210", "COMMERCIAL"));
  }

  @Test void previewKeepsTheExactTargetMonthAndOrganizationWithoutTaskLookup() {
    fixture(); var result = query.readQuote(20L, "2026-10", admin);
    assertThat(result.context().accountingMonth()).isEqualTo("2026-10");
    assertThat(result.context().oaFormItemId()).isEqualTo(20L);
    assertThat(result.context().materialOrganizationCode()).isEqualTo("COMMERCIAL");
    assertThat(result.product()).isEqualTo(new TechnicalBomRows.Product("TARGET", "当前产品", "MODEL", "SPEC"));
    verify(items).selectById(20L); verify(forms).selectById(10L);
    verifyNoMoreInteractions(items, forms);
  }

  @Test void ordinaryTechniciansQuoteStaffAndOaSessionsCannotUseAdminPreview() {
    for (var actor : List.of(new TechnicalDataActor(2L,"tech",Set.of("technical:data:task:edit")),
        new TechnicalDataActor(3L,"quote",Set.of("ingest:quote:cost-run:execute")),
        new TechnicalDataActor(1L,"oa",Set.of("*:*:*"),10L))) {
      assertThatThrownBy(() -> query.readQuote(20L,"2026-10",actor)).isInstanceOf(TechnicalDataTaskException.class);
    }
    verifyNoInteractions(items, forms, organizations);
  }

  @Test void invalidMonthAndMissingProductAreExplicitErrors() {
    assertThatThrownBy(() -> query.readQuote(20L,"2026-13",admin)).hasMessageContaining("月份");
    verifyNoInteractions(items);
    assertThatThrownBy(() -> query.readQuote(20L,"2026-10",admin)).hasMessageContaining("不存在");
  }

  @Test void deletedProductOrFormCannotBePreviewed() {
    fixture(); items.selectById(20L).setDeleted(1);
    assertThatThrownBy(() -> query.readQuote(20L,"2026-10",admin)).hasMessageContaining("不存在");
    items.selectById(20L).setDeleted(0); forms.selectById(10L).setDeleted(1);
    assertThatThrownBy(() -> query.readQuote(20L,"2026-10",admin)).hasMessageContaining("不存在");
    verifyNoInteractions(organizations);
  }

  @Test void selectedBusinessUnitCannotBeReplacedByAnotherProductsUnit() {
    fixture(); var auth = new UsernamePasswordAuthenticationToken("admin",null,List.of());
    auth.setDetails(Map.of("businessUnitType","HOUSEHOLD")); SecurityContextHolder.getContext().setAuthentication(auth);
    assertThatThrownBy(() -> query.readQuote(20L,"2026-10",admin)).hasMessageContaining("无权");
    verifyNoInteractions(organizations);
  }

  @Test void quoteStaffCanReadSourceButCannotUseAdminTrialEndpoints() {
    fixture(); var quote = new TechnicalDataActor(3L,"quote",Set.of("ingest:quote:cost-run:execute"));
    var result = query.readPreview(20L,"2026-10",quote);
    assertThat(result.context().oaFormItemId()).isEqualTo(20L);
    assertThat(result.context().accountingMonth()).isEqualTo("2026-10");
    assertThatThrownBy(() -> query.readQuote(20L,"2026-10",quote)).hasMessageContaining("管理员");
    assertThat(query.readPreview(20L,"2026-10",admin).product()).isEqualTo(result.product());
  }

  @Test void sourcePreviewDoesNotGrantUnassignedQuotesToTechniciansOrOaSessions() {
    for (var actor : List.of(new TechnicalDataActor(2L,"tech",Set.of("technical:data:task:list","technical:data:task:edit")),
        new TechnicalDataActor(1L,"oa",Set.of("*:*:*"),10L))) {
      assertThatThrownBy(() -> query.readPreview(20L,"2026-10",actor)).hasMessageContaining("无权");
    }
    verifyNoInteractions(items,forms,organizations);
  }

  @Test void sourcePreviewRejectsCrossBusinessUnitDeletedProductsAndInvalidMonths() {
    fixture();
    assertThatThrownBy(() -> query.readPreview(20L,"2026-13",admin)).hasMessageContaining("月份");
    items.selectById(20L).setDeleted(1);
    assertThatThrownBy(() -> query.readPreview(20L,"2026-10",admin)).hasMessageContaining("不存在");
    items.selectById(20L).setDeleted(0);
    var auth = new UsernamePasswordAuthenticationToken("admin",null,List.of());
    auth.setDetails(Map.of("businessUnitType","HOUSEHOLD"));SecurityContextHolder.getContext().setAuthentication(auth);
    assertThatThrownBy(() -> query.readPreview(20L,"2026-10",admin)).hasMessageContaining("无权");
    verifyNoInteractions(organizations);
  }
}
