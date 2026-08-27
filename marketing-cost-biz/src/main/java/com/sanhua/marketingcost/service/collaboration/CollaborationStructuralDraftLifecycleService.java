package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 协作任务结构草稿的唯一状态入口。BOM/包装继续使用原表，仅补齐提交、退回和审核生效状态，
 * 避免产品任务已结束而其来源对象仍停留在 DRAFT。
 */
@Service
public class CollaborationStructuralDraftLifecycleService {
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final QuoteBomPackageReferenceMapper packageMapper;

  public CollaborationStructuralDraftLifecycleService(
      QuoteBomSupplementVersionMapper versionMapper,
      QuoteBomPackageReferenceMapper packageMapper) {
    this.versionMapper = versionMapper;
    this.packageMapper = packageMapper;
  }

  @Transactional
  public void submit(QuoteCollaborationProductTask task, CollaborationActor actor) {
    if (task == null) throw invalid("产品任务不存在");
    LocalDateTime now = LocalDateTime.now();
    if (enabled(task.getNeedBom())) {
      QuoteBomSupplementVersion version = version(task);
      version.setVersionStatus("SUBMITTED");
      version.setSubmittedBy(actor.userId());
      version.setSubmittedByName(actor.userName());
      version.setSubmittedAt(now);
      version.setUpdatedAt(now);
      update(version);
    }
    if (enabled(task.getNeedPackage())) {
      QuoteBomPackageReference reference = packageReference(task);
      reference.setReferenceStatus("SUBMITTED");
      reference.setUpdatedAt(now);
      update(reference);
    }
  }

  @Transactional
  public void returnForRevision(
      QuoteCollaborationProductTask task,
      boolean returnBom,
      boolean returnPackage,
      String reason,
      CollaborationActor actor) {
    LocalDateTime now = LocalDateTime.now();
    if (returnBom) {
      QuoteBomSupplementVersion version = version(task);
      version.setVersionStatus("RETURNED");
      version.setReviewerUserId(actor.userId());
      version.setReviewerName(actor.userName());
      version.setReviewedAt(now);
      version.setReviewComment(trim(reason));
      version.setUpdatedAt(now);
      update(version);
    }
    if (returnPackage) {
      QuoteBomPackageReference reference = packageReference(task);
      reference.setReferenceStatus("RETURNED");
      reference.setRemark(trim(reason));
      reference.setUpdatedAt(now);
      update(reference);
    }
  }

  @Transactional
  public void approveBom(
      QuoteCollaborationProductTask task, CollaborationActor actor, String comment) {
    QuoteBomSupplementVersion version = version(task);
    LocalDateTime now = LocalDateTime.now();
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    version.setReviewerUserId(actor.userId());
    version.setReviewerName(actor.userName());
    version.setReviewedAt(now);
    version.setReviewComment(trim(comment));
    version.setReuseValidUntil(reuseValidUntil(task));
    version.setEffectiveFrom(YearMonth.parse(task.getAccountingMonth()).atDay(1));
    version.setEffectiveTo(null);
    version.setUpdatedAt(now);
    update(version);
  }

  /** 审核时 U9 已经有正式 BOM：保留审计记录，但该补录版本不发布、不进入半年复用。 */
  @Transactional
  public void supersedeBomWithU9(
      QuoteCollaborationProductTask task, CollaborationActor actor) {
    QuoteBomSupplementVersion version = version(task);
    LocalDateTime now = LocalDateTime.now();
    version.setVersionStatus("VOIDED");
    version.setActiveFlag(0);
    version.setReviewerUserId(actor.userId());
    version.setReviewerName(actor.userName());
    version.setReviewedAt(now);
    version.setReviewComment("审核生效前 U9 已有正式 BOM，本次补录不发布");
    version.setReuseValidUntil(null);
    version.setUpdatedAt(now);
    update(version);
  }

  @Transactional
  public void approvePackage(QuoteCollaborationProductTask task) {
    QuoteBomPackageReference reference = packageReference(task);
    reference.setReferenceStatus("APPROVED");
    reference.setActiveFlag(1);
    reference.setUpdatedAt(LocalDateTime.now());
    update(reference);
  }

  private QuoteBomSupplementVersion version(QuoteCollaborationProductTask task) {
    if (task == null || task.getSupplementVersionId() == null) {
      throw invalid("产品任务缺少 BOM 补录版本");
    }
    QuoteBomSupplementVersion version = versionMapper.selectById(task.getSupplementVersionId());
    if (version == null) throw invalid("BOM 补录版本不存在");
    if (!same(task.getProductCode(), version.getQuoteProductCode())) {
      throw invalid("BOM 补录版本与当前产品不一致");
    }
    return version;
  }

  private QuoteBomPackageReference packageReference(QuoteCollaborationProductTask task) {
    if (task == null || task.getPackageReferenceId() == null) {
      throw invalid("产品任务缺少包装方案");
    }
    QuoteBomPackageReference reference = packageMapper.selectById(task.getPackageReferenceId());
    if (reference == null) throw invalid("包装方案不存在");
    return reference;
  }

  private void update(QuoteBomSupplementVersion version) {
    if (versionMapper.updateById(version) != 1) throw invalid("BOM 补录版本状态更新失败");
  }

  private void update(QuoteBomPackageReference reference) {
    if (packageMapper.updateById(reference) != 1) throw invalid("包装方案状态更新失败");
  }

  private LocalDate reuseValidUntil(QuoteCollaborationProductTask task) {
    if (!StringUtils.hasText(task.getAccountingMonth())) throw invalid("核算月份不能为空");
    return YearMonth.parse(task.getAccountingMonth().trim()).atDay(1).plusMonths(6).minusDays(1);
  }

  private static boolean enabled(Integer value) {
    return value != null && value == 1;
  }

  private static boolean same(String left, String right) {
    return StringUtils.hasText(left) && StringUtils.hasText(right)
        && left.trim().equals(right.trim());
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }
}
