package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
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
  private final ThreadLocal<Read> current = new ThreadLocal<>();

  public MonthlyRepriceBomReadScope(QuoteBomSupplementVersionMapper versions, QuoteBomSupplementDetailMapper details) {
    this.versions = versions;
    this.details = details;
  }

  public <T> T use(MonthlyRepriceCalculation calculation, Supplier<T> work) {
    if (current.get() != null) throw new IllegalStateException("调价产品核算不能嵌套复用 BOM 来源");
    var read = new Read(calculation, drawing(calculation));
    current.set(read);
    try { return work.get(); }
    finally { current.remove(); }
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

  public Long snapshotId(QuoteBomReadContext context) {
    var read = requiredScope(context);
    return read == null ? null : read.calculation.bom().getSyncRecordId();
  }

  /** 已确认、已组装的明细；原始图纸行可能仍含已排除节点，不能重新匹配来替代它。 */
  public List<QuoteBomSupplementDetail> drawingRows(QuoteBomReadContext context) {
    var read = requiredScope(context);
    if (read == null || read.drawing == null) return null;
    if (read.rows == null) read.rows = List.copyOf(details.selectList(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, read.drawing.sourceVersionId())));
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
    Read(MonthlyRepriceCalculation calculation, ElectronicDrawingWorkContext drawing) {
      this.calculation = calculation;
      this.drawing = drawing;
    }
  }
}
