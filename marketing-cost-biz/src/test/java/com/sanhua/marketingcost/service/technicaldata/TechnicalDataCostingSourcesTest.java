package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TechnicalDataCostingSourcesTest {
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final OaFormMapper forms = mock(OaFormMapper.class);
  private final QuoteBomContextResolver contexts = mock(QuoteBomContextResolver.class);
  private final TechnicalDataPublicSourceCheck publicSources = mock(TechnicalDataPublicSourceCheck.class);
  private final TechnicalDataSharedModules shared = mock(TechnicalDataSharedModules.class);
  private final QuoteTechnicalDataRepository repository = mock(QuoteTechnicalDataRepository.class);
  private final TechnicalDataVersionContentCodec codec = mock(TechnicalDataVersionContentCodec.class);
  private final TechnicalDataDependencies dependencies = mock(TechnicalDataDependencies.class);
  private final TechnicalDataOaWorkflowRepository workflow = mock(TechnicalDataOaWorkflowRepository.class);
  private final OaMessageCodec json = mock(OaMessageCodec.class);
  private final QuoteTechModuleMapper modules = mock(QuoteTechModuleMapper.class);
  private final TechnicalDataCostingSources service = new TechnicalDataCostingSources(
      items, forms, contexts, publicSources, shared, repository, codec, dependencies, workflow, json, modules);
  private QuoteTechTask task;
  private QuoteTechDataVersion version;

  @BeforeEach void setUp() {
    var item = new OaFormItem(); item.setId(10L); item.setOaFormId(1L); item.setMaterialNo("PRODUCT");
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA-CURRENT"); form.setBusinessUnitType("COMMERCIAL");
    when(items.selectById(10L)).thenReturn(item);
    when(forms.selectById(1L)).thenReturn(form);
    when(contexts.resolveOrganization(form,item)).thenReturn(new QuoteDataOrganization("210","COMMERCIAL"));
    fact("PACKAGE", TechnicalDataAvailability.MISSING);
    when(shared.find("PRODUCT",10L,"PACKAGE")).thenReturn(owner("PACKAGE","COMMERCIAL","2026-08"));
    var product = new QuoteTechProduct(); product.setId(20L); product.setTaskId(30L); product.setActiveFlag(1);
    product.setProductStatus("APPROVED"); product.setEffectiveVersionId(40L);
    task = new QuoteTechTask(); task.setId(30L); task.setActiveFlag(1); task.setTaskStatus("APPROVED");
    task.setReviewStatus("PASSED"); task.setOaFlowId(50L);
    version = new QuoteTechDataVersion(); version.setId(40L); version.setProductId(20L);
    version.setVersionStatus("APPROVED"); version.setContentFingerprint("approved");
    var submitted = new QuoteTechDataVersion(); submitted.setId(39L);
    when(repository.findProduct(20L)).thenReturn(Optional.of(product));
    when(repository.findTask(30L)).thenReturn(Optional.of(task));
    when(repository.findVersion(40L)).thenReturn(Optional.of(version));
    when(repository.findVersion(39L)).thenReturn(Optional.of(submitted));
    when(codec.fingerprint(any(),any(),anyList(),anyList(),anyList())).thenReturn("approved");
    when(workflow.findFlow(50L)).thenReturn(flow(true,"confirmed"));
    when(workflow.approvalBasis(50L)).thenReturn("basis");
    when(json.canonicalHash("basis")).thenReturn("confirmed");
    var module = new QuoteTechModule(); module.setModuleType("PACKAGE"); module.setCurrentVersionId(39L);
    when(modules.selectByProductId(20L)).thenReturn(List.of(module));
  }

  @Test void publicSourcePreventsReadingSupplement() {
    fact("PACKAGE", TechnicalDataAvailability.AVAILABLE);
    var selected = service.select(10L,"2026-09");
    selected.requireReady();
    assertThat(selected.sources()).isEmpty();
    verifyNoInteractions(shared,repository,workflow);
  }

  @Test void approvedOriginalProductSourceCanBeUsedAcrossMonths() {
    var selected = service.select(10L,"2026-09");
    selected.requireReady();
    var source = selected.sources().get("PACKAGE");
    assertThat(source.version().getId()).isEqualTo(40L);
    assertThat(source.moduleVersionId()).isEqualTo(39L);
    assertThat(source.product().getId()).isEqualTo(20L);
  }

  @Test void publicQueryErrorMustNotFallBackToApprovedSupplement() {
    fact("PACKAGE", TechnicalDataAvailability.ERROR);
    assertThatThrownBy(() -> service.select(10L,"2026-09").requireReady())
        .isInstanceOf(EffectiveTechnicalDataException.class).hasMessageContaining("公共来源异常");
    verifyNoInteractions(shared,repository);
  }

  @Test void ownApprovedMaterialIsReadableForI06PreparationBeforeQuoterConfirmation() {
    task.setOaFormId(1L);
    when(workflow.findFlow(50L)).thenReturn(flow(false, null));
    service.select(10L, "2026-09").requireReady();
    task.setTaskStatus("SUBMITTED");
    assertBlocked("TECH_DATA_TASK_NOT_APPROVED");
  }

  @Test void approvalDoesNotReplaceFinanceConfirmation() {
    when(workflow.findFlow(50L)).thenReturn(flow(true,null));
    assertBlocked("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED");
    when(workflow.findFlow(50L)).thenReturn(flow(false,"confirmed"));
    assertBlocked("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED");
    when(workflow.findFlow(50L)).thenReturn(flow(true,"old-basis"));
    assertBlocked("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED");
  }

  @Test void otherUnapprovedRequiredParticipantsBlockConsumption() {
    task.setTaskStatus("SUBMITTED");
    assertBlocked("TECH_DATA_TASK_NOT_APPROVED");
  }

  @Test void materialPriceOwnerUsesTheSameBusinessGateAndReadableErrors() {
    var context = service.select(10L, "2026-09").context();
    var owner = owner("PRICE", "COMMERCIAL", "2026-08");
    when(workflow.findFlow(50L)).thenReturn(flow(true, null));
    assertThatThrownBy(() -> service.requireSource(context, owner))
        .isInstanceOfSatisfying(EffectiveTechnicalDataException.class, error ->
            assertThat(error.errorCode()).isEqualTo("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED"))
        .hasMessageContaining("价格");
    task.setTaskStatus("SUBMITTED");
    assertThatThrownBy(() -> service.requireSource(context, owner))
        .isInstanceOfSatisfying(EffectiveTechnicalDataException.class, error ->
            assertThat(error.errorCode()).isEqualTo("TECH_DATA_TASK_NOT_APPROVED"));
  }

  @Test void organizationAndAnnualDataCannotBeSilentlyReused() {
    when(shared.find("PRODUCT",10L,"PACKAGE")).thenReturn(owner("PACKAGE","PLATE","2026-08"));
    assertBlocked("TECH_DATA_SOURCE_SCOPE_MISMATCH");
    fact("SALARY",TechnicalDataAvailability.MISSING);
    when(shared.find("PRODUCT",10L,"SALARY")).thenReturn(owner("SALARY","COMMERCIAL","2025-08"));
    assertBlocked("TECH_DATA_SOURCE_SCOPE_MISMATCH");
  }

  @Test void changedFrozenContentAndDependenciesBlockNewCosting() {
    version.setContentFingerprint("changed");
    assertBlocked("TECH_DATA_CONTENT_FINGERPRINT_MISMATCH");
    version.setContentFingerprint("approved");
    when(dependencies.stale(any(),anyList())).thenReturn(List.of(
        new TechnicalDataDependencies.Issue("PACKAGE","DRAWING_BOM","changed")));
    assertBlocked("TECH_DATA_SOURCE_CHANGED");
  }

  @Test void ownDraftCanPreparePricesButCannotBecomeFinalCostInput() {
    var product = repository.findProduct(20L).orElseThrow();
    product.setOaFormItemId(10L); product.setAccountingMonth("2026-09"); product.setEffectiveVersionId(null);
    task.setTaskStatus("EDITING");
    var draft = repository.findVersion(39L).orElseThrow(); draft.setProductId(20L); draft.setVersionStatus("DRAFT");
    assertThat(service.preparationSources(10L,"2026-09").get("PACKAGE").version().getId()).isEqualTo(39L);
    assertBlocked("TECH_DATA_TASK_NOT_APPROVED");
  }

  @Test void anotherQuoteCannotUseAnUnapprovedOriginalDraftEvenForPreparation() {
    task.setTaskStatus("EDITING");
    assertThatThrownBy(() -> service.preparationSources(10L,"2026-09"))
        .isInstanceOf(EffectiveTechnicalDataException.class).hasMessageContaining("尚未全部审批通过");
  }

  private void assertBlocked(String code) {
    var selected = service.select(10L,"2026-09");
    assertThat(selected.sources()).isEmpty();
    assertThat(selected.issues()).singleElement().satisfies(issue -> assertThat(issue.code()).isEqualTo(code));
    assertThatThrownBy(selected::requireReady).isInstanceOf(EffectiveTechnicalDataException.class);
  }

  private void fact(String type, TechnicalDataAvailability status) {
    when(publicSources.checkDataSources(any())).thenReturn(List.of(new TechnicalDataSourceFact(
        TechnicalDataModuleType.valueOf(type),status,"PUBLIC_CHECK","公共来源异常或明确缺失","source",LocalDateTime.now())));
  }
  private TechnicalDataSharedModuleRepository.Owner owner(String type,String unit,String month) {
    return new TechnicalDataSharedModuleRepository.Owner(21L,20L,30L,9L,"PRODUCT",type,
        "APPROVED","APPROVED",101L,"张工",39L,"APPROVED",unit,"210",month);
  }
  private TechnicalDataOaWorkflowRepository.Flow flow(boolean ready,String fingerprint) {
    return new TechnicalDataOaWorkflowRepository.Flow(50L,"MOCK","TEST",2L,"2026-08","DOC","FLOW",1L,ready,1L,fingerprint);
  }
}
