package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.config.ApprovedResultReuseProperties;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.service.collaboration.scan.ApprovedResultSourceSnapshot;
import com.sanhua.marketingcost.service.collaboration.scan.ApprovedResultSourceSnapshotReader;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-07 审核生效结果生成与失效")
class QuoteCollaborationApprovedResultServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), ZONE);
  private QuoteCollaborationTaskRepository taskRepository;
  private QuoteCollaborationReviewRepository reviewRepository;
  private ApprovedResultSourceSnapshotReader sourceReader;
  private QuoteCollaborationCurrentU9BomGateway u9Gateway;
  private QuoteCollaborationApprovedResultService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(QuoteCollaborationTaskRepository.class);
    reviewRepository = mock(QuoteCollaborationReviewRepository.class);
    sourceReader = mock(ApprovedResultSourceSnapshotReader.class);
    u9Gateway = mock(QuoteCollaborationCurrentU9BomGateway.class);
    ApprovedResultReuseProperties properties = new ApprovedResultReuseProperties();
    properties.setValidityMonths(6);
    properties.setPolicyCode("COLLAB_RESULT_SIX_MONTHS_V1");
    service = new QuoteCollaborationApprovedResultService(
        taskRepository, reviewRepository, sourceReader, u9Gateway,
        new ApprovedResultFingerprints(), properties, CLOCK);
  }

  @Test
  @DisplayName("财务审核生效后生成FULL_BOM结果并将六个月规则固化为半开区间")
  void activatesFullBomForExactlySixMonths() {
    QuoteCollaborationProductTask task = fullBomTask();
    QuoteCollaborationReview review = effectiveReview();
    when(taskRepository.findProductTaskById(10L, scope())).thenReturn(Optional.of(task));
    when(reviewRepository.findReviewById(20L, "COMMERCIAL")).thenReturn(Optional.of(review));
    when(reviewRepository.findResultBySource(10L, 20L, "FULL_BOM", scope()))
        .thenReturn(Optional.empty());
    when(sourceReader.readFullBom(30L, "P-1"))
        .thenReturn(ApprovedResultSourceSnapshot.ready(
            "SUPPLEMENT_VERSION", "ELECTRONIC_DRAWING", "V3", 8, "a".repeat(64)));
    when(reviewRepository.saveApprovedResult(any())).thenAnswer(invocation -> {
      QuoteCollaborationApprovedResult value = invocation.getArgument(0);
      value.setId(40L);
      value.setResultNo("QCAR-40");
      return value;
    });

    QuoteCollaborationApprovedResult result = service.activate(
        command());

    assertThat(result.getResultType()).isEqualTo("FULL_BOM");
    assertThat(result.getValidFrom()).isEqualTo(LocalDateTime.of(2026, 8, 12, 10, 30));
    assertThat(result.getValidUntil()).isEqualTo(LocalDateTime.of(2027, 2, 12, 10, 30));
    assertThat(result.getValidityMonths()).isEqualTo(6);
    assertThat(result.getValidityPolicyCode()).isEqualTo("COLLAB_RESULT_SIX_MONTHS_V1");
    assertThat(result.getStructureFingerprint()).isEqualTo("a".repeat(64));
    assertThat(result.getU9ContextFingerprint()).isNull();
  }

  @Test
  @DisplayName("裸品包装结果从当前U9本体生成上下文指纹，不信任历史报价价格")
  void activatesBarePackageWithCurrentU9Context() {
    QuoteCollaborationProductTask task = barePackageTask();
    QuoteCollaborationReview review = effectiveReview();
    QuoteCollaborationQuoteLink owner = ownerLink();
    when(taskRepository.findProductTaskById(10L, scope())).thenReturn(Optional.of(task));
    when(taskRepository.findLinksByProductTask(10L, scope())).thenReturn(List.of(owner));
    when(reviewRepository.findReviewById(20L, "COMMERCIAL")).thenReturn(Optional.of(review));
    when(reviewRepository.findResultBySource(10L, 20L, "BARE_PACKAGE", scope()))
        .thenReturn(Optional.empty());
    when(sourceReader.readBarePackage(31L, "P-1", "210", "COMMERCIAL"))
        .thenReturn(ApprovedResultSourceSnapshot.ready(
            "PACKAGE_REFERENCE", "QUOTE_PACKAGE", "PKG-V2", 3, "b".repeat(64)));
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.available(
        "U9", "BODY-V6", null, 18, "c".repeat(64)));
    when(reviewRepository.saveApprovedResult(any())).thenAnswer(invocation -> invocation.getArgument(0));

    QuoteCollaborationApprovedResult result = service.activate(
        command());

    assertThat(result.getResultType()).isEqualTo("BARE_PACKAGE");
    assertThat(result.getSourceObjectId()).isEqualTo(31L);
    assertThat(result.getU9ContextFingerprint()).hasSize(64);
    verify(u9Gateway).read(any());
  }

  @Test
  @DisplayName("同一审核和产品重复生效只返回原结果，不重复生成")
  void activationIsIdempotent() {
    QuoteCollaborationProductTask task = fullBomTask();
    QuoteCollaborationApprovedResult existing = new QuoteCollaborationApprovedResult();
    existing.setId(40L);
    existing.setResultStatus("ACTIVE");
    when(taskRepository.findProductTaskById(10L, scope())).thenReturn(Optional.of(task));
    when(reviewRepository.findReviewById(20L, "COMMERCIAL"))
        .thenReturn(Optional.of(effectiveReview()));
    when(reviewRepository.findResultBySource(10L, 20L, "FULL_BOM", scope()))
        .thenReturn(Optional.of(existing));

    assertThat(service.activate(command()))
        .isSameAs(existing);
    verify(sourceReader, never()).readFullBom(any(), any());
    verify(reviewRepository, never()).saveApprovedResult(any());
  }

  @Test
  @DisplayName("审核未生效不得生成可复用结果")
  void rejectsReviewThatIsNotEffective() {
    QuoteCollaborationReview review = effectiveReview();
    review.setReviewStatus("APPROVED");
    when(taskRepository.findProductTaskById(10L, scope()))
        .thenReturn(Optional.of(fullBomTask()));
    when(reviewRepository.findReviewById(20L, "COMMERCIAL")).thenReturn(Optional.of(review));

    assertThatThrownBy(() -> service.activate(
        command()))
        .isInstanceOf(CollaborationDomainException.class)
        .hasMessageContaining("审核尚未生效");
  }

  @Test
  @DisplayName("手工失效必须记录原因且重复操作幂等")
  void invalidatesActiveResultWithReason() {
    QuoteCollaborationApprovedResult active = new QuoteCollaborationApprovedResult();
    active.setId(40L);
    active.setResultStatus("ACTIVE");
    when(reviewRepository.findApprovedResultById(40L, scope())).thenReturn(Optional.of(active));
    QuoteCollaborationApprovedResult invalid = new QuoteCollaborationApprovedResult();
    invalid.setId(40L);
    invalid.setResultStatus("INVALIDATED");
    invalid.setInvalidReason("电子图库版本人工撤销");
    when(reviewRepository.invalidateApprovedResult(
        eq(40L), eq("ACTIVE"), eq("电子图库版本人工撤销"), eq(scope()), eq(actor()), any()))
        .thenReturn(invalid);

    QuoteCollaborationApprovedResult result = service.invalidate(
        40L, "电子图库版本人工撤销", scope(), actor());

    assertThat(result.getResultStatus()).isEqualTo("INVALIDATED");
    assertThat(result.getInvalidReason()).isEqualTo("电子图库版本人工撤销");
  }

  private QuoteCollaborationProductTask fullBomTask() {
    QuoteCollaborationProductTask task = baseTask();
    task.setProductForm("NORMAL");
    task.setPrimaryScope("FULL_BOM");
    task.setSupplementVersionId(30L);
    return task;
  }

  private QuoteCollaborationProductTask barePackageTask() {
    QuoteCollaborationProductTask task = baseTask();
    task.setProductForm("BARE");
    task.setPrimaryScope("BARE_PACKAGE");
    task.setPackageReferenceId(31L);
    return task;
  }

  private QuoteCollaborationProductTask baseTask() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(10L);
    task.setOriginCollaborationId(5L);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setPriceOrgCode("210");
    task.setMaterialOrgCode("COMMERCIAL");
    task.setAccountingMonth("2026-08");
    task.setProductCode("P-1");
    task.setProductName("热力膨胀阀");
    task.setProductSpec("规格");
    task.setProductModel("型号");
    task.setTaskStatus("APPROVED_PUBLISHING");
    return task;
  }

  private QuoteCollaborationReview effectiveReview() {
    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setId(20L);
    review.setCollaborationId(5L);
    review.setReviewStatus("EFFECTIVE");
    review.setEffectiveAt(LocalDateTime.of(2026, 8, 12, 10, 30));
    return review;
  }

  private QuoteCollaborationQuoteLink ownerLink() {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setProductTaskId(10L);
    link.setOaFormId(100L);
    link.setOaFormItemId(101L);
    link.setOaNo("OA-1");
    link.setLinkType("OWNER");
    return link;
  }

  private CollaborationScope scope() {
    return new CollaborationScope("COMMERCIAL", "210");
  }

  private CollaborationActor actor() {
    return new CollaborationActor(701L, "财务审核员");
  }

  private ApprovedResultActivationCommand command() {
    return new ApprovedResultActivationCommand(
        10L, 20L, "COMMERCIAL", "210", actor());
  }
}
