package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkspaceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.service.costing.ProductCostingContext;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 只读取报价来源和已发生的核算检查；不接受浏览器自报的来源结论。 */
@Component
public class TechnicalDataQuoteSourceReader {
  private final OaFormItemExtraFieldMapper extraFields;
  private final OaFormItemMapper itemMapper;
  private final OaFormMapper formMapper;
  private final QuoteCostingWorkspaceMapper workspaceMapper;
  private final QuoteBomContextResolver contextResolver;
  private final TechnicalDataSourceCheckService checks;
  private final TechnicalDataModuleRequirementEvaluator evaluator;
  private final TechnicalDataSourceCheckStore store;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final OaMessageCodec codec;
  private final TechnicalDataRequirementRefreshService taskRefresh;
  private final TechnicalDataSharedModuleQuery sharedSources;

  public TechnicalDataQuoteSourceReader(
      OaFormItemMapper itemMapper, OaFormMapper formMapper, OaFormItemExtraFieldMapper extraFields,
      QuoteCostingWorkspaceMapper workspaceMapper, QuoteBomContextResolver contextResolver,
      TechnicalDataSourceCheckService checks, TechnicalDataModuleRequirementEvaluator evaluator,
      TechnicalDataSourceCheckStore store, TechnicalDataSourceSnapshotFactory snapshots, OaMessageCodec codec,
      TechnicalDataRequirementRefreshService taskRefresh, TechnicalDataSharedModuleQuery sharedSources) {
    this.sharedSources = sharedSources;
    this.extraFields = extraFields;
    this.itemMapper = itemMapper;
    this.formMapper = formMapper;
    this.workspaceMapper = workspaceMapper;
    this.contextResolver = contextResolver;
    this.checks = checks;
    this.evaluator = evaluator;
    this.store = store;
    this.snapshots = snapshots;
    this.codec = codec;
    this.taskRefresh = taskRefresh;
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public TechnicalDataSourceCheckResponse recheck(Long itemId, String month) {
    return refreshTask(lockAndRead(itemId, month));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Source lockAndRead(Long itemId, String month) {
    var context = lockContext(itemId, month);
    return check(context.form(), context.item(), context.month(), context.workspace());
  }

  /** 进入补录只使用一键核算已固化的检查结果，不再重复访问九个资料源。 */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Source readChecked(Long itemId, String month) {
    var context = lockContext(itemId, month);
    TechnicalDataSourceCheckResponse check;
    try {
      check = store.read(context.workspace());
    } catch (IllegalStateException exception) {
      throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT,
          exception.getMessage() + "，请重新发起核算");
    }
    if (!Objects.equals(check.oaFormItemId(), itemId)
        || !Objects.equals(check.accountingMonth(), context.month())) {
      throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT,
          "一键核算保存的资料检查范围不一致，请重新发起核算");
    }
    return new Source(context.product(), List.of(), check);
  }

  private LockedContext lockContext(Long itemId, String month) {
    if (itemId == null || itemId <= 0) throw error(TechnicalDataTaskErrorCode.INVALID_REQUEST, "报价产品行不能为空");
    try { month = YearMonth.parse(month).toString(); }
    catch (RuntimeException exception) { throw error(TechnicalDataTaskErrorCode.INVALID_REQUEST, "核算月份应为 YYYY-MM"); }
    OaFormItem item = itemMapper.selectById(itemId);
    OaForm form = item == null ? null : formMapper.selectById(item.getOaFormId());
    if (form == null || (!BusinessUnitContext.isAdmin() && !Objects.equals(
            form.getBusinessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType()))) {
      throw error(TechnicalDataTaskErrorCode.FORBIDDEN, "报价产品不存在或不属于当前业务单元：" + itemId);
    }
    // 所有批量调用按产品行 ID 升序取锁，重复请求在产品行上串行，不依赖不存在行的间隙锁。
    item = itemMapper.selectForCostCompletion(itemId, form.getId(), item.getBusinessUnitType());
    if (item == null) throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "报价产品来源已变化，请重新检查");
    QuoteCostingWorkspace workspace = workspaceMapper.selectByItemAndMonthForUpdate(itemId, month);
    if (workspace == null || workspace.getLastCheckedAt() == null
        || !Objects.equals(workspace.getBusinessUnitType(), form.getBusinessUnitType())) {
      throw error(TechnicalDataTaskErrorCode.INVALID_REQUEST,
          "产品 " + itemId + " 在 " + month + " 尚未执行产品核算或一键核算，请先检查缺口");
    }
    return new LockedContext(form, item, workspace, month, product(form, item));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public TechnicalDataSourceCheckResponse afterCosting(ProductCostingContext context) {
    var workspace = workspaceMapper.selectByItemAndMonth(context.itemId(), context.periodMonth());
    if (workspace == null) throw new IllegalStateException("核算结束后未找到产品工作区");
    return refreshTask(check(context.form(), context.item(), context.periodMonth(), workspace));
  }

  private TechnicalDataSourceCheckResponse refreshTask(Source source) {
    var response = source.check();
    String message = taskRefresh.reconcile(response.oaFormItemId(), response.accountingMonth(),
        source.product().applicableOrgCode(), response.modules());
    return new TechnicalDataSourceCheckResponse(response.oaFormItemId(), response.accountingMonth(),
        response.fingerprint(), response.checkedAt(), response.modules(), message, response.sharedModules());
  }

  private Source check(OaForm form, OaFormItem item, String month, QuoteCostingWorkspace workspace) {
    TechnicalDataProductSource product = product(form, item);
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var context = new QuoteBomReadContext(form.getId(), item.getId(), form.getOaNo(), month,
        form.getBusinessUnitType(), QuoteProductIdentityUtils.resolveCostingCode(item), item.getProductName(), item.getSpec(),
        item.getSunlModel(), product.applicableOrgCode(), product.materialOrganizationCode(), now.toLocalDate(), now);
    var checked = checks.check(context, workspace);
    var requirements = evaluator.evaluate(checked.facts());
    var shared = sharedSources.describe(product, month, checked.facts());
    String fingerprint = codec.canonicalHash(Map.of("product", snapshots.create(product).fingerprint(), "month", month, "sharedModules", shared,
        "facts", requirements.stream().map(row -> List.of(row.moduleType(), row.availability().name(), row.reasonCode(),
            Objects.toString(row.sourceReference(), ""))).toList()));
    var response = new TechnicalDataSourceCheckResponse(item.getId(), month, fingerprint, now, requirements, null, shared);
    store.save(workspace.getId(), response, checked.evidence());
    return new Source(product, checked.facts(), response);
  }

  private TechnicalDataProductSource product(OaForm form, OaFormItem item) {
    QuoteDataOrganization organization;
    try {
      organization = contextResolver.resolveOrganization(form, item);
    } catch (QuoteIngestException exception) {
      throw error(TechnicalDataTaskErrorCode.INVALID_REQUEST, exception.getMessage());
    }
    return new TechnicalDataProductSource(
        form.getId(), form.getOaNo(), item.getId(), item.getExternalLineId(),
        item.getSeq(), item.getMaterialNo(), item.getProductName(), item.getSunlModel(),
        item.getSpec(), item.getProductAttr(),
        item.getFirstQuoteFlag() == null ? null : item.getFirstQuoteFlag() == 1,
        item.getAnnualVolume(), extraFields.selectAnnualVolumeUnit(item.getId()), item.getPackageMethod(), form.getBusinessUnitType(),
        organization.priceOrgCode(), organization.materialOrganizationCode());
  }

  private TechnicalDataTaskException error(TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }

  public record Source(TechnicalDataProductSource product, List<TechnicalDataSourceFact> facts, TechnicalDataSourceCheckResponse check) {}
  private record LockedContext(OaForm form, OaFormItem item, QuoteCostingWorkspace workspace,
      String month, TechnicalDataProductSource product) {}
}
