package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TechnicalDataPendingPreviewTest {
  private final List<String> checksCalled = new ArrayList<>();
  private final TechnicalDataSourceCheckStore store = mock(TechnicalDataSourceCheckStore.class);
  private final TechnicalDataSourceCheckService checks = mock(TechnicalDataSourceCheckService.class, call -> {
    checksCalled.add(call.getMethod().getName());
    return new TechnicalDataSourceCheckService.Check(List.of(new TechnicalDataSourceFact(
        TechnicalDataModuleType.SALARY, TechnicalDataAvailability.AVAILABLE, "SALARY_AVAILABLE", "工资已补齐", "salary-current", LocalDateTime.now())), Map.of());
  });
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final OaFormMapper forms = mock(OaFormMapper.class);
  private final QuoteCostingWorkspaceMapper workspaces = mock(QuoteCostingWorkspaceMapper.class);
  private final QuoteBomContextResolver context = mock(QuoteBomContextResolver.class);
  private final TechnicalDataSharedModuleQuery shared = mock(TechnicalDataSharedModuleQuery.class);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
  private final TechnicalDataQuoteSourceReader reader = new TechnicalDataQuoteSourceReader(items,forms,
      mock(OaFormItemExtraFieldMapper.class),workspaces,context,checks,new TechnicalDataModuleRequirementEvaluator(),
      store,new TechnicalDataSourceSnapshotFactory(json),new OaMessageCodec(json),mock(TechnicalDataRequirementRefreshService.class),shared);

  @BeforeEach void fixture() {
    var authentication = new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("*:*:*")));
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,"COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var item=new OaFormItem();item.setId(7L);item.setOaFormId(1L);item.setMaterialNo("PRODUCT");
    var form=new OaForm();form.setId(1L);form.setOaNo("OA");form.setBusinessUnitType("COMMERCIAL");
    workspace.setBusinessUnitType("COMMERCIAL");workspace.setLastCheckedAt(LocalDateTime.now());workspace.setCurrentBomBuildBatchId("BOM");
    when(items.selectById(7L)).thenReturn(item);when(forms.selectById(1L)).thenReturn(form);
    when(workspaces.selectByItemAndMonth(7L,"2026-11")).thenReturn(workspace);
    when(context.resolveOrganization(form,item)).thenReturn(new QuoteDataOrganization("210","COMMERCIAL"));
    when(shared.describe(any(),any(),any())).thenReturn(List.of());
  }
  @AfterEach void clear() {SecurityContextHolder.clearContext();}

  @Test void salaryOnlyPendingRowDoesNotRunTheUnrelatedWholeProductPriceChain() {
    saved("SALARY",true,"SALARY_MISSING");
    var result=reader.preview(7L,"2026-11");
    assertThat(result.modules()).filteredOn(row -> row.moduleType().equals("SALARY"))
        .allSatisfy(row -> assertThat(row.availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE));
    assertThat(checksCalled).as("补录列表只复核原待办模块，不能因缺工资跑完整取价链").containsExactly("preview");
    verify(checks).preview(any(), any(), eq(Set.of(TechnicalDataModuleType.SALARY)));
  }

  @Test void uncertainMixedPriceKeepsTheCompleteSourceCheckAndFingerprintBasis() {
    saved("PRICE",false,"PRICE_BASIS_UNCONFIRMED");
    reader.preview(7L,"2026-11");
    assertThat(checksCalled).containsExactly("preview");
    verify(checks).preview(any(), any(), isNull());
  }

  private void saved(String type,boolean required,String reason) {
    var now=LocalDateTime.now();
    when(store.read(workspace)).thenReturn(new TechnicalDataSourceCheckResponse(7L,"2026-11","saved",now,
        List.of(new TechnicalDataModuleRequirement(type,required,reason,"原待办",TechnicalDataAvailability.MISSING,"old",now)),null,List.of()));
  }
}
