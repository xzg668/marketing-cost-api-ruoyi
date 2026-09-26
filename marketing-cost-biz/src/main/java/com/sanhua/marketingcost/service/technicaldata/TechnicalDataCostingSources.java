package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 正式组树和成本汇总共用的资料选择：公共明确缺失后，才读取唯一的已批准补录来源。 */
@Service
public class TechnicalDataCostingSources {
  public record Source(String moduleType, QuoteTechTask task, QuoteTechProduct product,
      QuoteTechDataVersion version, Long moduleVersionId) {}
  public record Issue(String moduleType, String code, String message) {}
  public record Selection(QuoteBomReadContext context, Map<String, Source> sources,
      List<TechnicalDataSourceFact> publicFacts, List<Issue> issues) {
    public Selection {
      sources = Map.copyOf(sources);
      publicFacts = List.copyOf(publicFacts);
      issues = List.copyOf(issues);
    }
    public void requireReady() {
      if (!issues.isEmpty()) {
        var first = issues.getFirst();
        throw new EffectiveTechnicalDataException(first.code(), context.oaFormItemId(),
            context.accountingMonth(), issues.stream().map(Issue::moduleType).distinct().toList(),
            String.join("；", issues.stream().map(Issue::message).distinct().toList()));
      }
    }
  }

  private final OaFormItemMapper items;
  private final OaFormMapper forms;
  private final QuoteBomContextResolver contexts;
  private final TechnicalDataPublicSourceCheck publicSources;
  private final TechnicalDataSharedModules shared;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataVersionContentCodec content;
  private final TechnicalDataDependencies dependencies;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final OaMessageCodec json;
  private final QuoteTechModuleMapper modules;

  public TechnicalDataCostingSources(OaFormItemMapper items, OaFormMapper forms,
      QuoteBomContextResolver contexts, TechnicalDataPublicSourceCheck publicSources,
      TechnicalDataSharedModules shared, QuoteTechnicalDataRepository repository,
      TechnicalDataVersionContentCodec content, TechnicalDataDependencies dependencies,
      TechnicalDataOaWorkflowRepository workflow, OaMessageCodec json, QuoteTechModuleMapper modules) {
    this.items = items;
    this.forms = forms;
    this.contexts = contexts;
    this.publicSources = publicSources;
    this.shared = shared;
    this.repository = repository;
    this.content = content;
    this.dependencies = dependencies;
    this.workflow = workflow;
    this.json = json;
    this.modules = modules;
  }

  @Transactional
  public Selection select(Long itemId, String month) {
    var context = context(itemId, month);
    var item = items.selectById(itemId);
    var facts = publicSources.checkDataSources(context);
    Map<String, Source> selected = new LinkedHashMap<>();
    List<Issue> issues = new ArrayList<>();
    for (var fact : facts) {
      String type = fact.moduleType().name();
      if (fact.availability() == TechnicalDataAvailability.AVAILABLE) continue;
      if (fact.availability() != TechnicalDataAvailability.MISSING) {
        issues.add(new Issue(type, "TECH_DATA_SOURCE_NOT_CONFIRMED", fact.reason()));
        continue;
      }
      try {
        var owner = shared.find(item.getMaterialNo(), itemId, type);
        if (owner == null) {
          issues.add(new Issue(type, "TECH_DATA_EFFECTIVE_VERSION_MISSING", fact.reason()));
          continue;
        }
        selected.put(type, requireSource(context, owner));
      } catch (EffectiveTechnicalDataException exception) {
        issues.add(new Issue(type, exception.errorCode(), exception.getMessage()));
      }
    }
    return new Selection(context, selected, facts, issues);
  }

  /** 仅本任务准备阶段允许读取尚未送审的数量；跨报价仍须原任务全部批准且财务确认。 */
  @Transactional
  public Map<String, Source> preparationSources(Long itemId, String month) {
    var context = context(itemId, month);
    var item = items.selectById(itemId);
    Map<String, Source> selected = new LinkedHashMap<>();
    for (var fact : publicSources.checkDataSources(context)) {
      String type = fact.moduleType().name();
      if (!Set.of("PACKAGE", "SOLDER", "MANUFACTURING").contains(type) || fact.availability() == TechnicalDataAvailability.AVAILABLE) continue;
      if (fact.availability() != TechnicalDataAvailability.MISSING) {
        throw error(context, type, "TECH_DATA_SOURCE_NOT_CONFIRMED", fact.reason());
      }
      var owner = shared.find(item.getMaterialNo(), itemId, type);
      if (owner == null) continue;
      var product = repository.findProduct(owner.productId()).orElseThrow();
      if (!Objects.equals(product.getOaFormItemId(), itemId)
          || !Objects.equals(product.getAccountingMonth(), month)) {
        selected.put(type, requireSource(context, owner));
        continue;
      }
      if (!Objects.equals(owner.organization(), context.priceOrgCode())
          || !Objects.equals(owner.businessUnit(), context.businessUnitType())) {
        throw error(context, type, "TECH_DATA_SOURCE_SCOPE_MISMATCH", "补录材料组织与本次报价不一致");
      }
      // 已汇总时使用与正式成本完全相同的行身份；审批前保留本模块个人版本身份。
      Long versionId = product.getEffectiveVersionId() != null ? product.getEffectiveVersionId() : owner.versionId();
      if (versionId == null) continue;
      var version = repository.findVersion(versionId).orElseThrow();
      if (!Objects.equals(version.getProductId(), product.getId())) {
        throw error(context, type, "TECH_DATA_EFFECTIVE_VERSION_INVALID", "补录材料版本不属于本产品");
      }
      var task = repository.findTask(owner.taskId()).orElseThrow();
      selected.put(type, new Source(type, task, product, version, owner.versionId()));
    }
    return Map.copyOf(selected);
  }

  /** 无公共 BOM 的其他报价只能复用原产品已确认的图库，不能另取一份草稿替代它。 */
  @Transactional
  public Source sharedDrawing(Long itemId, String month) {
    var context = context(itemId, month);
    var item = items.selectById(itemId);
    var owner = shared.find(item.getMaterialNo(), itemId, "DRAWING_BOM");
    if (owner == null || Objects.equals(owner.quoteItemId(), itemId)
        && Objects.equals(owner.accountingMonth(), month)) return null;
    return requireSource(context, owner);
  }

  private QuoteBomReadContext context(Long itemId, String month) {
    var item = items.selectById(itemId);
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null) throw new IllegalArgumentException("报价产品行不存在：" + itemId);
    YearMonth.parse(month);
    var organization = contexts.resolveOrganization(form, item);
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    return new QuoteBomReadContext(form.getId(), itemId, form.getOaNo(), month,
        form.getBusinessUnitType(), QuoteProductIdentityUtils.resolveCostingCode(item),
        item.getProductName(), item.getSpec(), item.getSunlModel(), organization.priceOrgCode(),
        organization.materialOrganizationCode(), now.toLocalDate(), now);
  }

  public Source requireSource(QuoteBomReadContext context, TechnicalDataSharedModuleRepository.Owner owner) {
    String type = owner.moduleType();
    String label = "PRICE".equals(type) ? "价格" : TechnicalDataSharedModules.label(type);
    if (!Objects.equals(owner.businessUnit(), context.businessUnitType())
        || !Objects.equals(owner.organization(), context.priceOrgCode())
        || Set.of("SALARY", "NET_LOSS").contains(type)
            && YearMonth.parse(owner.accountingMonth()).getYear() != YearMonth.parse(context.accountingMonth()).getYear()) {
      throw error(context, type, "TECH_DATA_SOURCE_SCOPE_MISMATCH", "原补录组织、业务单元或年度不适用于本次报价，请核实原资料");
    }
    var product = repository.findProduct(owner.productId()).orElseThrow();
    var task = repository.findTask(owner.taskId()).orElseThrow();
    if (!Integer.valueOf(1).equals(product.getActiveFlag()) || !Integer.valueOf(1).equals(task.getActiveFlag())
        || !"APPROVED".equals(task.getTaskStatus()) || !"PASSED".equals(task.getReviewStatus())
        || !"APPROVED".equals(product.getProductStatus()) || product.getEffectiveVersionId() == null
        || !"APPROVED".equals(owner.moduleStatus()) || !"APPROVED".equals(owner.versionStatus())) {
      throw error(context, type, "TECH_DATA_TASK_NOT_APPROVED", "原补录尚未全部审批通过：" + label);
    }
    var flow = task.getOaFlowId() == null ? null : workflow.findFlow(task.getOaFlowId());
    // 本单资料确认前须能检查已批准内容；成本发布另由整单 I06 门禁保护。
    // 其他报价复用时仍要求原报价员已认可资料，避免把待退回内容当作公共有效依据。
    if (!Objects.equals(task.getOaFormId(), context.oaFormId())
        && (flow == null || !flow.financeReady() || flow.confirmedFingerprint() == null
        || !flow.confirmedFingerprint().equals(json.canonicalHash(workflow.approvalBasis(flow.id()))))) {
      throw error(context, type, "TECH_DATA_FINANCE_CONFIRMATION_REQUIRED", "原补录尚未到财务节点并完成资料确认：" + label);
    }
    var version = repository.findVersion(product.getEffectiveVersionId()).orElseThrow();
    if (!Objects.equals(version.getProductId(), product.getId()) || !"APPROVED".equals(version.getVersionStatus())) {
      throw error(context, type, "TECH_DATA_EFFECTIVE_VERSION_INVALID", "原补录有效版本指针异常");
    }
    var actual = content.fingerprint(version, content.readReferenceSnapshot(version.getReferenceSnapshotJson()),
        repository.findPackageItems(version.getId()), repository.findAuxItems(version.getId()), repository.findSalaryItems(version.getId()));
    if (!Objects.equals(actual, version.getContentFingerprint())) {
      throw error(context, type, "TECH_DATA_CONTENT_FINGERPRINT_MISMATCH", "原补录内容与批准指纹不一致");
    }
    var moduleVersions = modules.selectByProductId(product.getId());
    var module = moduleVersions.stream().filter(row -> type.equals(row.getModuleType())).findFirst().orElseThrow();
    if (!Objects.equals(module.getCurrentVersionId(), owner.versionId())) {
      throw error(context, type, "TECH_DATA_SOURCE_CHANGED", "原补录模块版本已变化，请重新检查");
    }
    var submitted = repository.findVersion(owner.versionId()).orElseThrow();
    if (dependencies.stale(submitted, moduleVersions).stream().anyMatch(issue -> type.equals(issue.moduleType()))) {
      throw error(context, type, "TECH_DATA_SOURCE_CHANGED", "原批准补录的引用依据已经变化，请退回原办理人核实");
    }
    return new Source(type, task, product, version, owner.versionId());
  }

  private EffectiveTechnicalDataException error(QuoteBomReadContext context, String module, String code, String message) {
    return new EffectiveTechnicalDataException(code, context.oaFormItemId(), context.accountingMonth(), List.of(module), message);
  }
}
