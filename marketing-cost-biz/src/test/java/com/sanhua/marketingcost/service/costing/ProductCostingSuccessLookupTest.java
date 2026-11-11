package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.*;

class ProductCostingSuccessLookupTest {
  final QuoteCostingWorkspaceService workspaces=mock(QuoteCostingWorkspaceService.class);
  final OaFormItemMapper items=mock(OaFormItemMapper.class);
  final QuoteCostRunVersionMapper versions=mock(QuoteCostRunVersionMapper.class);
  final CostingAlgorithmVersionProvider algorithms=new CostingAlgorithmVersionProvider("NEW_ALGORITHM");
  final ProductCostingSuccessLookup lookup=new ProductCostingSuccessLookup(workspaces,items,versions,algorithms);
  final OaForm form=new OaForm();
  final OaFormItem item=new OaFormItem();
  final QuoteCostingWorkspace workspace=new QuoteCostingWorkspace();
  final QuoteCostRunVersion version=new QuoteCostRunVersion();
  ProductCostingContext context;

  @BeforeEach void savedFormalPattern() {
    form.setId(23L);form.setOaNo("FI-SC-006-20261107-006");
    form.setBusinessUnitType("COMMERCIAL");
    item.setId(155L);item.setOaFormId(23L);item.setConfirmedCostVersionId(20L);
    workspace.setWorkspaceStatus("SUCCESS");workspace.setCurrentCostVersionId(20L);
    workspace.setInputFingerprint("SAVED-INPUT");workspace.setLastSuccessInputFingerprint("SAVED-INPUT");
    workspace.setSourceRevision("SAVED-SOURCE");workspace.setLastSuccessSourceRevision("SAVED-SOURCE");
    version.setId(20L);version.setOaNo(form.getOaNo());version.setOaFormItemId(item.getId());
    version.setPricingMonth("2026-11");version.setStatus("SUCCESS");
    version.setBusinessUnitType("COMMERCIAL");
    version.setInputFingerprint("SAVED-INPUT");version.setSourceRevision("SAVED-SOURCE");
    version.setAlgorithmVersion("MONTHLY_PRODUCT_COST_V1");version.setFinalQuoteAmount(new BigDecimal("32.93162700"));
    context=new ProductCostingContext(form,item,"1145900000048","2026-11","报价员","CURRENT-SOURCES-CHANGED");
    when(workspaces.find(155L,"2026-11")).thenReturn(Optional.of(workspace));
    when(items.selectById(155L)).thenReturn(item);
    when(versions.selectById(20L)).thenReturn(version);
  }

  @Test void savedCostCanBeSubmittedWhileActiveRecalculationDoesNotReuseOldInputs() {
    assertThat(lookup.find(context)).isEmpty();
    assertThat(lookup.findSaved(context)).hasValueSatisfying(saved ->
        assertThat(saved.version().getFinalQuoteAmount()).isEqualByComparingTo("32.93162700"));
  }

  @Test void incompleteOrDifferentCurrentVersionCannotSubmitThePreviousResult() {
    workspace.setWorkspaceStatus("RUNNING");assertThat(lookup.findSaved(context)).isEmpty();
    workspace.setWorkspaceStatus("SUCCESS");item.setConfirmedCostVersionId(21L);
    assertThat(lookup.findSaved(context)).isEmpty();
  }

  @Test void savedReaderAlwaysFollowsTheNewlyCompletedVersion() {
    var second=new QuoteCostRunVersion();
    second.setId(21L);second.setOaNo(form.getOaNo());second.setOaFormItemId(155L);
    second.setPricingMonth("2026-11");second.setStatus("SUCCESS");second.setInputFingerprint("NEW-INPUT");
    second.setSourceRevision("NEW-SOURCE");second.setFinalQuoteAmount(new BigDecimal("33.50000000"));
    when(versions.selectById(21L)).thenReturn(second);
    item.setConfirmedCostVersionId(21L);workspace.setCurrentCostVersionId(21L);
    workspace.setInputFingerprint("NEW-INPUT");workspace.setLastSuccessInputFingerprint("NEW-INPUT");
    workspace.setSourceRevision("NEW-SOURCE");workspace.setLastSuccessSourceRevision("NEW-SOURCE");
    assertThat(lookup.findSaved(context)).hasValueSatisfying(saved ->
        assertThat(saved.version().getFinalQuoteAmount()).isEqualByComparingTo("33.5"));
    assertThat(version.getFinalQuoteAmount()).isEqualByComparingTo("32.93162700");
  }

}
