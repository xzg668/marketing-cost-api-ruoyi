package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPruner;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPrunerImpl;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIdentity;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeAuditService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeMonthlyInheritanceResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeMonthlyInheritanceService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionRepository;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.impl.QuoteBomAlternativeApplicationServiceImpl;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class QuoteBomAlternativeApiContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(
            new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(
        assistant, QuoteBomPreparationRecord.class);
    TableInfoHelper.initTableInfo(
        assistant, BomRawHierarchy.class);
  }

  @Test
  void controllerExposesOnlyQueryPutAndHistoryUnderStableResourcePath()
      throws Exception {
    RequestMapping root =
        QuoteBomAlternativeController.class.getAnnotation(
            RequestMapping.class);

    assertThat(root.value())
        .containsExactly(
            "/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}"
                + "/costing-bom/alternative-groups");
    assertThat(
            QuoteBomAlternativeController.class
                .getMethod(
                    "getAlternativeGroups",
                    String.class,
                    Long.class,
                    String.class)
                .getAnnotation(GetMapping.class)
                .value())
        .isEmpty();
    assertThat(
            QuoteBomAlternativeController.class
                .getMethod(
                    "saveSelection",
                    String.class,
                    Long.class,
                    String.class,
                    QuoteBomAlternativeSelectionRequest.class,
                    org.springframework.security.core.Authentication.class)
                .getAnnotation(PutMapping.class)
                .value())
        .containsExactly("/{groupKey}/selection");
    assertThat(
            QuoteBomAlternativeController.class
                .getMethod(
                    "getSelectionHistory",
                    String.class,
                    Long.class,
                    String.class,
                    String.class)
                .getAnnotation(GetMapping.class)
                .value())
        .containsExactly("/{groupKey}/history");
  }

  @Test
  void selectionRequestContainsOnlyScopeConcurrencyAndBusinessIntent()
      throws Exception {
    QuoteBomAlternativeSelectionRequest request =
        objectMapper.readValue(
            """
            {
              "periodMonth": "2026-07",
              "selectedMaterialCode": "ALT",
              "expectedSelectionVersion": 1,
              "expectedBuildBatchId": "BUILD-1",
              "confirmDiscardManualChanges": true,
              "selectionRemark": "本次报价采用替代件",
              "materialName": "前端伪造名称",
              "childType": "STANDARD",
              "qtyPerParent": 999
            }
            """,
            QuoteBomAlternativeSelectionRequest.class);

    assertThat(request.periodMonth()).isEqualTo("2026-07");
    assertThat(request.selectedMaterialCode()).isEqualTo("ALT");
    assertThat(request.expectedSelectionVersion()).isEqualTo(1);
    assertThat(request.expectedBuildBatchId()).isEqualTo("BUILD-1");
    assertThat(request.confirmDiscardManualChanges()).isTrue();
    assertThat(request.selectionRemark()).contains("替代件");
    assertThat(QuoteBomAlternativeSelectionRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly(
            "periodMonth",
            "selectedMaterialCode",
            "expectedSelectionVersion",
            "expectedBuildBatchId",
            "confirmDiscardManualChanges",
            "selectionRemark");
  }

  @Test
  void responseMakesRebuildAndIdempotencyObservable() throws Exception {
    QuoteBomAlternativeSelectionResponse response =
        new QuoteBomAlternativeSelectionResponse(
            "GROUP",
            2,
            "ALT",
            "ALTERNATIVE",
            "MANUAL_ALTERNATIVE",
            false,
            true,
            true,
            35,
            36,
            "BUILD-2",
            List.of("PRICE_TYPE_CONFIRMATION", "COST_RUN"));

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

    assertThat(json.get("alternativeGroupKey").asText())
        .isEqualTo("GROUP");
    assertThat(json.get("selectionVersion").asInt()).isEqualTo(2);
    assertThat(json.get("idempotent").asBoolean()).isFalse();
    assertThat(json.get("rebuilt").asBoolean()).isTrue();
    assertThat(json.get("manualChangesDiscarded").asBoolean()).isTrue();
    assertThat(json.get("rowsBefore").asInt()).isEqualTo(35);
    assertThat(json.get("rowsAfter").asInt()).isEqualTo(36);
    assertThat(json.get("newBuildBatchId").asText()).isEqualTo("BUILD-2");
    assertThat(json.get("workflowInvalidated")).hasSize(2);
  }

  @Test
  void applicationServiceBuildsCommandFromBackendQuoteScope()
      throws Exception {
    BomRawHierarchyMapper rawMapper =
        mock(BomRawHierarchyMapper.class);
    BomAlternativeGroupResolver resolver =
        mock(BomAlternativeGroupResolver.class);
    BomAlternativeBranchPruner pruner =
        mock(BomAlternativeBranchPruner.class);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    QuoteBomAlternativeSelectionRepository repository =
        mock(QuoteBomAlternativeSelectionRepository.class);
    QuoteBomAlternativeRebuildService rebuildService =
        mock(QuoteBomAlternativeRebuildService.class);
    QuoteBomPreparationRecordMapper preparationMapper =
        mock(QuoteBomPreparationRecordMapper.class);
    OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
    OaFormMapper formMapper = mock(OaFormMapper.class);
    QuoteBomAlternativeMonthlyInheritanceService monthlyInheritanceService =
        mock(QuoteBomAlternativeMonthlyInheritanceService.class);
    when(monthlyInheritanceService.inheritIfFrozen(any(), any()))
        .thenReturn(QuoteBomAlternativeMonthlyInheritanceResult.notFrozen());
    QuoteBomAlternativeApplicationServiceImpl service =
        new QuoteBomAlternativeApplicationServiceImpl(
            rawMapper,
            resolver,
            pruner,
            selectionService,
            repository,
            rebuildService,
            mock(QuoteBomAlternativeAuditService.class),
            preparationMapper,
            itemMapper,
            formMapper,
            new QuoteBomContextResolver(),
            monthlyInheritanceService,
            objectMapper);

    QuoteBomPreparationRecord preparation =
        new QuoteBomPreparationRecord();
    preparation.setId(90L);
    preparation.setOaFormId(9L);
    preparation.setOaFormItemId(901L);
    preparation.setOaNo("OA-QBA-09");
    preparation.setQuoteProductCode("TOP-AUTH");
    preparation.setProductType("NON_BARE");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    OaFormItem item = new OaFormItem();
    item.setId(901L);
    item.setOaFormId(9L);
    item.setBusinessUnitType("COMMERCIAL");
    OaForm form = new OaForm();
    form.setId(9L);
    form.setOaNo("OA-QBA-09");
    form.setBusinessUnitType("COMMERCIAL");
    when(preparationMapper.selectOne(any())).thenReturn(preparation);
    when(itemMapper.selectById(901L)).thenReturn(item);
    when(formMapper.selectById(9L)).thenReturn(form);
    when(rebuildService.rebuild(any()))
        .thenReturn(
            new QuoteBomAlternativeRebuildResult(
                new QuoteBomAlternativeSelectionResult(
                    "SEL-2",
                    "GROUP",
                    "STD",
                    "ALT",
                    BomChildType.ALTERNATIVE,
                    "MANUAL_ALTERNATIVE",
                    2,
                    "ACTIVE",
                    false,
                    false,
                    true,
                    "IMPORT-1",
                    "BUILD-2"),
                false,
                true,
                false,
                35,
                36,
                "BUILD-2",
                1,
                1,
                1));

    QuoteBomAlternativeSelectionResponse response =
        service.saveSelection(
            "OA-QBA-09",
            901L,
            "GROUP",
            new QuoteBomAlternativeSelectionRequest(
                "2026-07",
                "ALT",
                1,
                "BUILD-1",
                false,
                "业务选择"),
            "quoter");

    ArgumentCaptor<QuoteBomAlternativeRebuildCommand> captor =
        ArgumentCaptor.forClass(
            QuoteBomAlternativeRebuildCommand.class);
    verify(rebuildService).rebuild(captor.capture());
    QuoteBomAlternativeRebuildCommand command = captor.getValue();
    assertThat(command.oaNo()).isEqualTo("OA-QBA-09");
    assertThat(command.oaFormItemId()).isEqualTo(901L);
    assertThat(command.topProductCode()).isEqualTo("TOP-AUTH");
    assertThat(command.priceOrgCode()).isEqualTo("210");
    assertThat(command.materialOrganizationCode())
        .isEqualTo("COMMERCIAL");
    assertThat(command.businessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(command.bomPurpose()).isEqualTo("主制造");
    assertThat(command.selectedMaterialCode()).isEqualTo("ALT");
    assertThat(command.selectedBy()).isEqualTo("quoter");
    assertThat(response.workflowInvalidated())
        .containsExactly(
            "PRICE_TYPE_CONFIRMATION",
            "PRICE_PREPARE",
            "FINAL_PRICE",
            "COST_RUN");
  }

  @Test
  void queryReturnsOnlyBackendResolvedCandidatesAndCurrentSelection() {
    BomRawHierarchyMapper rawMapper =
        mock(BomRawHierarchyMapper.class);
    BomAlternativeGroupResolver resolver =
        mock(BomAlternativeGroupResolver.class);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    QuoteBomAlternativeSelectionRepository repository =
        mock(QuoteBomAlternativeSelectionRepository.class);
    QuoteBomAlternativeRebuildService rebuildService =
        mock(QuoteBomAlternativeRebuildService.class);
    QuoteBomPreparationRecordMapper preparationMapper =
        mock(QuoteBomPreparationRecordMapper.class);
    OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
    OaFormMapper formMapper = mock(OaFormMapper.class);
    QuoteBomAlternativeMonthlyInheritanceService monthlyInheritanceService =
        mock(QuoteBomAlternativeMonthlyInheritanceService.class);
    when(monthlyInheritanceService.inheritIfFrozen(any(), any()))
        .thenReturn(QuoteBomAlternativeMonthlyInheritanceResult.notFrozen());
    QuoteBomAlternativeApplicationServiceImpl service =
        new QuoteBomAlternativeApplicationServiceImpl(
            rawMapper,
            resolver,
            new BomAlternativeBranchPrunerImpl(),
            selectionService,
            repository,
            rebuildService,
            mock(QuoteBomAlternativeAuditService.class),
            preparationMapper,
            itemMapper,
            formMapper,
            new QuoteBomContextResolver(),
            monthlyInheritanceService,
            objectMapper);
    stubQuoteScope(preparationMapper, itemMapper, formMapper);

    List<BomRawHierarchy> rows =
        List.of(
            row(1L, "TOP-AUTH", "TOP-AUTH", 0, "/TOP-AUTH/", null, null),
            row(2L, "PARENT", "TOP-AUTH", 1, "/TOP-AUTH/PARENT/", null, null),
            row(3L, "STD", "PARENT", 2, "/TOP-AUTH/PARENT/STD/", "GROUP", "STANDARD"),
            row(4L, "ALT", "PARENT", 2, "/TOP-AUTH/PARENT/ALT/", "GROUP", "ALTERNATIVE"));
    BomAlternativeGroup group =
        new BomAlternativeGroup(
            new BomAlternativeGroupIdentity(
                "210",
                "TOP-AUTH",
                "PARENT-FP",
                "PARENT",
                "主制造",
                "V1",
                LocalDate.of(2026, 1, 1),
                null,
                10,
                "010"),
            "GROUP",
            List.of(
                candidate(rows.get(2), BomChildType.STANDARD),
                candidate(rows.get(3), BomChildType.ALTERNATIVE)));
    QuoteBomAlternativeSelectionResult current =
        new QuoteBomAlternativeSelectionResult(
            "SEL-1",
            "GROUP",
            "STD",
            "ALT",
            BomChildType.ALTERNATIVE,
            "MANUAL_ALTERNATIVE",
            1,
            "ACTIVE",
            false,
            false,
            true,
            "IMPORT-1",
            "BUILD-1");
    when(rawMapper.selectList(any())).thenReturn(rows);
    when(resolver.resolve(any()))
        .thenReturn(
            new BomAlternativeGroupResolution(
                List.of(group), List.of()));
    when(selectionService.findCurrent(any(), any()))
        .thenReturn(current);
    when(selectionService.synchronize(any(), any()))
        .thenReturn(List.of(current));

    QuoteBomAlternativeSummaryResponse summary =
        service.getAlternativeGroups(
            "OA-QBA-09", 901L, "2026-07");

    assertThat(summary.groupCount()).isEqualTo(1);
    assertThat(summary.manualAlternativeCount()).isEqualTo(1);
    assertThat(summary.reviewRequired()).isFalse();
    assertThat(summary.groups().getFirst().parentMaterialName())
        .isEqualTo("名称-PARENT");
    assertThat(summary.groups().getFirst().selectedMaterialCode())
        .isEqualTo("ALT");
    assertThat(summary.groups().getFirst().candidates())
        .extracting(
            candidate ->
                candidate.materialCode() + ":" + candidate.selected())
        .containsExactly("STD:false", "ALT:true");
  }

  private static void stubQuoteScope(
      QuoteBomPreparationRecordMapper preparationMapper,
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper) {
    QuoteBomPreparationRecord preparation =
        new QuoteBomPreparationRecord();
    preparation.setId(90L);
    preparation.setOaFormId(9L);
    preparation.setOaFormItemId(901L);
    preparation.setOaNo("OA-QBA-09");
    preparation.setQuoteProductCode("TOP-AUTH");
    preparation.setProductType("NON_BARE");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    OaFormItem item = new OaFormItem();
    item.setId(901L);
    item.setOaFormId(9L);
    item.setBusinessUnitType("COMMERCIAL");
    OaForm form = new OaForm();
    form.setId(9L);
    form.setOaNo("OA-QBA-09");
    form.setBusinessUnitType("COMMERCIAL");
    when(preparationMapper.selectOne(any())).thenReturn(preparation);
    when(itemMapper.selectById(901L)).thenReturn(item);
    when(formMapper.selectById(9L)).thenReturn(form);
  }

  private static BomRawHierarchy row(
      Long id,
      String materialCode,
      String parentCode,
      int level,
      String path,
      String groupKey,
      String childType) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode("TOP-AUTH");
    row.setParentCode(parentCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("名称-" + materialCode);
    row.setMaterialSpec("规格-" + materialCode);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(level);
    row.setProcessSeq("010");
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setSourceImportBatchId("IMPORT-1");
    row.setBuildBatchId("BUILD-1");
    row.setAlternativeGroupKey(groupKey);
    row.setChildType(childType);
    return row;
  }

  private static BomAlternativeCandidate candidate(
      BomRawHierarchy row, BomChildType childType) {
    return new BomAlternativeCandidate(
        row.getId(),
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getMaterialSpec(),
        childType,
        row.getQtyPerParent(),
        row.getPath(),
        row.getSourceImportBatchId(),
        row.getBuildBatchId());
  }
}
