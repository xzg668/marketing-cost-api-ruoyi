package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Dependency;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** The existing final-source business gate must accept a complete read capture without live I/O. */
class CapturedTechnicalDataSourcesTest {
  final QuoteTechnicalDataRepository repository=mock(QuoteTechnicalDataRepository.class);
  final QuoteTechModuleMapper moduleMapper=mock(QuoteTechModuleMapper.class);
  final TechnicalDataOaWorkflowRepository workflow=mock(TechnicalDataOaWorkflowRepository.class);
  final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
  final TechnicalDataVersionContentCodec codec=new TechnicalDataVersionContentCodec(mapper);
  final OaMessageCodec json=new OaMessageCodec(mapper);
  final TechnicalDataDependencies dependencies=new TechnicalDataDependencies(repository,codec,json);
  final TechnicalDataCostingSources service=new TechnicalDataCostingSources(null,null,null,null,null,repository,codec,dependencies,workflow,json,moduleMapper);
  final QuoteBomReadContext context=new QuoteBomReadContext(1L,10L,"CURRENT","2026-10","COMMERCIAL","TOP",null,null,null,"210","COMMERCIAL",LocalDate.of(2026,10,23),LocalDateTime.of(2026,10,23,9,0));
  final TechnicalDataSharedModuleRepository.Owner owner=new TechnicalDataSharedModuleRepository.Owner(21,20,30,9,"ORIGINAL","PRICE","SUBMITTED","SUBMITTED",1L,"fixture",39L,"SUBMITTED","COMMERCIAL","210","2026-09");
  final QuoteTechTask task=new QuoteTechTask();final QuoteTechProduct product=new QuoteTechProduct();
  final QuoteTechModule price=new QuoteTechModule();final QuoteTechDataVersion personal=new QuoteTechDataVersion(),effective=new QuoteTechDataVersion();
  List<QuoteTechModule> modules;
  String basis="[21:51:39:original]";
  TechnicalDataOaWorkflowRepository.Flow flow;

  @BeforeEach void ready(){
    task.setId(30L);task.setOaFormId(2L);task.setActiveFlag(1);task.setTaskStatus("SUBMITTED");task.setOaFlowId(50L);task.setBusinessUnitType("COMMERCIAL");task.setApplicableOrgCode("210");
    product.setId(20L);product.setTaskId(30L);product.setActiveFlag(1);product.setProductStatus("SUBMITTED");product.setEffectiveVersionId(40L);product.setAccountingMonth("2026-09");
    price.setId(21L);price.setProductId(20L);price.setModuleType("PRICE");price.setRequiredFlag(1);price.setSourceAvailability("MISSING");price.setModuleStatus("SUBMITTED");price.setCurrentVersionId(39L);
    modules=TechnicalDataModuleType.orderedCodes().stream().map(type->{
      if(type.equals("PRICE"))return price;
      var module=new QuoteTechModule();module.setId(100L+TechnicalDataModuleType.orderOf(type));module.setProductId(20L);module.setModuleType(type);module.setRequiredFlag(0);module.setModuleStatus("NOT_REQUIRED");module.setSourceAvailability("AVAILABLE");return module;
    }).toList();
    for(var version:List.of(personal,effective)){version.setProductId(20L);version.setVersionStatus("SUBMITTED");version.setContentSchemaVersion(2);version.setReferenceSnapshotJson(codec.referenceSnapshotJson(codec.moduleSnapshots(modules)));version.setSourceFactsJson(codec.sourceFactsJson("{}",modules));}
    personal.setId(39L);effective.setId(40L);seal(personal);seal(effective);
    flow=new TechnicalDataOaWorkflowRepository.Flow(50,"MOCK","TEST",2,"2026-09","D","F",1,true,1L,json.canonicalHash(basis));
  }
  void seal(QuoteTechDataVersion version){version.setContentFingerprint(codec.fingerprint(version,codec.readReferenceSnapshot(version.getReferenceSnapshotJson()),List.of(),List.of(),List.of()));}
  TechnicalDataCostingSources.Captured captured(){return new TechnicalDataCostingSources.Captured(task,product,Map.of(39L,personal,40L,effective),modules,Map.of(39L,List.of(),40L,List.of()),Map.of(39L,List.of(),40L,List.of()),Map.of(39L,List.of(),40L,List.of()),flow,basis);}
  @AfterEach void noIo(){verifyNoInteractions(repository,moduleMapper,workflow);}
  @Test void finalAggregateAndPersonalModuleVersionRemainDistinctAndBothArePreserved(){var selected=service.requireCapturedSource(context,owner,captured());assertThat(selected.version().getId()).isEqualTo(40);assertThat(selected.moduleVersionId()).isEqualTo(39);assertThat(selected.product().getId()).isEqualTo(20);}
  @Test void aSubmittedPriceModuleAloneCannotReplaceTheMissingFinalAggregate(){product.setEffectiveVersionId(null);assertBlocked("TECH_DATA_TASK_NOT_SUBMITTED");}
  @Test void incompleteOtherParticipantsAndReturnedOriginalTaskStillBlockThePrice(){task.setTaskStatus("IN_PROGRESS");assertBlocked("TECH_DATA_TASK_NOT_SUBMITTED");task.setTaskStatus("SUBMITTED");product.setProductStatus("RETURNED");assertBlocked("TECH_DATA_TASK_NOT_SUBMITTED");}
  @Test void aFinalPointerToAnotherProductOrDraftIsRejected(){effective.setProductId(99L);assertBlocked("TECH_DATA_EFFECTIVE_VERSION_INVALID");effective.setProductId(20L);effective.setVersionStatus("DRAFT");assertBlocked("TECH_DATA_EFFECTIVE_VERSION_INVALID");}
  @Test void changedFinalFrozenContentCannotBeConsumed(){effective.setProductModel("tampered");assertBlocked("TECH_DATA_CONTENT_FINGERPRINT_MISMATCH");}
  @Test void anotherQuoteNeedsCurrentFinanceConfirmation(){flow=new TechnicalDataOaWorkflowRepository.Flow(50,"MOCK","TEST",2,"2026-09","D","F",1,false,1L,json.canonicalHash(basis));assertBlocked("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED");flow=new TechnicalDataOaWorkflowRepository.Flow(50,"MOCK","TEST",2,"2026-09","D","F",1,true,1L,"old");assertBlocked("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED");}
  @Test void ownQuoteKeepsExistingAbilityToReadFinalSubmittedContentBeforeItsFinanceConfirmation(){task.setOaFormId(1L);flow=null;assertThat(service.requireCapturedSource(context,owner,captured()).version().getId()).isEqualTo(40);}
  @Test void actualModulePointerChangeCannotHideBehindAnOldClaim(){price.setCurrentVersionId(41L);assertBlocked("TECH_DATA_SOURCE_CHANGED");}
  @Test void unqueriedEmptyDetailsAreNotInventedAsACompleteCapture(){var raw=captured();var incomplete=new TechnicalDataCostingSources.Captured(task,product,raw.versions(),modules,Map.of(),raw.auxiliaryItems(),raw.salaryItems(),flow,basis);assertThatThrownBy(()->service.requireCapturedSource(context,owner,incomplete)).isInstanceOf(RuntimeException.class).hasMessageContaining("capture");}
  @Test void aConfirmationCapturedFromAnotherFlowCannotAuthorizeThisSource(){flow=new TechnicalDataOaWorkflowRepository.Flow(99,"MOCK","TEST",2,"2026-09","D","F",1,true,1L,json.canonicalHash(basis));assertBlocked("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED");}
  @Test void aCapturedDraftPersonalVersionCannotHideBehindTheEarlierSubmittedOwnerRow(){personal.setVersionStatus("DRAFT");assertBlocked("TECH_DATA_EFFECTIVE_VERSION_INVALID");}
  @Test void aCapturedUnsubmittedRequiredParticipantCannotHideBehindAnEarlierAggregatePointer(){var salary=module("SALARY");salary.setRequiredFlag(1);salary.setModuleStatus("DRAFT");assertBlocked("TECH_DATA_TASK_NOT_SUBMITTED");}
  @Test void omittedCapturedModulesAreRejectedInsteadOfBeingTreatedAsUnneeded(){modules=modules.stream().filter(module->!module.getModuleType().equals("SALARY")).toList();assertBlocked("TECH_DATA_MODULE_SET_INVALID");}
  @Test void capturedPublicDependencyUsesTheSameFingerprintAndDetectsAChangedSourceWithoutIo(){
    var manufacturing=module("MANUFACTURING");manufacturing.setSourceReference("public-v1");
    personal.setSourceFactsJson(codec.sourceFactsJson("{}",modules,List.of(new Dependency("PRICE","MANUFACTURING",null,json.dataFingerprint(Map.of("availability","AVAILABLE","sourceReference","public-v1"))))));seal(personal);
    assertThat(service.requireCapturedSource(context,owner,captured()).moduleVersionId()).isEqualTo(39);manufacturing.setSourceReference("public-v2");assertBlocked("TECH_DATA_SOURCE_CHANGED");
  }
  @Test void capturedPackageDependencyUsesActualCodesAndUnitsWhileQuantityOnlyEditsDoNotInvalidatePrice(){
    var actual=packageDependency();var row=actual.packageItems().get(52L).getFirst();
    assertThat(service.requireCapturedSource(context,owner,actual).moduleVersionId()).isEqualTo(39);row.setStandardQuantity(new java.math.BigDecimal("99"));assertThat(service.requireCapturedSource(context,owner,actual).moduleVersionId()).isEqualTo(39);row.setOriginalUnit("KG");assertThatThrownBy(()->service.requireCapturedSource(context,owner,actual)).isInstanceOfSatisfying(EffectiveTechnicalDataException.class,error->assertThat(error.errorCode()).isEqualTo("TECH_DATA_SOURCE_CHANGED"));
  }
  @Test void aNonTargetRosterModuleFromAnotherProductCannotBeConsumed(){module("SALARY").setProductId(999L);assertBlocked("TECH_DATA_EFFECTIVE_VERSION_INVALID");}
  @Test void capturedDependencyVersionMapKeyMustMatchItsActualRowIdentity(){
    var actual=packageDependency();actual.versions().get(52L).setId(53L);
    var alias=new QuoteTechPackageItem();alias.setVersionId(53L);alias.setComponentMaterialNo("C1");alias.setOriginalUnit("件");var rows=new HashMap<>(actual.packageItems());rows.put(53L,List.of(alias));
    // Supplying the other ID's complete rows cannot hide a mismatched version-map identity.
    assertInvalidCapture(new TechnicalDataCostingSources.Captured(task,product,actual.versions(),modules,rows,actual.auxiliaryItems(),actual.salaryItems(),flow,basis));
  }
  @Test void capturedDependencyVersionMustBelongToItsModuleProduct(){var actual=packageDependency();actual.versions().get(52L).setProductId(999L);assertInvalidCapture(actual);}
  @Test void capturedDetailsMustBelongToTheirQueriedVersionEvenWhenTheirBusinessValuesMatch(){var actual=packageDependency();actual.packageItems().get(52L).getFirst().setVersionId(999L);assertInvalidCapture(actual);}
  @Test void capturedTaskBusinessUnitMustAgreeWithTheOwnerQuery(){task.setBusinessUnitType("HOUSEHOLD");assertBlocked("TECH_DATA_SOURCE_SCOPE_MISMATCH");}
  @Test void capturedTaskOrganizationMustAgreeWithTheOwnerQuery(){task.setApplicableOrgCode("220");assertBlocked("TECH_DATA_SOURCE_SCOPE_MISMATCH");}
  @Test void capturedOriginalMonthMustAgreeWithTheOwnerQuery(){product.setAccountingMonth("2025-09");assertBlocked("TECH_DATA_SOURCE_SCOPE_MISMATCH");}

  private TechnicalDataCostingSources.Captured packageDependency(){
    var packaging=module("PACKAGE");packaging.setRequiredFlag(1);packaging.setModuleStatus("SUBMITTED");packaging.setSourceAvailability("MISSING");packaging.setCurrentVersionId(52L);
    var packagingVersion=new QuoteTechDataVersion();packagingVersion.setId(52L);packagingVersion.setProductId(20L);packagingVersion.setVersionStatus("SUBMITTED");packagingVersion.setContentSchemaVersion(2);
    var row=new QuoteTechPackageItem();row.setVersionId(52L);row.setComponentMaterialNo("C1");row.setOriginalUnit("件");
    personal.setSourceFactsJson(codec.sourceFactsJson("{}",modules,List.of(new Dependency("PRICE","PACKAGE",52L,json.dataFingerprint(List.of("C1|件"))))));seal(personal);
    var input=captured();var versions=new HashMap<>(input.versions());versions.put(52L,packagingVersion);var packages=new HashMap<>(input.packageItems());packages.put(52L,List.of(row));
    return new TechnicalDataCostingSources.Captured(task,product,versions,modules,packages,input.auxiliaryItems(),input.salaryItems(),flow,basis);
  }
  private void assertInvalidCapture(TechnicalDataCostingSources.Captured value){assertThatThrownBy(()->service.requireCapturedSource(context,owner,value)).isInstanceOf(RuntimeException.class).hasMessageContaining("captured");}
  private QuoteTechModule module(String type){return modules.stream().filter(module->module.getModuleType().equals(type)).findFirst().orElseThrow();}
  private void assertBlocked(String code){assertThatThrownBy(()->service.requireCapturedSource(context,owner,captured())).isInstanceOfSatisfying(EffectiveTechnicalDataException.class,error->assertThat(error.errorCode()).isEqualTo(code));}
}
