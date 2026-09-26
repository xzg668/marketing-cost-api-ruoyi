package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirementsResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration") @Transactional
class TechnicalDataPriceApplicationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  @Autowired TechnicalDataPriceApplicationService service;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataPriceOwnership ownership;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired TechnicalDataPricePublication publication;
  @Autowired com.sanhua.marketingcost.service.impl.TechnicalPriceSourceResolverImpl priceCalculator;
  @Autowired com.sanhua.marketingcost.mapper.PriceFixedItemMapper fixed;
  @Autowired com.sanhua.marketingcost.mapper.PriceLinkedItemMapper linked;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired com.sanhua.marketingcost.mapper.MaterialMasterRawMapper materials;
  @Autowired com.sanhua.marketingcost.service.MaterialPriceRouterService router;
  @Autowired com.sanhua.marketingcost.service.pricing.FixedPriceResolver fixedResolver;
  @MockBean TechnicalDataPriceRequirements requirements;
  @MockBean TechnicalDataPriceReferences references;
  private Long productId;
  private String material;

  @BeforeEach void fixture() {
    material = "TW15-" + UUID.randomUUID().toString().substring(0,8);
    productId = product(101L, "王工", "2026-09");
    when(requirements.check(any(), any())).thenReturn(check("MISSING", "basis"));
  }

  @Test void fixedDraftKeepsExactPriceAndDiscardsOtherModes() {
    var request = input("FIXED", 0); var item = request.getItems().getFirst();
    item.setUnitPrice(new BigDecimal("12.123456789")); item.setFormula("old formula"); item.setReferenceId(999L);
    item.setParameters(new TechnicalDataSupplementContent.PriceParameters(BigDecimal.ONE,null,"克",null,null,null));
    var saved = service.save(productId, request, WANG);
    var value = saved.content().items().getFirst();
    assertThat(saved.issues()).isEmpty(); assertThat(saved.moduleStatus()).isEqualTo("READY");
    assertThat(value.unitPrice()).isEqualByComparingTo("12.123456789");
    assertThat(value.formula()).isNull(); assertThat(value.reference()).isNull(); assertThat(value.parameters()).isNull();
    assertThat(ownership.find(material).productId()).isEqualTo(productId);
    assertThat(codec.prices(repository.findVersion(saved.versionId()).orElseThrow())).isEqualTo(saved.content());
    verifyNoInteractions(references);
  }

  @Test void incompleteDraftPersistsButCannotPassSubmissionValidation() {
    var saved = service.save(productId, input("FIXED", 0), WANG);
    assertThat(saved.moduleStatus()).isEqualTo("EDITING");
    assertThat(saved.content().items().getFirst().unitPrice()).isNull();
    assertThat(service.validateCurrent(repository.findProduct(productId).orElseThrow(), repository.findVersion(saved.versionId()).orElseThrow()))
        .anyMatch(message -> message.contains("必须大于 0"));
  }

  @Test void manualFormulaRemainsTextAndDoesNotCarryOldFixedOrReference() {
    var request = input("MANUAL",0); var item = request.getItems().getFirst();
    item.setFormula("铜价 + 加工费"); item.setUnitPrice(new BigDecimal("12")); item.setReferenceId(999L);
    var saved = service.save(productId,request,WANG);
    var value = saved.content().items().getFirst();
    assertThat(value.formula()).isEqualTo("铜价 + 加工费"); assertThat(value.unitPrice()).isNull(); assertThat(value.reference()).isNull();
    assertThat(saved.versionStatus()).isEqualTo("DRAFT"); verifyNoInteractions(references);
  }

  @Test void referenceCopiesServerSnapshotAndOnlyCurrentParameters() {
    var original = new TechnicalDataPriceReference(123L,"OTHER","原料","型号","只","210","COMMERCIAL","2026-08",
        "PROCESS_FEE", "加工费", null, 0, "原供应商", null, "ref", List.of());
    when(references.require(any(),eq(123L),eq("ref"))).thenReturn(original);
    var request = input("REFERENCE",0); var item = request.getItems().getFirst();
    item.setReferenceId(123L); item.setReferenceFingerprint("ref");
    item.setParameters(new TechnicalDataSupplementContent.PriceParameters(null,null,null,new BigDecimal("12"),null,"元/只"));
    var saved = service.save(productId,request,WANG);
    assertThat(saved.content().items().getFirst().reference()).isEqualTo(original);
    assertThat(saved.content().items().getFirst().parameters().processFee()).isEqualByComparingTo("12");
    assertThat(saved.content().items().getFirst().formula()).isEqualTo("PROCESS_FEE");
  }

  @Test void otherQuoteAndMonthCannotClaimSameMaterial() {
    service.save(productId,input("MANUAL",0),WANG);
    Long second = product(102L,"李工","2026-12");
    assertThatThrownBy(() -> service.save(second,input("FIXED",0),LI)).isInstanceOf(TechnicalDataTaskException.class)
        .hasMessageContaining("王工").hasMessageContaining("不能重复补价");
    assertThat(repository.findProduct(second).orElseThrow().getCurrentEditVersionId()).isNull();
    assertThat(ownership.find(material).productId()).isEqualTo(productId);
  }

  @Test void publicPriceAndChangedRequirementsRejectStaleSaveWithoutClaim() {
    when(requirements.check(any(),any())).thenReturn(check("AVAILABLE","changed"));
    assertThatThrownBy(() -> service.save(productId,input("FIXED",0),WANG)).hasMessageContaining("需求或来源已变化");
    var current = input("FIXED",0); current.setRequirementsFingerprint("changed");
    assertThatThrownBy(() -> service.save(productId,current,WANG)).hasMessageContaining("已无缺价");
    assertThat(ownership.find(material)).isNull();
  }

  @Test void wrongActorUnknownMaterialAndStaleVersionCannotWrite() {
    assertThatThrownBy(() -> service.save(productId,input("FIXED",0),LI)).isInstanceOf(TechnicalDataTaskException.class);
    var wrong = input("FIXED",0); wrong.getItems().getFirst().setItemKey("fabricated");
    assertThatThrownBy(() -> service.save(productId,wrong,WANG)).hasMessageContaining("不属于当前缺价需求");
    var saved = service.save(productId,input("FIXED",0),WANG);
    assertThatThrownBy(() -> service.save(productId,input("FIXED",0),WANG)).hasMessageContaining("其他会话修改");
    assertThat(repository.findVersion(saved.versionId()).orElseThrow().getVersionStatus()).isEqualTo("DRAFT");
  }

  @Test void approvedFixedPriceIsSharedAcrossMonthsAndPublicFixedPriceTakesPriority() {
    var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("test", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("*:*:*")));
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      var master=new MaterialMasterRaw(); master.setMaterialCode(material); master.setMaterialName("缺价料件"); master.setUnit("只");
      master.setOrganizationCode("COMMERCIAL"); master.setActiveFlag(1); master.setImportBatchId(material); master.setSourceType("EXCEL"); materials.insert(master);
      var request=input("FIXED",0); request.getItems().getFirst().setUnitPrice(new BigDecimal("12.123456789"));
      var saved=service.save(productId,request,WANG);
      var product=repository.lockProduct(productId).orElseThrow(); var task=repository.findTask(product.getTaskId()).orElseThrow();
      var person=new com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient(1L,task.getId(),1,101L,"王工","wang","FILL",List.of("PRICE"),1L,"todo","CONFIRMED","OPEN",null,"工程部","leader","王总",null,0,0,null,true,null,null,"T-TEST", null);
      var frozen=versions.freeze(product,person,saved.expectedVersion(),101L);
      var approved=versions.transition(versions.transition(frozen,"SUBMITTED",101L),"APPROVED",101L);
      versions.state(repository.lockProduct(productId).orElseThrow(),person,approved.getId(),"APPROVED");
      publication.publish(task,product,approved); publication.publish(task,product,approved);
      var routes=router.listCandidates(material,"2027-12",java.time.LocalDate.of(2027,12,1));
      assertThat(routes).hasSize(1); assertThat(routes.getFirst().supplemental()).isTrue();
      var item=new com.sanhua.marketingcost.dto.CostRunPartItemDto(); item.setPartCode(material); item.setPriceOrgCode("210");
      var context=new com.sanhua.marketingcost.dto.CostRunContext(); context.setBusinessUnitType("COMMERCIAL"); context.setPriceOrgCode("210"); context.setPricingMonth("2027-12");
      assertThat(fixedResolver.resolve("OTHER-QUOTE",item,routes.getFirst(),context).unitPrice()).isEqualByComparingTo("12.123456789");
      context.setPriceOrgCode("220");
      assertThat(fixedResolver.resolve("OTHER-QUOTE",item,routes.getFirst(),context).unitPrice()).isNull();
      context.setPriceOrgCode("210");
      var publicPrice=new PriceFixedItem(); publicPrice.setMaterialCode(material); publicPrice.setSourceKind("PUBLIC"); publicPrice.setBusinessUnitType("COMMERCIAL"); publicPrice.setOrgCode("210"); publicPrice.setFixedPrice(BigDecimal.TEN); publicPrice.setSourceType("PURCHASE_FIXED"); fixed.insert(publicPrice);
      var preferred=router.listCandidates(material,"2027-12",java.time.LocalDate.of(2027,12,1));
      assertThat(preferred).hasSize(3); assertThat(preferred.getFirst().supplemental()).isFalse();
      assertThat(fixedResolver.resolve("OTHER-QUOTE",item,preferred.getFirst(),context).unitPrice()).isEqualByComparingTo("10");
      assertThat(codec.prices(repository.findVersion(approved.getId()).orElseThrow()).items().getFirst().unitPrice()).isEqualByComparingTo("12.123456789");
      assertThat(fixed.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(PriceFixedItem.class).eq(PriceFixedItem::getTechnicalVersionId,approved.getId()))).hasSize(1);
    } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
  }

  @Test void approvedManualFormulaWaitsForFinanceAndDoesNotPublishPrice() {
    var request=input("MANUAL",0); request.getItems().getFirst().setFormula("铜价 + 加工费");
    var approved=approve(service.save(productId,request,WANG));
    assertThat(publication.statuses(approved).getFirst().status()).isEqualTo("WAIT_FINANCE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE technical_version_id=?",Integer.class,approved.getId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_linked_item WHERE technical_version_id=?",Integer.class,approved.getId())).isZero();
  }

  @Test void openingApprovedPriceWithoutExplicitVersionDoesNotRecalculateItsOldMonth() {
    var request = input("FIXED", 0);
    request.getItems().getFirst().setUnitPrice(new BigDecimal("12.123456789"));
    var approved = approve(service.save(productId, request, WANG));
    clearInvocations(requirements);
    when(requirements.check(any(), any()))
        .thenThrow(new IllegalArgumentException("当前重算核算月已变化"));

    var displayed = service.get(productId, null, WANG);
    var explicit = service.get(productId, approved.getId(), WANG);

    assertThat(displayed.historical()).isTrue();
    assertThat(displayed.editable()).isFalse();
    assertThat(displayed.versionId()).isEqualTo(approved.getId());
    assertThat(displayed.content()).isEqualTo(explicit.content());
    assertThat(displayed.content().items().getFirst().unitPrice())
        .isEqualByComparingTo("12.123456789");
    verifyNoInteractions(requirements);
  }

  @Test void approvedReferenceRunsActualFormulaAndRetainsOriginalSource() {
    var product=repository.findProduct(productId).orElseThrow(); var task=repository.findTask(product.getTaskId()).orElseThrow();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,?)",task.getOaNo(),"COMMERCIAL");
    var original=new PriceLinkedItem(); original.setMaterialCode("REFERENCE-"+material); original.setBusinessUnitType("COMMERCIAL");
    original.setPricingMonth("2026-09"); original.setFormulaExpr("[process_fee]"); original.setProcessFee(new BigDecimal("99"));
    original.setEffectiveFrom(java.time.LocalDate.of(2026,9,1)); original.setTaxIncluded(0); original.setUnit("只"); original.setSupplierName("原供应商"); original.setDeleted(0); linked.insert(original);
    var reference=new TechnicalDataPriceReference(original.getId(),original.getMaterialCode(),"原料","型号","只","210","COMMERCIAL","2026-09",
        "[process_fee]","加工费",null,0,"原供应商",null,"reference",List.of());
    when(references.require(any(),eq(original.getId()),eq("reference"))).thenReturn(reference);
    var request=input("REFERENCE",0); var input=request.getItems().getFirst();
    input.setReferenceId(original.getId()); input.setReferenceFingerprint("reference");
    input.setParameters(new TechnicalDataSupplementContent.PriceParameters(null,null,null,new BigDecimal("12"),null,"元/只"));
    var approved=approve(service.save(productId,request,WANG));
    var status=publication.statuses(approved).getFirst();
    assertThat(status.status()).withFailMessage(status.message()).isEqualTo("AVAILABLE");
    var published=linked.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(PriceLinkedItem.class).eq(PriceLinkedItem::getTechnicalVersionId,approved.getId())).getFirst();
    assertThat(published.getProcessFee()).isEqualByComparingTo("12"); assertThat(published.getSupplierName()).isNull();
    assertThat(linked.selectById(original.getId()).getProcessFee()).isEqualByComparingTo("99");
  }

  @Test void referenceCalculationFailureRemainsVisibleAndRetryDoesNotDuplicateSource() {
    var reference=new TechnicalDataPriceReference(999L,"REF","原料","型号","只","210","COMMERCIAL","2026-09",
        "[process_fee]","加工费",null,0,null,null,"reference",List.of());
    when(references.require(any(),eq(999L),eq("reference"))).thenReturn(reference);
    var request=input("REFERENCE",0); var input=request.getItems().getFirst();
    input.setReferenceId(999L); input.setReferenceFingerprint("reference");
    input.setParameters(new TechnicalDataSupplementContent.PriceParameters(null,null,null,new BigDecimal("12"),null,"元/只"));
    var approved=approve(service.save(productId,request,WANG));
    assertThat(publication.statuses(approved).getFirst().status()).isEqualTo("FAILED");
    var product=repository.findProduct(productId).orElseThrow(); var task=repository.findTask(product.getTaskId()).orElseThrow();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,?)",task.getOaNo(),"COMMERCIAL");
    publication.publish(task,product,approved);
    var status=publication.statuses(approved).getFirst();
    assertThat(status.status()).withFailMessage(status.message()).isEqualTo("AVAILABLE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_linked_item WHERE technical_version_id=?",Integer.class,approved.getId())).isEqualTo(1);
  }

  @Test
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void concurrentMaterialClaimsHaveExactlyOneOwner() throws Exception {
    Long second = product(102L, "李工", "2026-12");
    Long firstModule = jdbc.queryForObject("SELECT id FROM lp_quote_tech_module WHERE product_id=? AND module_type='PRICE'", Long.class, productId);
    Long secondModule = jdbc.queryForObject("SELECT id FROM lp_quote_tech_module WHERE product_id=? AND module_type='PRICE'", Long.class, second);
    var start = new java.util.concurrent.CountDownLatch(1);
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var futures = List.of(firstModule, secondModule).stream().map(module -> pool.submit(() -> {
        start.await();
        try {
          transaction.executeWithoutResult(status -> ownership.require(module, "COMMERCIAL", check("MISSING", "basis").items()));
          return "SUCCESS";
        } catch (TechnicalDataTaskException conflict) {
          assertThat(conflict.getMessage()).contains("不能重复补价");
          return "CONFLICT";
        }
      })).toList();
      start.countDown();
      assertThat(List.of(futures.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS), futures.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
      assertThat(ownership.find(material).moduleId()).isIn(firstModule, secondModule);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_price_claim WHERE material_code=?", Integer.class, material)).isEqualTo(1);
    }
  }

  @Test void monthlyReferenceCalculationUsesMonthlyFactorsAcrossMonthsWithoutOaLock() {
    var source=new PriceLinkedItem(); source.setId(999L); source.setMaterialCode(material); source.setBusinessUnitType("COMMERCIAL");
    source.setPricingMonth("2026-09"); source.setFormulaExpr("[process_fee]"); source.setProcessFee(new BigDecimal("12"));
    source.setTaxIncluded(0); source.setUnit("只"); source.setTechnicalVersionId(1L);
    var context=new com.sanhua.marketingcost.dto.CostRunContext();
    context.setScene(com.sanhua.marketingcost.dto.CostRunContext.SCENE_MONTHLY_REPRICE);
    context.setBusinessUnitType("COMMERCIAL"); context.setPricingMonth("2027-12");
    context.setPriceAsOfTime(LocalDateTime.of(2027,12,1,9,0));
    var result=priceCalculator.calculate(source,context);
    assertThat(result.getCalcStatus()).withFailMessage("计算结果：%s", result.getCalcMessage()).isEqualTo("OK");
    assertThat(result.getPartUnitPrice()).isPositive();
    assertThat(result.getCalcScene()).isEqualTo("MONTHLY_ADJUST");
    assertThat(result.getFactorSource()).isEqualTo("MONTHLY_FACTOR");
    assertThat(result.getPricingMonth()).isEqualTo("2027-12");
  }

  private QuoteTechDataVersion approve(TechnicalDataPriceResponse saved) {
    var product=repository.lockProduct(productId).orElseThrow(); var task=repository.findTask(product.getTaskId()).orElseThrow();
    var person=new com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient(1L,task.getId(),1,101L,"王工","wang","FILL",List.of("PRICE"),1L,"todo","CONFIRMED","OPEN",null,"工程部","leader","王总",null,0,0,null,true,null,null,"T-TEST", null);
    var frozen=versions.freeze(product,person,saved.expectedVersion(),101L);
    var approved=versions.transition(versions.transition(frozen,"SUBMITTED",101L),"APPROVED",101L);
    versions.state(repository.lockProduct(productId).orElseThrow(),person,approved.getId(),"APPROVED");
    publication.publish(task,product,approved);
    return approved;
  }

  private TechnicalDataPriceRequirementsResponse check(String status,String fingerprint) {
    return new TechnicalDataPriceRequirementsResponse(List.of(new TechnicalDataPriceRequirement("key",material,"料件","型号","210","只","CNY",List.of("BOM"),status,
        "AVAILABLE".equals(status) ? BigDecimal.TEN : null,"检查结果")),List.of(),fingerprint);
  }
  private TechnicalDataPriceSaveRequest input(String mode,int expected) {
    var request = new TechnicalDataPriceSaveRequest(); request.setExpectedVersion(expected); request.setRequirementsFingerprint("basis");
    var item = new TechnicalDataPriceSaveRequest.Item(); item.setItemKey("key"); item.setEntryMode(mode); request.setItems(List.of(item)); return request;
  }
  private Long product(long assignee,String name,String month) {
    var key = "TW15-" + UUID.randomUUID();
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(10L); task.setOaFormItemId(System.nanoTime());
    task.setOaNo(key); task.setAccountingMonth(month); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(assignee); task.setAssigneeName(name); var taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(task.getOaFormItemId()); product.setQuoteNo(key);
    product.setAccountingMonth(month); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{}");
    Long id = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = "PRICE".equals(type); var module = new QuoteTechModule(); module.setProductId(id); module.setModuleType(type);
      module.setRequiredFlag(required?1:0); module.setModuleStatus(required?"PENDING":"NOT_REQUIRED"); module.setSourceAvailability(required?"MISSING":"AVAILABLE");
      module.setSourceReference("PACKAGE".equals(type) ? "包装来源".repeat(200) : "TEST_SOURCE"); module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("实际缺价");
      module.setEntryMode(required?"MANUAL":"NONE"); module.setAssigneeUserId(assignee); module.setAssigneeName(name); persistence.createModule(module);
    }
    return id;
  }
}
