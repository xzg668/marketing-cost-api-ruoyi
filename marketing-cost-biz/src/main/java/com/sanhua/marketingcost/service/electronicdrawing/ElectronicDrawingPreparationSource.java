package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.PublicationContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataDrawingSnapshot;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** U9 明确无 BOM 时，提供本报价组树草稿或原产品已提交资料的报价范围内快照。 */
@Service
public class ElectronicDrawingPreparationSource {
  private static final String PREFIX = "ED_DRAFT:";
  private final TechnicalDataCostingSources technicalSources;
  private final TechnicalDataVersionContentCodec content;
  private final OaMessageCodec json;
  private final ElectronicDrawingSourceNodeRepository sourceNodes;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final QuoteBomSupplementVersionMapper versions;
  private final QuoteBomMonthlySnapshotMapper snapshots;
  private final ApprovedElectronicBomRawSnapshotPublisher converter;
  private final DrawingVerificationService verifications;

  public ElectronicDrawingPreparationSource(
      ElectronicDrawingWorkflowContextPort contexts,
      QuoteBomSupplementVersionMapper versions,
      QuoteBomMonthlySnapshotMapper snapshots,
      ApprovedElectronicBomRawSnapshotPublisher converter,
      TechnicalDataCostingSources technicalSources,
      TechnicalDataVersionContentCodec content,
      OaMessageCodec json,
      ElectronicDrawingSourceNodeRepository sourceNodes,
      DrawingVerificationService verifications) {
    this.verifications = verifications;
    this.technicalSources = technicalSources;
    this.content = content;
    this.json = json;
    this.sourceNodes = sourceNodes;
    this.contexts = contexts;
    this.versions = versions;
    this.snapshots = snapshots;
    this.converter = converter;
  }

  /** 已有月度快照可直接查看；主动核算产生的新核验未完成时，禁止退回旧快照。 */
  public String currentBlockReason(Long itemId, String month, String businessUnit, String org) {
    var context = contexts.load(itemId, businessUnit, org, month);
    var current = verifications.current(context).orElse(null);
    // 此方法仅在调用方已经找到适用月度快照时使用。跨月/跨报价读取不要求重复取图；
    // 主动核算入口仍先发起最新明细核验，有任务后严格检查其结果和组树状态。
    if (current == null) return null;
    if (!"RECEIVED".equals(current.getTaskState()))
      return "WAIT_REVIEW".equals(current.getTaskState()) ? "最新图库明细待确认，请先完成图库核对"
          : "本次图库核验尚未完成，请查看取图结果后继续";
    var source = context.sourceVersionId() == null ? null : versions.selectById(context.sourceVersionId());
    if (source == null || !Objects.equals(source.getDrawingResultId(), current.getCurrentResultId())
        || !Objects.equals(current.getConsumedResultId(), current.getCurrentResultId()))
      return "最新图库明细已接收，等待处理明细变化及对应补录资料";
    if (!context.published() && !ElectronicDrawingWorkflowStage.COMPOSED.equals(context.workflowStage()))
      return "最新图库明细尚未形成完整 BOM，请处理变化节点的料号或补录资料";
    return null;
  }

  public QuoteBomMonthlySnapshot snapshot(
      Long itemId,
      String month,
      String businessUnit,
      String org,
      String customer,
      String packageMethod) {
    var context = contexts.load(itemId, businessUnit, org, month);
    if (!ElectronicDrawingWorkflowStage.COMPOSED.equals(context.workflowStage())
        || context.published()) return null;
    String batch = batch(context);
    var existing =
        snapshots.selectList(
            Wrappers.<QuoteBomMonthlySnapshot>lambdaQuery()
                .eq(QuoteBomMonthlySnapshot::getSourceOaFormItemId, itemId)
                .eq(QuoteBomMonthlySnapshot::getCostPeriodMonth, month)
                .eq(QuoteBomMonthlySnapshot::getBomBatchId, batch)
                .eq(QuoteBomMonthlySnapshot::getSyncStatus, "DRAFT"));
    if (existing.size() > 1) throw new IllegalStateException("当前报价图库准备记录重复");
    if (!existing.isEmpty()) return existing.getFirst();
    var result = new QuoteBomMonthlySnapshot();
    result.setProductCode(context.quoteProductCode());
    result.setPriceOrgCode(org);
    result.setBusinessUnitType(businessUnit);
    result.setMaterialOrganizationCode(context.materialOrgCode());
    result.setCustomerCode(customer);
    result.setPackageMethod(packageMethod);
    result.setCostPeriodMonth(month);
    result.setBomSource(versions.selectById(context.sourceVersionId()).getBomSource());
    result.setBomPurpose("主制造");
    result.setBomVersion("ED-" + context.sourceVersionId());
    result.setSyncType("TECH_PREPARATION");
    result.setSyncStatus("DRAFT");
    result.setSyncAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    result.setSyncBy("系统");
    result.setSourceOaNo(context.oaNo());
    result.setSourceOaFormItemId(itemId);
    result.setBomBatchId(batch);
    result.setStructureFingerprint(
        versions.selectById(context.sourceVersionId()).getCompositionFingerprint());
    result.setLineCount(preview(context, batch).size());
    result.setActiveFlag(1);
    snapshots.insert(result);
    return result;
  }

  public List<BomRawHierarchy> rows(QuoteBomMonthlySnapshot snapshot) {
    if (snapshot.getBomBatchId().startsWith("ED_SHARED:")) {
      var shared =
          technicalSources.sharedDrawing(
              snapshot.getSourceOaFormItemId(), snapshot.getCostPeriodMonth());
      if (shared == null) throw new IllegalStateException("原批准图库来源已变化，请重新检查");
      var original =
          sharedContext(shared, snapshot.getBusinessUnitType(), snapshot.getPriceOrgCode());
      String batch = sharedBatch(shared, original);
      if (!Objects.equals(batch, snapshot.getBomBatchId())
          || !Objects.equals(
              versions.selectById(original.sourceVersionId()).getCompositionFingerprint(),
              snapshot.getStructureFingerprint())) {
        throw new IllegalStateException("原批准图库组树已变化，请重新检查");
      }
      return converter.preview(
          new PublicationContext(
              original.sourceVersionId(),
              snapshot.getProductCode(),
              null,
              snapshot.getPriceOrgCode(),
              snapshot.getBusinessUnitType(),
              snapshot.getCostPeriodMonth()),
          batch);
    }
    var context =
        contexts.load(
            snapshot.getSourceOaFormItemId(),
            snapshot.getBusinessUnitType(),
            snapshot.getPriceOrgCode(),
            snapshot.getCostPeriodMonth());
    if (!ElectronicDrawingWorkflowStage.COMPOSED.equals(context.workflowStage())
        || !Objects.equals(batch(context), snapshot.getBomBatchId())
        || !Objects.equals(
            versions.selectById(context.sourceVersionId()).getCompositionFingerprint(),
            snapshot.getStructureFingerprint())) {
      throw new IllegalStateException("电子图库草稿已变化，请重新检查 BOM");
    }
    return preview(context, snapshot.getBomBatchId());
  }

  /** 财务归类完成后接续本单已组好的图库；图号取已确认来源，客户图号允许未填写。 */
  public String composedDrawingNo(Long itemId, String month, String businessUnit, String org) {
    var context = contexts.load(itemId, businessUnit, org, month);
    if (!ElectronicDrawingWorkflowStage.COMPOSED.equals(context.workflowStage())) return null;
    return requireVersion(context, true).getElectronicDrawingNo();
  }

  private ElectronicDrawingWorkContext sharedContext(
      TechnicalDataCostingSources.Source shared, String businessUnit, String org) {
    var original =
        contexts.load(
            shared.product().getOaFormItemId(),
            businessUnit,
            org,
            shared.product().getAccountingMonth());
    var frozen = content.drawingBom(shared.version());
    if (frozen == null || !Objects.equals(original.sourceVersionId(), frozen.sourceVersionId())) {
      throw new IllegalStateException("电子图库来源版本已更新，请重新检查本次报价资料");
    }
    var actual =
        TechnicalDataDrawingSnapshot.from(
            original,
            versions.selectById(original.sourceVersionId()),
            sourceNodes.findByVersionId(original.sourceVersionId()));
    if (!json.dataFingerprint(frozen).equals(json.dataFingerprint(actual))) {
      throw new IllegalStateException("原图库节点与批准快照不一致，请重新检查来源及受影响资料");
    }
    requireVersion(original, true); // 允许复用已发布版本，仍严格核验原产品和组织身份。
    return original;
  }

  private String sharedBatch(
      TechnicalDataCostingSources.Source shared, ElectronicDrawingWorkContext original) {
    String fingerprint =
        versions.selectById(original.sourceVersionId()).getCompositionFingerprint();
    return "ED_SHARED:"
        + shared.version().getId()
        + ":"
        + original.sourceVersionId()
        + ":"
        + fingerprint.substring(0, Math.min(24, fingerprint.length()));
  }

  private String batch(ElectronicDrawingWorkContext context) {
    var version = requireVersion(context, false);
    // 沿用核算链 64 字符批次契约；完整指纹另存并在每次读取时核验。
    String fingerprint = version.getCompositionFingerprint();
    return PREFIX
        + version.getId()
        + ":"
        + fingerprint.substring(0, Math.min(32, fingerprint.length()));
  }

  private QuoteBomSupplementVersion requireVersion(
      ElectronicDrawingWorkContext context, boolean allowApproved) {
    var version =
        context.sourceVersionId() == null ? null : versions.selectById(context.sourceVersionId());
    if (version == null
        || !context.active()
        || !Objects.equals(version.getPreparationId(), context.preparationId())
        || !Objects.equals(version.getPeriodMonth(), context.accountingMonth())
        || !Objects.equals(version.getQuoteProductCode(), context.quoteProductCode())
        || !Objects.equals(version.getMaterialOrgCode(), context.materialOrgCode())
        || !("DRAFT".equals(version.getVersionStatus())
            || allowApproved && "APPROVED".equals(version.getVersionStatus()))
        || !Objects.equals(version.getActiveFlag(), 1)
        || version.getCompositionFingerprint() == null
        || version.getCompositionFingerprint().isBlank()) {
      throw new IllegalStateException("电子图库版本与原报价产品、月份、组织或可用状态不一致");
    }
    return version;
  }

  private List<BomRawHierarchy> preview(ElectronicDrawingWorkContext context, String batch) {
    return converter.preview(
        new PublicationContext(
            context.sourceVersionId(),
            context.quoteProductCode(),
            context.temporaryProductKey(),
            context.priceOrgCode(),
            context.businessUnitType(),
            context.accountingMonth()),
        batch);
  }
}
