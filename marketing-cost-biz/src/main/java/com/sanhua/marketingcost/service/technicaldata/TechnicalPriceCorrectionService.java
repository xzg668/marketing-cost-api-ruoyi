package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 自行公式交接范围及当前缺口。公共／共享取价仍由现有统一取价服务决定。 */
@Service
public class TechnicalPriceCorrectionService {
  public record Scope(
      String oaNo,
      Long itemId,
      String month,
      String businessUnit,
      QuoteTechTask task,
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      List<PriceItem> items) {}

  private final QuoteTechnicalDataRepository technical;
  private final QuoteTechModuleMapper modules;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataPriceOwnership ownership;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final MakePartMaterialPriceResolveService prices;

  public TechnicalPriceCorrectionService(
      QuoteTechnicalDataRepository technical,
      QuoteTechModuleMapper modules,
      OaFormMapper forms,
      OaFormItemMapper items,
      TechnicalDataVersionContentCodec codec,
      TechnicalDataPriceOwnership ownership,
      TechnicalDataOaWorkflowRepository workflow,
      MakePartMaterialPriceResolveService prices) {
    this.technical = technical;
    this.modules = modules;
    this.forms = forms;
    this.items = items;
    this.codec = codec;
    this.ownership = ownership;
    this.workflow = workflow;
    this.prices = prices;
  }

  @Transactional(readOnly = true)
  public TechnicalPriceCorrection get(String oaNo, Long itemId, String month) {
    Scope scope = scope(oaNo, itemId, month, null, false);
    if (scope == null) return null;
    var pending = new ArrayList<TechnicalPriceCorrection.Item>();
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    for (var row : scope.items()) {
      var price =
          prices.calculateMaterialUnitPrice(
              row.materialNo(), month, now.toLocalDate(), now, oaNo, scope.businessUnit(), null);
      if (price == null) throw new IllegalStateException("取价检查未返回结果：" + row.materialNo());
      if ("OK".equals(price.getStatus())
          && price.getUnitPrice() != null
          && price.getUnitPrice().signum() > 0) continue;
      pending.add(
          new TechnicalPriceCorrection.Item(
              row.itemKey(),
              row.materialNo(),
              "TECH_PRICE_CORRECTION_REQUIRED",
              Objects.toString(price.getRemark(), "自行公式尚未形成可用价格")));
    }
    var flow =
        scope.task().getOaFlowId() == null ? null : workflow.findFlow(scope.task().getOaFlowId());
    return new TechnicalPriceCorrection(
        oaNo,
        itemId,
        month,
        scope.businessUnit(),
        scope.version().getId(),
        scope.version().getContentFingerprint(),
        !pending.isEmpty() && flow != null && flow.financeReady(),
        List.copyOf(pending));
  }

  /** 导出／预检／确认均重新核验；确认按原任务、产品、流程、版本顺序加锁。 */
  public Scope require(
      TechnicalPriceImportContext context,
      String month,
      String businessUnit,
      TechnicalDataActor actor,
      boolean lock) {
    if (context == null || context.technicalVersionId() == null)
      throw new IllegalArgumentException("缺少补录审批版本及报价上下文");
    Scope scope =
        scope(context.oaNo(), context.oaFormItemId(), month, context.technicalVersionId(), lock);
    if (scope == null || !Objects.equals(scope.businessUnit(), businessUnit))
      throw new IllegalArgumentException("补录版本、月份或业务单元与本次报价不一致");
    if (actor == null
        || actor.shortSession()
        || !(actor.admin() || actor.has("ingest:quote:cost-run:execute")))
      throw new IllegalArgumentException("无权处理本报价的公式修正");
    if (!actor.admin()
        && !Objects.equals(BusinessUnitContext.getCurrentBusinessUnitType(), businessUnit))
      throw new IllegalArgumentException("不能处理其他业务单元的补录公式");
    var flow = workflow.findFlow(scope.task().getOaFlowId());
    if (flow == null
        || !flow.financeReady()
        || !(actor.admin() || Objects.equals(actor.userId(), flow.financeUserId())))
      throw new IllegalArgumentException("当前不是本单 OA 财务办理节点或办理人");
    return scope;
  }

  private Scope scope(String oaNo, Long itemId, String month, Long versionId, boolean lock) {
    try {
      YearMonth.parse(month);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("核算月份必须为 yyyy-MM");
    }
    var item = items.selectById(itemId);
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null || !Objects.equals(form.getOaNo(), oaNo))
      throw new IllegalArgumentException("报价产品行不属于本报价单");
    String businessUnit =
        item.getBusinessUnitType() == null
            ? form.getBusinessUnitType()
            : item.getBusinessUnitType();
    var product = technical.findActiveProduct(itemId, month).orElse(null);
    if (product == null) return null;
    var task =
        (lock ? technical.lockTask(product.getTaskId()) : technical.findTask(product.getTaskId()))
            .orElseThrow();
    if (lock) {
      product = technical.lockProduct(product.getId()).orElseThrow();
      if (task.getOaFlowId() != null) workflow.lockFlow(task.getOaFlowId());
    }
    var priceModules =
        modules.selectByProductId(product.getId()).stream()
            .filter(
                m ->
                    "PRICE".equals(m.getModuleType())
                        && Integer.valueOf(1).equals(m.getRequiredFlag()))
            .toList();
    if (priceModules.isEmpty()) return null;
    var module = priceModules.getFirst();
    if (!Integer.valueOf(1).equals(task.getActiveFlag())
        || !"APPROVED".equals(task.getTaskStatus())
        || !"PASSED".equals(task.getReviewStatus())
        || !"APPROVED".equals(product.getProductStatus())
        || !"APPROVED".equals(module.getModuleStatus())
        || module.getCurrentVersionId() == null) return null;
    if (versionId != null && !versionId.equals(module.getCurrentVersionId()))
      throw new IllegalArgumentException("审批版本已变化，请回原核算页重新下载");
    var version =
        (lock
                ? technical.lockVersion(module.getCurrentVersionId())
                : technical.findVersion(module.getCurrentVersionId()))
            .orElseThrow();
    if (!Objects.equals(product.getId(), version.getProductId())
        || !"APPROVED".equals(version.getVersionStatus()))
      throw new IllegalArgumentException("价格审批版本已失效");
    String fingerprint =
        codec.fingerprint(
            version,
            codec.readReferenceSnapshot(version.getReferenceSnapshotJson()),
            technical.findPackageItems(version.getId()),
            technical.findAuxItems(version.getId()),
            technical.findSalaryItems(version.getId()));
    if (!Objects.equals(fingerprint, version.getContentFingerprint()))
      throw new IllegalArgumentException("价格审批内容与冻结指纹不一致");
    var content = codec.prices(version);
    List<PriceItem> manual =
        content == null
            ? List.of()
            : content.items().stream().filter(row -> "MANUAL".equals(row.entryMode())).toList();
    for (var row : manual) {
      var owner = ownership.find(row.materialNo());
      if (owner == null
          || !Objects.equals(owner.productId(), product.getId())
          || !Objects.equals(owner.versionId(), version.getId()))
        throw new IllegalArgumentException("料号原补录来源已变化：" + row.materialNo());
    }
    return new Scope(oaNo, itemId, month, businessUnit, task, product, version, manual);
  }
}
