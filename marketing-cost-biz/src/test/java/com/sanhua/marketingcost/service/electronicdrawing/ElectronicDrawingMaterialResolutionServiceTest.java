package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.service.electronicdrawing.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionRequest;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("电子图库严格自动匹配与财务人工选料")
class ElectronicDrawingMaterialResolutionServiceTest {
  @Mock private ElectronicDrawingWorkflowContextPort contextPort;
  @Mock private QuoteBomSupplementVersionMapper versionMapper;
  @Mock private ElectronicDrawingSourceNodeRepository sourceNodeRepository;
  @Mock private ElectronicDrawingMaterialMatcher matcher;
  @Mock private MaterialMasterRawMapper materialMapper;
  @Mock private ElectronicDrawingActorProvider actorProvider;

  private ElectronicDrawingMaterialResolutionService service;
  private ElectronicDrawingWorkContext context;
  private QuoteBomSupplementVersion version;
  private ElectronicDrawingActor finance;

  @BeforeEach
  void setUp() {
    service = new ElectronicDrawingMaterialResolutionService(
        contextPort, versionMapper, sourceNodeRepository,
        matcher, materialMapper, actorProvider);
    context = context(4);
    version = version();
    finance = new ElectronicDrawingActor(8801L, "报价员甲");
    authentication("COMMERCIAL");
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void automaticMatchingPersistsUniqueOnlyAndNeverChoosesAmbiguousFirst() {
    ElectronicDrawingSourceNode unique = node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    ElectronicDrawingSourceNode ambiguous = node(2L, "2", "D-2", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    ElectronicDrawingSourceNode missing = node(3L, "3", "D-3", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    ElectronicDrawingSourceNode matched = copy(unique, ElectronicDrawingSourceNode.MATCH_AUTO, "1001", "SYSTEM");
    ElectronicDrawingSourceNode ambiguity = copy(ambiguous, ElectronicDrawingSourceNode.MATCH_AMBIGUOUS, null, null);
    arrangeSystemTarget(List.of(unique, ambiguous, missing), List.of(unique, ambiguous, missing),
        List.of(matched, ambiguity, missing));
    when(matcher.match(eq("COMMERCIAL"), any())).thenReturn(List.of(
        match(unique, ElectronicDrawingMaterialMatcher.Status.AUTO_MATCHED, "1001"),
        match(ambiguous, ElectronicDrawingMaterialMatcher.Status.AMBIGUOUS, null),
        match(missing, ElectronicDrawingMaterialMatcher.Status.UNMATCHED, null)));
    when(contextPort.touch(eq(context), eq(501L), eq(0L), eq("SYSTEM"), any()))
        .thenReturn(context(5));
    when(materialMapper.selectByLatestBatchAndCodes(anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(material("1001")));

    var response = service.autoMatch(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(response.autoMatchedCount()).isOne();
    assertThat(response.ambiguousCount()).isOne();
    assertThat(response.unmatchedCount()).isOne();
    assertThat(response.complete()).isFalse();
    verify(sourceNodeRepository).updateResolution(
        eq(1L), eq(ElectronicDrawingSourceNode.MATCH_UNMATCHED),
        eq(ElectronicDrawingSourceNode.MATCH_AUTO), eq("1001"), eq("SYSTEM"), any());
    verify(sourceNodeRepository).updateResolution(
        2L, ElectronicDrawingSourceNode.MATCH_UNMATCHED, ElectronicDrawingSourceNode.MATCH_AMBIGUOUS,
        null, null, null);
    verify(sourceNodeRepository, never()).updateResolution(
        eq(3L), any(), any(), any(), any(), any());
  }

  @Test
  void completeMappingsCanRetryCompositionWithoutRewritingSelections() {
    ElectronicDrawingSourceNode selected = copy(
        node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED),
        ElectronicDrawingSourceNode.MATCH_MANUAL, "1001", "报价员甲");
    arrangeUserTarget(List.of(selected));
    when(materialMapper.selectByLatestBatchAndCodes(anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(material("1001")));

    var result = service.apply(101L,
        new ElectronicDrawingMaterialResolutionRequest(4, 501L, List.of()), "2026-08");

    assertThat(result.complete()).isTrue();
    assertThat(result.manuallySelectedCount()).isOne();
    verify(sourceNodeRepository, never()).updateResolution(any(), any(), any(), any(), any(), any());
    verify(contextPort, never()).touch(any(), any(), any(), any(), any());
  }

  @Test
  void emptySaveCannotSkipPendingMappings() {
    arrangeUserTarget(List.of(node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED)));
    assertThatThrownBy(() -> service.apply(101L,
        new ElectronicDrawingMaterialResolutionRequest(4, 501L, List.of()), "2026-08"))
        .isInstanceOf(ElectronicDrawingMaterialResolutionException.class)
        .hasMessageContaining("仍有待确认物料");
    verify(sourceNodeRepository, never()).updateResolution(any(), any(), any(), any(), any(), any());
  }

  @Test
  void threeExplicitSearchTypesUseOnlyCurrentTaskOrganization() {
    arrangeUserTarget(List.of(node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED)));
    when(materialMapper.selectElectronicDrawingOptions(
        any(), any(), eq(null), eq("COMMERCIAL"), eq(30)))
        .thenReturn(List.of(material("1001")));

    assertThat(service.search(101L, 501L, "DRAWING_NO", "D-1", null, "2026-08").options()).hasSize(1);
    assertThat(service.search(101L, 501L, "MATERIAL_CODE", "1001", null, "2026-08").options()).hasSize(1);
    assertThat(service.search(101L, 501L, "MATERIAL_NAME", "六角螺母", null, "2026-08").options()).hasSize(1);

    verify(materialMapper).selectElectronicDrawingOptions(
        "DRAWING_NO", "D-1", null, "COMMERCIAL", 30);
    verify(materialMapper).selectElectronicDrawingOptions(
        "MATERIAL_CODE", "1001", null, "COMMERCIAL", 30);
    verify(materialMapper).selectElectronicDrawingOptions(
        "MATERIAL_NAME", "六角螺母", null, "COMMERCIAL", 30);
  }

  @Test
  void blankKeywordReturnsNoCandidatesWithoutQueryingMaterialMaster() {
    arrangeUserTarget(List.of(node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED)));

    var response = service.search(101L, 501L, "DRAWING_NO", "  ", null, "2026-08");

    assertThat(response.options()).isEmpty();
    verify(materialMapper, never()).selectElectronicDrawingOptions(any(), any(), any(), any(), anyInt());
  }

  @Test
  void oneRequestCanPersistMultipleSelectionsWithActualFinanceUserAndShanghaiTime() {
    ElectronicDrawingSourceNode first = node(1L, "1", "S040A-08213", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    ElectronicDrawingSourceNode second = node(2L, "2", "M8-A2-70-钝化-GB/T6170", ElectronicDrawingSourceNode.MATCH_AMBIGUOUS);
    ElectronicDrawingSourceNode savedFirst = copy(first, ElectronicDrawingSourceNode.MATCH_MANUAL, "1001", "USER:8801:报价员甲");
    ElectronicDrawingSourceNode savedSecond = copy(second, ElectronicDrawingSourceNode.MATCH_MANUAL, "9990000053986", "USER:8801:报价员甲");
    arrangeApply(List.of(first, second), List.of(savedFirst, savedSecond));
    when(materialMapper.selectByLatestBatchAndCodes(anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(material("1001"), material("9990000053986")));
    when(sourceNodeRepository.updateResolution(any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(contextPort.touch(eq(context), eq(501L), eq(8801L), eq("报价员甲"), any()))
        .thenReturn(context(5));
    LocalDateTime before = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).minusSeconds(1);

    var response = service.apply(101L, new ElectronicDrawingMaterialResolutionRequest(
        4, 501L, List.of(
            new ElectronicDrawingMaterialResolutionRequest.Selection(1L, "1001"),
            new ElectronicDrawingMaterialResolutionRequest.Selection(2L, "9990000053986"))), "2026-08");

    LocalDateTime after = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).plusSeconds(1);
    assertThat(response.manuallySelectedCount()).isEqualTo(2);
    assertThat(response.complete()).isTrue();
    ArgumentCaptor<LocalDateTime> resolvedAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(sourceNodeRepository).updateResolution(
        eq(1L), eq(ElectronicDrawingSourceNode.MATCH_UNMATCHED),
        eq(ElectronicDrawingSourceNode.MATCH_MANUAL), eq("1001"),
        eq("USER:8801:报价员甲"), resolvedAt.capture());
    assertThat(resolvedAt.getValue()).isBetween(before, after);
    assertThat(response.items().getFirst().drawingCode()).isEqualTo("S040A-08213");
    assertThat(response.items().getFirst().resolvedMaterialCode()).isEqualTo("1001");
  }

  @Test
  void partialSaveSurvivesReturnReentryAndRefreshAsOneRemainingItem() {
    ElectronicDrawingSourceNode selected = node(1L, "1", "S040A-08213", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    ElectronicDrawingSourceNode remaining = node(2L, "2", "M8-A2-70-钝化-GB/T6170", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    ElectronicDrawingSourceNode saved = copy(selected, ElectronicDrawingSourceNode.MATCH_MANUAL, "1001", "USER:8801:报价员甲");
    arrangeApply(List.of(selected, remaining), List.of(saved, remaining));
    when(materialMapper.selectByLatestBatchAndCodes(anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(material("1001")));
    when(sourceNodeRepository.updateResolution(any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(contextPort.touch(any(), any(), any(), any(), any())).thenReturn(context(5));

    var savedResponse = service.apply(101L, new ElectronicDrawingMaterialResolutionRequest(
        4, 501L, List.of(new ElectronicDrawingMaterialResolutionRequest.Selection(1L, "1001"))), "2026-08");
    assertThat(savedResponse.complete()).isFalse();
    assertThat(savedResponse.unmatchedCount()).isOne();

    when(contextPort.loadForCurrentBusinessUnit(101L, "2026-08")).thenReturn(context(5));
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(List.of(saved, remaining));
    var reentered = service.state(101L, "2026-08");
    var refreshed = service.state(101L, "2026-08");
    assertThat(reentered.items()).usingRecursiveComparison().isEqualTo(refreshed.items());
    assertThat(refreshed.items()).filteredOn(item -> item.requiresAction()).singleElement()
        .extracting(item -> item.drawingCode()).isEqualTo("M8-A2-70-钝化-GB/T6170");
  }

  @Test
  void rejectsCrossOrganizationInactiveOrMissingMaterialBecauseCurrentOrgQueryReturnsNone() {
    ElectronicDrawingSourceNode pending = node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    arrangeUserTarget(List.of(pending));
    when(materialMapper.selectByLatestBatchAndCodes(anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.apply(101L, request(4, 501L, 1L, "CROSS-ORG"), "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingMaterialResolutionException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingMaterialResolutionException.MATERIAL_NOT_FOUND));
    verify(sourceNodeRepository, never()).updateResolution(any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectsStaleTaskVersionBeforeAnyNodeOrMaterialMutation() {
    when(actorProvider.current()).thenReturn(finance);
    when(contextPort.loadForCurrentBusinessUnit(101L, "2026-08")).thenReturn(context);

    assertThatThrownBy(() -> service.apply(101L, request(3, 501L, 1L, "1001"), "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingMaterialResolutionException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingMaterialResolutionException.TASK_VERSION_CONFLICT));
    verifyNoInteractions(versionMapper, sourceNodeRepository, materialMapper);
    verify(contextPort, never()).touch(any(), any(), any(), any(), any());
  }

  @Test
  void optimisticConflictAfterNodeUpdatesFailsTheWholeTransactionalCommand() {
    ElectronicDrawingSourceNode pending = node(1L, "1", "D-1", ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    arrangeUserTarget(List.of(pending));
    when(materialMapper.selectByLatestBatchAndCodes(anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(material("1001")));
    when(sourceNodeRepository.updateResolution(any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(contextPort.touch(any(), any(), any(), any(), any()))
        .thenThrow(new ElectronicDrawingWorkflowRetryException("conflict"));

    assertThatThrownBy(() -> service.apply(101L, request(4, 501L, 1L, "1001"), "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingMaterialResolutionException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingMaterialResolutionException.TASK_VERSION_CONFLICT));
    verify(sourceNodeRepository).updateResolution(any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectsUnauthorizedFinanceRoleBeforeReadingTask() {
    when(actorProvider.current()).thenThrow(new IllegalStateException("无报价权限"));

    assertThatThrownBy(() -> service.state(101L, "2026-08"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(contextPort, versionMapper, sourceNodeRepository, materialMapper);
  }

  @Test
  void crossBusinessUnitTaskIsNotVisibleToCurrentQuotationUser() {
    authentication("PLATE");
    when(actorProvider.current()).thenReturn(finance);
    when(contextPort.loadForCurrentBusinessUnit(101L, "2026-08"))
        .thenThrow(new IllegalArgumentException("不存在"));

    assertThatThrownBy(() -> service.state(101L, "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingMaterialResolutionException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingMaterialResolutionException.TASK_NOT_FOUND));
    verifyNoInteractions(versionMapper, sourceNodeRepository, materialMapper);
  }

  private void arrangeSystemTarget(
      List<ElectronicDrawingSourceNode> initial,
      List<ElectronicDrawingSourceNode> pending,
      List<ElectronicDrawingSourceNode> after) {
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(501L)).thenReturn(version);
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(initial, after);
    when(sourceNodeRepository.findPendingByVersionId(501L)).thenReturn(pending);
    when(sourceNodeRepository.updateResolution(any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
  }

  private void arrangeUserTarget(List<ElectronicDrawingSourceNode> nodes) {
    when(actorProvider.current()).thenReturn(finance);
    when(contextPort.loadForCurrentBusinessUnit(101L, "2026-08")).thenReturn(context);
    when(versionMapper.selectById(501L)).thenReturn(version);
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(nodes);
  }

  private void arrangeApply(
      List<ElectronicDrawingSourceNode> before, List<ElectronicDrawingSourceNode> after) {
    arrangeUserTarget(before);
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(before, after);
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context(5));
  }

  private ElectronicDrawingMaterialResolutionRequest request(
      int expectedVersion, long sourceVersionId, long nodeId, String materialCode) {
    return new ElectronicDrawingMaterialResolutionRequest(
        expectedVersion, sourceVersionId,
        List.of(new ElectronicDrawingMaterialResolutionRequest.Selection(nodeId, materialCode)));
  }

  private ElectronicDrawingMaterialMatcher.Match match(
      ElectronicDrawingSourceNode node,
      ElectronicDrawingMaterialMatcher.Status status,
      String selectedCode) {
    return new ElectronicDrawingMaterialMatcher.Match(
        node.getSourceSequence(), node.getSourceRowNo(), node.getDrawingCode(),
        node.getSourceName(), status, selectedCode, List.of());
  }

  private ElectronicDrawingWorkContext context(int revision) {
    return new ElectronicDrawingWorkContext(
        101L, revision, 301L, 501L, 100L, 201L, "QCPT-101", "OA-101",
        "1053100052030", null, "产品", null, null, null, "FULL_BOM", "2026-08",
        "210", "COMMERCIAL", "COMMERCIAL", "210", true, true,
        "BOM_IN_PROGRESS", ElectronicDrawingWorkflowStage.MAPPING_PENDING,
        8801L, "报价员甲", null);
  }

  private QuoteBomSupplementVersion version() {
    QuoteBomSupplementVersion value = new QuoteBomSupplementVersion();
    value.setId(501L);
    value.setPreparationId(301L);
    value.setTaskNo("QCPT-101");
    value.setQuoteProductCode("1053100052030");
    value.setPeriodMonth("2026-08");
    value.setMaterialOrgCode("COMMERCIAL");
    value.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    value.setElectronicDrawingNo("J40AH-40HY-03");
    value.setVersionNo(1);
    value.setVersionStatus("DRAFT");
    value.setActiveFlag(1);
    return value;
  }

  private ElectronicDrawingSourceNode node(Long id, String sequence, String drawing, String status) {
    ElectronicDrawingSourceNode value = new ElectronicDrawingSourceNode();
    value.setId(id);
    value.setSupplementVersionId(501L);
    value.setSourceRowNo(id.intValue() + 1);
    value.setSourceSequence(sequence);
    value.setDrawingCode(drawing);
    value.setSourceName("物料" + sequence);
    value.setQty(BigDecimal.ONE);
    value.setMatchStatus(status);
    return value;
  }

  private ElectronicDrawingSourceNode copy(
      ElectronicDrawingSourceNode source, String status, String materialCode, String resolvedBy) {
    ElectronicDrawingSourceNode value = node(
        source.getId(), source.getSourceSequence(), source.getDrawingCode(), status);
    value.setResolvedMaterialCode(materialCode);
    value.setResolvedBy(resolvedBy);
    if (materialCode != null) value.setResolvedAt(LocalDateTime.of(2026, 8, 30, 9, 0));
    return value;
  }

  private MaterialMasterRaw material(String code) {
    MaterialMasterRaw value = new MaterialMasterRaw();
    value.setMaterialCode(code);
    value.setOrganizationCode("COMMERCIAL");
    value.setMaterialName("名称" + code);
    value.setMaterialSpec("规格" + code);
    value.setMaterialModel("型号" + code);
    value.setDrawingNo("图号" + code);
    value.setShapeAttr("采购件");
    value.setUnit("件");
    value.setActiveFlag(1);
    return value;
  }

  private void authentication(String businessUnit) {
    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
        "报价员甲", "N/A", List.of(new SimpleGrantedAuthority("ROLE_BU_STAFF")));
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnit));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
