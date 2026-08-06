package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPruner;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeAuditService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeMonthlyInheritanceResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeMonthlyInheritanceService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionRepository;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBomAlternativeMonthlyApplicationTest {

  private BomRawHierarchyMapper rawMapper;
  private QuoteBomAlternativeRebuildService rebuildService;
  private QuoteBomAlternativeMonthlyInheritanceService inheritanceService;
  private QuoteBomAlternativeApplicationServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuoteBomPreparationRecord.class);
    TableInfoHelper.initTableInfo(assistant, BomRawHierarchy.class);
  }

  @BeforeEach
  void setUp() {
    rawMapper = mock(BomRawHierarchyMapper.class);
    rebuildService = mock(QuoteBomAlternativeRebuildService.class);
    inheritanceService = mock(QuoteBomAlternativeMonthlyInheritanceService.class);
    QuoteBomPreparationRecordMapper preparationMapper =
        mock(QuoteBomPreparationRecordMapper.class);
    OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
    OaFormMapper formMapper = mock(OaFormMapper.class);
    service =
        new QuoteBomAlternativeApplicationServiceImpl(
            rawMapper,
            mock(BomAlternativeGroupResolver.class),
            mock(BomAlternativeBranchPruner.class),
            mock(QuoteBomAlternativeSelectionService.class),
            mock(QuoteBomAlternativeSelectionRepository.class),
            rebuildService,
            mock(QuoteBomAlternativeAuditService.class),
            preparationMapper,
            itemMapper,
            formMapper,
            new QuoteBomContextResolver(),
            inheritanceService,
            new ObjectMapper());

    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setId(10L);
    preparation.setOaFormId(1L);
    preparation.setOaFormItemId(200L);
    preparation.setOaNo("OA-NEW");
    preparation.setQuoteProductCode("P");
    preparation.setProductType("NON_BARE");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    OaFormItem item = new OaFormItem();
    item.setId(200L);
    item.setOaFormId(1L);
    item.setBusinessUnitType("COMMERCIAL");
    item.setPackageMethod("BOX");
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-NEW");
    form.setCustomer("CUSTOMER-A");
    form.setBusinessUnitType("COMMERCIAL");
    when(preparationMapper.selectOne(any())).thenReturn(preparation);
    when(itemMapper.selectById(200L)).thenReturn(item);
    when(formMapper.selectById(1L)).thenReturn(form);
  }

  @Test
  void queryFrozenSelectionUsesStoredEvidenceWithoutReadingLiveU9Bom() {
    QuoteBomAlternativeSelection inherited = inheritedSelection();
    when(inheritanceService.inheritIfFrozen(any(), any()))
        .thenReturn(
            new QuoteBomAlternativeMonthlyInheritanceResult(
                true, true, 10L, "EFFECTIVE-1", List.of(inherited)));

    QuoteBomAlternativeSummaryResponse response =
        service.getAlternativeGroups("OA-NEW", 200L, "2026-08");

    assertThat(response.groupCount()).isEqualTo(1);
    assertThat(response.manualAlternativeCount()).isEqualTo(1);
    assertThat(response.reviewRequired()).isFalse();
    assertThat(response.groups().getFirst().selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY);
    assertThat(response.groups().getFirst().selectedMaterialCode()).isEqualTo("T");
    assertThat(response.groups().getFirst().candidates())
        .extracting(candidate -> candidate.materialCode() + ":" + candidate.selected())
        .containsExactly("S:false", "T:true");
    verify(rawMapper, never()).selectList(any());
  }

  @Test
  void saveEndpointReleasesAndOverwritesProvisionalBomBeforeStepTwoConfirmation() {
    when(inheritanceService.inheritIfFrozen(any(), any()))
        .thenReturn(
            new QuoteBomAlternativeMonthlyInheritanceResult(
                true, false, 10L, "EFFECTIVE-1", List.of(inheritedSelection())));
    when(inheritanceService.releaseProvisional(any(), any())).thenReturn(true);
    QuoteBomAlternativeSelectionResult selection =
        new QuoteBomAlternativeSelectionResult(
            "SEL-2",
            "GROUP-1",
            "S",
            "S",
            BomChildType.STANDARD,
            QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD,
            2,
            QuoteBomAlternativeSelection.STATUS_ACTIVE,
            false,
            false,
            true,
            "IMPORT-1",
            "RAW-1");
    when(rebuildService.rebuild(any()))
        .thenReturn(
            new QuoteBomAlternativeRebuildResult(
                selection, false, true, false, 12, 10, "RAW-2", 1, 1, 0));

    var response =
        service.saveSelection(
            "OA-NEW",
            200L,
            "GROUP-1",
            new QuoteBomAlternativeSelectionRequest(
                "2026-08", "S", 1, "RAW-1", false, "改回标准"),
            "finance");

    assertThat(response.selectedMaterialCode()).isEqualTo("S");
    assertThat(response.rebuilt()).isTrue();
    verify(inheritanceService).releaseProvisional(any(), any());
    verify(rebuildService).rebuild(any());
    verify(rawMapper, never()).selectList(any());
  }

  private static QuoteBomAlternativeSelection inheritedSelection() {
    QuoteBomAlternativeSelection row = new QuoteBomAlternativeSelection();
    row.setId(20L);
    row.setSelectionNo("SEL-INHERITED");
    row.setOaNo("OA-NEW");
    row.setOaFormItemId(200L);
    row.setTopProductCode("P");
    row.setPeriodMonth("2026-08");
    row.setPriceOrgCode("210");
    row.setAlternativeGroupKey("GROUP-1");
    row.setParentMaterialCode("PARENT");
    row.setParentMaterialName("父件");
    row.setParentPath("/P/PARENT/");
    row.setChildSeq(10);
    row.setProcessSeq("010");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setStandardMaterialCode("S");
    row.setSelectedMaterialCode("T");
    row.setSelectedChildType("ALTERNATIVE");
    row.setSelectionSource(QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY);
    row.setSelectionVersion(1);
    row.setSelectionStatus(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    row.setCurrentSlot(1);
    row.setCandidateSnapshotJson(
        "{\"candidates\":["
            + "{\"materialCode\":\"S\",\"materialName\":\"标准\",\"childType\":\"STANDARD\",\"qtyPerParent\":1},"
            + "{\"materialCode\":\"T\",\"materialName\":\"替代\",\"childType\":\"ALTERNATIVE\",\"qtyPerParent\":1}]}" );
    row.setSourceImportBatchId("IMPORT-1");
    row.setSourceBuildBatchId("RAW-1");
    row.setInheritedMonthlySnapshotId(10L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setSelectedAt(LocalDateTime.of(2026, 8, 4, 10, 0));
    return row;
  }
}
