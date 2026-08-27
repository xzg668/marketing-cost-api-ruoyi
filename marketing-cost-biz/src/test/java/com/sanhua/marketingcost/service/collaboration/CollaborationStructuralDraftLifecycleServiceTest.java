package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("协作 BOM 与包装草稿审核状态")
class CollaborationStructuralDraftLifecycleServiceTest {
  private final QuoteBomSupplementVersionMapper versionMapper =
      mock(QuoteBomSupplementVersionMapper.class);
  private final QuoteBomPackageReferenceMapper packageMapper =
      mock(QuoteBomPackageReferenceMapper.class);
  private final CollaborationStructuralDraftLifecycleService service =
      new CollaborationStructuralDraftLifecycleService(versionMapper, packageMapper);
  private final CollaborationActor actor = new CollaborationActor(31L, "审核人");
  private QuoteBomSupplementVersion version;

  @BeforeEach
  void setup() {
    version = new QuoteBomSupplementVersion();
    version.setId(90L);
    version.setQuoteProductCode("P-1");
    version.setVersionStatus("DRAFT");
    version.setActiveFlag(1);
    when(versionMapper.selectById(90L)).thenReturn(version);
    when(versionMapper.updateById(any(QuoteBomSupplementVersion.class))).thenReturn(1);
    when(packageMapper.updateById(any(QuoteBomPackageReference.class))).thenReturn(1);
  }

  @Test
  void submitAndReturnStayOnSameVersion() {
    QuoteCollaborationProductTask task = task();
    service.submit(task, actor);
    assertThat(version.getVersionStatus()).isEqualTo("SUBMITTED");
    assertThat(version.getSubmittedBy()).isEqualTo(31L);
    service.returnForRevision(task, true, false, "父子关系不正确", actor);
    assertThat(version.getVersionStatus()).isEqualTo("RETURNED");
    assertThat(version.getReviewComment()).isEqualTo("父子关系不正确");
    verify(versionMapper, org.mockito.Mockito.times(2)).updateById(version);
  }

  @Test
  void approvalUsesSixCompleteNaturalMonths() {
    QuoteCollaborationProductTask task = task();
    service.approveBom(task, actor, "通过");
    assertThat(version.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(version.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(version.getReuseValidUntil()).isEqualTo(LocalDate.of(2027, 1, 31));
  }

  @Test
  void u9AvailableVoidsSupplementWithoutPublishingIt() {
    service.supersedeBomWithU9(task(), actor);
    assertThat(version.getVersionStatus()).isEqualTo("VOIDED");
    assertThat(version.getActiveFlag()).isZero();
    assertThat(version.getReuseValidUntil()).isNull();
  }

  @Test
  void packageUsesTheSameSubmitReturnApproveLifecycle() {
    QuoteCollaborationProductTask task = task();
    task.setNeedBom(0);
    task.setNeedPackage(1);
    task.setPackageReferenceId(91L);
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setId(91L);
    reference.setReferenceStatus("DRAFT");
    when(packageMapper.selectById(91L)).thenReturn(reference);
    service.submit(task, actor);
    assertThat(reference.getReferenceStatus()).isEqualTo("SUBMITTED");
    service.returnForRevision(task, false, true, "包装不正确", actor);
    assertThat(reference.getReferenceStatus()).isEqualTo("RETURNED");
    service.approvePackage(task);
    assertThat(reference.getReferenceStatus()).isEqualTo("APPROVED");
  }

  private QuoteCollaborationProductTask task() {
    QuoteCollaborationProductTask value = new QuoteCollaborationProductTask();
    value.setProductCode("P-1");
    value.setAccountingMonth("2026-08");
    value.setNeedBom(1);
    value.setNeedPackage(0);
    value.setSupplementVersionId(90L);
    return value;
  }
}
