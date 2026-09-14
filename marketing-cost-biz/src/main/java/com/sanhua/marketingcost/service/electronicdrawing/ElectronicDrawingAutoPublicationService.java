package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.PublicationContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 已完成料号解析的电子图库混合 BOM 由系统直接发布，不产生技术提交或财务审核页面。
 * 版本生效、原始层快照、缺口关闭、产品和报价关联放行处于同一事务，任一步失败整体回滚。
 */
@Service
public class ElectronicDrawingAutoPublicationService {

  private final ElectronicDrawingWorkflowContextPort contextPort;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final ElectronicDrawingSourceNodeRepository sourceNodeRepository;
  private final ApprovedElectronicBomRawSnapshotPublisher rawSnapshotPublisher;

  public ElectronicDrawingAutoPublicationService(
      ElectronicDrawingWorkflowContextPort contextPort,
      QuoteBomSupplementVersionMapper versionMapper,
      ElectronicDrawingSourceNodeRepository sourceNodeRepository,
      ApprovedElectronicBomRawSnapshotPublisher rawSnapshotPublisher) {
    this.contextPort = contextPort;
    this.versionMapper = versionMapper;
    this.sourceNodeRepository = sourceNodeRepository;
    this.rawSnapshotPublisher = rawSnapshotPublisher;
  }

  @Transactional(rollbackFor = Exception.class)
  public PublicationResult publish(Long workflowId, String businessUnitType, String orgCode) {
    ElectronicDrawingWorkContext context = contextPort.load(workflowId, businessUnitType, orgCode);
    validateIdentity(context);
    QuoteBomSupplementVersion version = requireVersion(context);
    ensureNoPendingMappings(version.getId());

    if (context.published()) {
      requireAlreadyPublished(version, context);
      String batchId = rawSnapshotPublisher.publish(publicationContext(context));
      ElectronicDrawingWorkContext refreshed = contextPort.completePublication(
          context, version.getCompositionFingerprint(),
          LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
      return new PublicationResult(refreshed, batchId, true);
    }
    if (!ElectronicDrawingWorkflowStage.COMPOSED.equals(context.workflowStage())) {
      throw new IllegalStateException("电子图库混合 BOM 尚未合成，不能自动发布");
    }
    String fingerprint = required(version.getCompositionFingerprint(), "混合 BOM 指纹");

    if (!"APPROVED".equals(version.getVersionStatus())) {
      if (!"DRAFT".equals(version.getVersionStatus())) {
        throw new IllegalStateException("电子图库源版本当前状态不能自动发布："
            + version.getVersionStatus());
      }
      approve(version, context);
    }
    String batchId = rawSnapshotPublisher.publish(publicationContext(context));
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    ElectronicDrawingWorkContext published = contextPort.completePublication(
        context, fingerprint, now);
    return new PublicationResult(published, batchId, false);
  }

  public boolean isPublished(ElectronicDrawingWorkContext context) {
    return context != null && context.published();
  }

  private QuoteBomSupplementVersion requireVersion(ElectronicDrawingWorkContext context) {
    if (context.sourceVersionId() == null) {
      throw new IllegalStateException("电子图库报价产品缺少源版本");
    }
    QuoteBomSupplementVersion version = versionMapper.selectById(context.sourceVersionId());
    boolean valid = version != null
        && Objects.equals(version.getId(), context.sourceVersionId())
        && Objects.equals(version.getPreparationId(), context.preparationId())
        && Objects.equals(version.getQuoteProductCode(), context.quoteProductCode())
        && Objects.equals(version.getPeriodMonth(), context.accountingMonth())
        && Objects.equals(version.getMaterialOrgCode(), context.materialOrgCode())
        && "ELECTRONIC_DRAWING_EXCEL".equals(version.getBomSource())
        && Objects.equals(version.getActiveFlag(), 1);
    if (!valid) throw new IllegalStateException("电子图库源版本与产品任务不一致");
    return version;
  }

  private void requireAlreadyPublished(
      QuoteBomSupplementVersion version, ElectronicDrawingWorkContext context) {
    if (!"APPROVED".equals(version.getVersionStatus())
        || !Objects.equals(version.getCompositionFingerprint(), context.compositionFingerprint())) {
      throw new IllegalStateException("电子图库产品任务与已发布源版本不一致");
    }
  }

  private void ensureNoPendingMappings(Long versionId) {
    if (!sourceNodeRepository.findPendingByVersionId(versionId).isEmpty()) {
      throw new IllegalStateException("电子图库仍有物料未选择 U9 料号，不能自动发布");
    }
  }

  private void validateIdentity(ElectronicDrawingWorkContext context) {
    if (context == null || !"FULL_BOM".equals(context.primaryScope())
        || !context.bomRequired()) {
      throw new IllegalArgumentException("当前报价产品不是电子图库完整 BOM 对象");
    }
  }

  private void approve(
      QuoteBomSupplementVersion version, ElectronicDrawingWorkContext context) {
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    LocalDate reuseValidUntil = YearMonth.parse(context.accountingMonth())
        .atDay(1).plusMonths(6).minusDays(1);
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    version.setReviewerUserId(0L);
    version.setReviewerName("系统");
    version.setReviewedAt(now);
    version.setReviewComment("电子图库混合 BOM 自动校验发布");
    version.setReuseValidUntil(reuseValidUntil);
    version.setEffectiveFrom(YearMonth.parse(context.accountingMonth()).atDay(1));
    version.setEffectiveTo(null);
    version.setUpdatedAt(now);
    if (versionMapper.updateById(version) != 1) {
      throw new IllegalStateException("电子图库 BOM 版本自动生效失败");
    }
  }

  private PublicationContext publicationContext(ElectronicDrawingWorkContext context) {
    return new PublicationContext(
        context.sourceVersionId(), context.quoteProductCode(), context.temporaryProductKey(),
        context.priceOrgCode(), context.businessUnitType(), context.accountingMonth());
  }

  private String required(String value, String label) {
    if (!StringUtils.hasText(value)) throw new IllegalStateException(label + "不能为空");
    return value.trim();
  }

  public record PublicationResult(
      ElectronicDrawingWorkContext context, String rawBatchId, boolean idempotent) {}
}
