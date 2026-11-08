package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.PublicationContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSourceFact;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 一次调价产品核算共用已选定的 BOM 来源和检查结果；成功或失败后均立即释放。 */
@Component
public class MonthlyRepriceBomReadScope {
  private final QuoteBomSupplementVersionMapper versions;
  private final QuoteBomSupplementDetailMapper details;
  private final ApprovedElectronicBomRawSnapshotPublisher converter;
  private final ThreadLocal<Read> current = new ThreadLocal<>();

  public MonthlyRepriceBomReadScope(QuoteBomSupplementVersionMapper versions, QuoteBomSupplementDetailMapper details,
      ApprovedElectronicBomRawSnapshotPublisher converter) {
    this.versions = versions;
    this.details = details;
    this.converter = converter;
  }

  public <T> T use(MonthlyRepriceCalculation calculation, Supplier<T> work) {
    if (current.get() != null) throw new IllegalStateException("调价产品核算不能嵌套复用 BOM 来源");
    var read = new Read(calculation, drawing(calculation));
    current.set(read);
    try { return work.get(); }
    finally { current.remove(); }
  }

  /** Price and fee preparation must use the same selected OA, including its copper/board terms. */
  public com.sanhua.marketingcost.dto.CostRunQuotationInput quotation(
      com.sanhua.marketingcost.service.pricing.monthly.QuotationPriceSessions.Request request) {
    var read = current.get();
    if (read == null) return null;
    var selected = read.calculation;
    var input = selected.input();
    if (!Objects.equals(request.oaNo(), input.form().getOaNo())
        || !Objects.equals(request.businessUnit(), input.form().getBusinessUnitType())
        || !Objects.equals(request.month(), input.context().costPeriodMonth())
        || !Objects.equals(request.priceAsOfTime(), selected.priceAsOfTime())
        || request.productCode() != null && !Objects.equals(request.productCode(), input.context().productCode())
        || request.supplierReviews().oaFormItemId() != null
            && !Objects.equals(request.supplierReviews().oaFormItemId(), input.item().getId()))
      throw new IllegalArgumentException("调价取价不能跨选定的OA、产品、月份或取价时点");
    var saved = new com.sanhua.marketingcost.dto.CostRunQuotationInput(input.form(), input.item());
    saved.requireFor(selected.costContext(), request.oaNo(), input.context().productCode());
    return saved;
  }

  /** Null is only the legacy task format; a captured missing unit remains explicitly missing. */
  public com.sanhua.marketingcost.service.pricing.monthly.AnnualUsageInput annualUsage(
      com.sanhua.marketingcost.service.pricing.monthly.QuotationPriceSessions.Request request) {
    if (quotation(request) == null) return null;
    return current.get().calculation.input().annualUsage();
  }

  public CurrentU9BomResult original(QuoteBomReadContext context, Supplier<CurrentU9BomResult> lookup) {
    var read = requiredScope(context);
    if (read == null) return lookup.get();
    var bom = read.calculation.bom();
    return read.drawing == null
        ? CurrentU9BomResult.available("U9", bom.getBomVersion(), bom.getSyncBatchId(), 0)
            .withMonthlySnapshot(bom.getSyncRecordId(), false)
        : CurrentU9BomResult.notFound("本次已确认无 U9 BOM，沿用已选定的有效图库 BOM");
  }

  public ElectronicDrawingWorkContext drawing(QuoteBomReadContext context,
      Supplier<ElectronicDrawingWorkContext> lookup) {
    var read = requiredScope(context);
    return read == null ? lookup.get() : read.drawing;
  }

  public ElectronicDrawingWorkContext costingDrawing(Long itemId, String month,
      Supplier<ElectronicDrawingWorkContext> lookup) {
    var read = current.get();
    if (read == null) return lookup.get();
    if (!Objects.equals(read.calculation.input().item().getId(), itemId)
        || !Objects.equals(read.calculation.input().context().costPeriodMonth(), month))
      throw new IllegalArgumentException("制造件输入不能跨调价产品或月份读取");
    return read.drawing;
  }

  public Long snapshotId(QuoteBomReadContext context) {
    var read = requiredScope(context);
    return read == null ? null : read.calculation.bom().getSyncRecordId();
  }

  /** 已确认、已组装的明细；原始图纸行可能仍含已排除节点，不能重新匹配来替代它。 */
  public List<QuoteBomSupplementDetail> drawingRows(QuoteBomReadContext context) {
    var read = requiredScope(context);
    if (read == null || read.drawing == null) return null;
    return drawingRows(read);
  }

  /** 新月份尚无冻结明细时，从本次已选定的图库版本生成同一份层级。 */
  public List<BomRawHierarchy> drawingHierarchy(QuoteBomMonthlySnapshot snapshot) {
    var read = current.get();
    if (read == null || read.drawing == null) return null;
    var selected = read.calculation.bom();
    var input = read.calculation.input();
    var context = input.context();
    if (!Objects.equals(snapshot.getId(), selected.getSyncRecordId())
        || !Objects.equals(snapshot.getBomBatchId(), selected.getSyncBatchId())
        || !Objects.equals(snapshot.getBomSource(), selected.getBomSource())
        || !Objects.equals(snapshot.getSourceOaFormItemId(), input.item().getId())
        || !Objects.equals(snapshot.getProductCode(), context.productCode())
        || !Objects.equals(snapshot.getCostPeriodMonth(), context.costPeriodMonth())
        || !Objects.equals(snapshot.getPriceOrgCode(), context.organization().priceOrgCode()))
      throw new IllegalArgumentException("月度BOM与本次已选定的图库来源不一致");
    if (read.hierarchy == null) read.hierarchy = converter.approvedRows(
        new PublicationContext(read.drawing.sourceVersionId(), context.productCode(), null,
            context.organization().priceOrgCode(), input.form().getBusinessUnitType(),
            read.drawing.accountingMonth()), drawingRows(read));
    return read.hierarchy;
  }

  private List<QuoteBomSupplementDetail> drawingRows(Read read) {
    if (read.rows == null) read.rows = List.copyOf(details.selectList(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, read.drawing.sourceVersionId())
            .orderByAsc(QuoteBomSupplementDetail::getLineNo)));
    return read.rows;
  }

  public List<TechnicalDataSourceFact> facts(QuoteBomReadContext context,
      Supplier<List<TechnicalDataSourceFact>> check) {
    var read = requiredScope(context);
    if (read == null) return check.get();
    if (read.facts == null) read.facts = List.copyOf(check.get());
    return read.facts;
  }

  private Read requiredScope(QuoteBomReadContext context) {
    var read = current.get();
    if (read == null) return null;
    var input = read.calculation.input();
    var bom = input.context();
    if (!Objects.equals(input.item().getId(), context.oaFormItemId())
        || !Objects.equals(input.form().getId(), context.oaFormId())
        || !Objects.equals(input.form().getBusinessUnitType(), context.businessUnitType())
        || !Objects.equals(bom.productCode(), context.productCode())
        || !Objects.equals(bom.costPeriodMonth(), context.accountingMonth())
        || !Objects.equals(bom.organization().priceOrgCode(), context.priceOrgCode())
        || !Objects.equals(bom.organization().materialOrganizationCode(), context.materialOrganizationCode()))
      throw new IllegalArgumentException("调价 BOM 来源不能跨报价产品、月份或组织复用");
    return read;
  }

  private ElectronicDrawingWorkContext drawing(MonthlyRepriceCalculation calculation) {
    var bom = calculation.bom();
    if ("U9".equals(bom.getBomSource())) return null;
    String batch = bom.getSyncBatchId();
    if (!"ELECTRONIC_DRAWING_BOM".equals(bom.getBomSource())
        || batch == null || !batch.matches("SUPPLEMENT_VERSION:[1-9][0-9]*"))
      throw new IllegalArgumentException("已选定的调价 BOM 缺少明确的图库版本");
    var version = versions.selectById(Long.valueOf(batch.substring("SUPPLEMENT_VERSION:".length())));
    var input = calculation.input();
    var context = input.context();
    var month = YearMonth.parse(context.costPeriodMonth());
    if (version == null || !Objects.equals(version.getOaFormItemId(), input.item().getId())
        || !Objects.equals(version.getQuoteProductCode(), context.productCode())
        || !Objects.equals(version.getMaterialOrgCode(), context.organization().materialOrganizationCode())
        || !"APPROVED".equals(version.getVersionStatus()) || !Integer.valueOf(1).equals(version.getActiveFlag())
        || !StringUtils.hasText(version.getCompositionFingerprint())
        || version.getPeriodMonth() == null || YearMonth.parse(version.getPeriodMonth()).isAfter(month)
        || version.getReuseValidUntil() == null || version.getReuseValidUntil().isBefore(month.atDay(1))
        || version.getEffectiveFrom() != null && version.getEffectiveFrom().isAfter(month.atEndOfMonth())
        || version.getEffectiveTo() != null && version.getEffectiveTo().isBefore(month.atDay(1)))
      throw new IllegalArgumentException("已选定的图库 BOM 未确认、已失效或不属于本次调价产品");
    return new ElectronicDrawingWorkContext(input.item().getId(), 0, version.getPreparationId(),
        version.getId(), input.form().getId(), input.item().getId(), version.getTaskNo(), input.form().getOaNo(),
        context.productCode(), null, input.item().getProductName(), input.item().getSpec(), input.item().getSunlModel(),
        version.getProductType(), "FULL_BOM", version.getPeriodMonth(), context.organization().priceOrgCode(),
        context.organization().materialOrganizationCode(), input.form().getBusinessUnitType(),
        context.organization().priceOrgCode(), true, true, "READY_FOR_COSTING", "PUBLISHED", null, null,
        version.getCompositionFingerprint());
  }

  private static final class Read {
    final MonthlyRepriceCalculation calculation;
    final ElectronicDrawingWorkContext drawing;
    List<TechnicalDataSourceFact> facts;
    List<QuoteBomSupplementDetail> rows;
    List<BomRawHierarchy> hierarchy;
    Read(MonthlyRepriceCalculation calculation, ElectronicDrawingWorkContext drawing) {
      this.calculation = calculation;
      this.drawing = drawing;
    }
  }
}
