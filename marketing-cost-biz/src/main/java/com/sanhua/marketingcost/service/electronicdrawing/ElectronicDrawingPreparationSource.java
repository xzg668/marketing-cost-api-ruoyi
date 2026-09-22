package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.PublicationContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataDrawingSnapshot;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** U9 明确无 BOM 时，提供本报价组树草稿或原产品已批准资料的报价范围内快照。 */
@Service
public class ElectronicDrawingPreparationSource {
  private static final String PREFIX = "ED_DRAFT:";
  private final TechnicalDataCostingSources technicalSources;
  private final TechnicalDataVersionContentCodec content;
  private final OaMessageCodec json;
  private final ElectronicDrawingSourceNodeRepository sourceNodes;
  private final QuoteProductBomPreparationService preparation;
  private final QuoteBomPreparationRecordMapper preparationRecords;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final QuoteBomSupplementVersionMapper versions;
  private final QuoteBomMonthlySnapshotMapper snapshots;
  private final ApprovedElectronicBomRawSnapshotPublisher converter;

  public ElectronicDrawingPreparationSource(
      ElectronicDrawingWorkflowContextPort contexts,
      QuoteBomSupplementVersionMapper versions,
      QuoteBomMonthlySnapshotMapper snapshots,
      ApprovedElectronicBomRawSnapshotPublisher converter,
      TechnicalDataCostingSources technicalSources,
      TechnicalDataVersionContentCodec content,
      OaMessageCodec json,
      ElectronicDrawingSourceNodeRepository sourceNodes,
      QuoteProductBomPreparationService preparation,
      QuoteBomPreparationRecordMapper preparationRecords) {
    this.technicalSources = technicalSources;
    this.content = content;
    this.json = json;
    this.sourceNodes = sourceNodes;
    this.preparation = preparation;
    this.preparationRecords = preparationRecords;
    this.contexts = contexts;
    this.versions = versions;
    this.snapshots = snapshots;
    this.converter = converter;
  }

  public QuoteBomMonthlySnapshot snapshot(
      Long itemId,
      String month,
      String businessUnit,
      String org,
      String customer,
      String packageMethod) {
    var shared = technicalSources.sharedDrawing(itemId, month);
    if (shared != null) return sharedSnapshot(itemId, month, businessUnit, org, customer, packageMethod, shared);
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
    result.setBomSource("ELECTRONIC_DRAWING_EXCEL");
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

  public boolean prepareSharedDrawing(Long itemId, String month) {
    if (technicalSources.sharedDrawing(itemId, month) == null) return false;
    var prepared = preparation.prepareByOaFormItem(itemId, YearMonth.parse(month).atDay(1), month);
    if (prepared == null || prepared.preparationRecordId() == null)
      throw new IllegalStateException("本报价 BOM 准备记录尚未建立");
    return true;
  }

  private QuoteBomMonthlySnapshot sharedSnapshot(
      Long itemId,
      String month,
      String businessUnit,
      String org,
      String customer,
      String packageMethod,
      TechnicalDataCostingSources.Source shared) {
    var original = sharedContext(shared, businessUnit, org);
    String batch = sharedBatch(shared, original);
    var current = preparation.prepareByOaFormItem(itemId, YearMonth.parse(month).atDay(1), month);
    if (current == null || current.preparationRecordId() == null)
      throw new IllegalStateException("本报价 BOM 准备记录尚未建立");
    preparationRecords.update(
        null,
        Wrappers.<QuoteBomPreparationRecord>lambdaUpdate()
            .eq(QuoteBomPreparationRecord::getId, current.preparationRecordId())
            .set(QuoteBomPreparationRecord::getPreparationStatus, "READY")
            .set(QuoteBomPreparationRecord::getReusedFromOaFormItemId, original.oaFormItemId())
            .set(QuoteBomPreparationRecord::getReuseType, "APPROVED_TECH_DRAWING")
            .set(QuoteBomPreparationRecord::getErrorMessage, null));
    var found =
        snapshots.selectList(
            Wrappers.<QuoteBomMonthlySnapshot>lambdaQuery()
                .eq(QuoteBomMonthlySnapshot::getSourceOaFormItemId, itemId)
                .eq(QuoteBomMonthlySnapshot::getCostPeriodMonth, month)
                .eq(QuoteBomMonthlySnapshot::getBomBatchId, batch)
                .eq(QuoteBomMonthlySnapshot::getSyncStatus, "DRAFT"));
    if (found.size() > 1) throw new IllegalStateException("本报价复用图库准备记录重复");
    if (!found.isEmpty()) return found.getFirst();
    var snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setProductCode(original.quoteProductCode());
    snapshot.setPriceOrgCode(org);
    snapshot.setBusinessUnitType(businessUnit);
    snapshot.setMaterialOrganizationCode(original.materialOrgCode());
    snapshot.setCustomerCode(customer);
    snapshot.setPackageMethod(packageMethod);
    snapshot.setCostPeriodMonth(month);
    snapshot.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    snapshot.setBomPurpose("主制造");
    snapshot.setBomVersion("ED-" + original.sourceVersionId());
    snapshot.setSyncType("TECH_PREPARATION");
    snapshot.setSyncStatus("DRAFT");
    snapshot.setSyncAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    snapshot.setSyncBy("系统");
    snapshot.setSourceOaNo(current.oaNo());
    snapshot.setSourceOaFormItemId(itemId);
    snapshot.setBomBatchId(batch);
    snapshot.setStructureFingerprint(
        versions.selectById(original.sourceVersionId()).getCompositionFingerprint());
    snapshot.setActiveFlag(1);
    snapshot.setLineCount(preview(original, batch).size());
    snapshots.insert(snapshot);
    return snapshot;
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
