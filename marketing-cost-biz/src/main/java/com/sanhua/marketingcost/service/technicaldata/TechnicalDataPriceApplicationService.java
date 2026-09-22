package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceOwner;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirementsResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirement;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 协调价格草稿、真实需求和料号占用；审批继续走原人员分支。 */
@Service
public class TechnicalDataPriceApplicationService {
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TechnicalDataPriceRequirements requirements;
  private final TechnicalDataPriceReferences references;
  private final TechnicalDataPriceOwnership ownership;
  private final TechnicalDataPricePublication publication;

  public TechnicalDataPriceApplicationService(QuoteTechnicalDataRepository repository, TechnicalDataTaskRepository tasks,
      TechnicalDataVersionContentCodec codec, TechnicalDataSourceSnapshotFactory snapshots,
      TechnicalDataPriceRequirements requirements, TechnicalDataPriceReferences references, TechnicalDataPriceOwnership ownership, TechnicalDataPricePublication publication) {
    this.repository = repository; this.tasks = tasks; this.codec = codec; this.snapshots = snapshots;
    this.publication = publication;
    this.requirements = requirements; this.references = references; this.ownership = ownership;
  }

  @Transactional(readOnly = true)
  public TechnicalDataPriceResponse get(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = versionId != null ? versionId : Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(scope.module().getModuleStatus())
        ? scope.module().getCurrentVersionId() : scope.product().getCurrentEditVersionId();
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("价格版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品的价格版本");
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    // 已送审/批准资料只读冻结内容；跨月查看原资料不能触发原月份的价格准备。
    var check = historical ? null : requirements.check(scope.task(), scope.product());
    var content = codec.prices(version);
    var issues = !historical && check != null ? issues(content, check) : List.<String>of();
    var owners = check == null ? List.<TechnicalDataPriceOwner>of() : check.items().stream()
        .filter(row -> "MISSING".equals(row.status())).map(row -> ownership.find(row.materialNo())).filter(Objects::nonNull)
        .filter(owner -> Objects.equals(owner.businessUnit(), scope.task().getBusinessUnitType())
            && Objects.equals(owner.organizationCode(), scope.task().getApplicableOrgCode())).toList();
    return new TechnicalDataPriceResponse(scope.task().getId(), productId, scope.product().getRowVersion(), selected,
        version == null ? null : version.getVersionStatus(), scope.module().getModuleStatus(),
        !historical && actor.canEditModule(scope.task(), scope.module()), historical, content, check, owners, issues, publication.statuses(version));
  }

  @Transactional(readOnly = true)
  public List<TechnicalDataPriceReference> references(Long productId, String searchBy, String keyword, TechnicalDataActor actor) {
    return references.search(scope(productId, actor, null).task(), searchBy, keyword);
  }

  @Transactional
  public TechnicalDataPriceResponse save(Long productId, TechnicalDataPriceSaveRequest request, TechnicalDataActor actor) {
    if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
        || request.getItems() == null || request.getItems().size() > 500 || !request.getUnknownFields().isEmpty()) throw invalid("价格请求缺少版本或包含不支持的字段");
    var scope = scope(productId, actor, request.getExpectedVersion());
    if (!Integer.valueOf(2).equals(scope.product().getContentSchemaVersion()) || !Integer.valueOf(1).equals(scope.product().getActiveFlag())
        || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("当前价格模块未分派给本人或已送审");
    var check = requirements.check(scope.task(), scope.product());
    if (!Objects.equals(request.getRequirementsFingerprint(), check.fingerprint())) throw conflict("价格需求或来源已变化，请重新检查；本次输入仍保留");
    if (!check.issues().isEmpty()) throw invalid(String.join("；", check.issues()));
    var missing = check.items().stream().filter(row -> "MISSING".equals(row.status())).toList();
    if (missing.isEmpty()) throw conflict("当前已无缺价，无需继续补录，请重新检查资料");
    ownership.require(scope.module().getId(), scope.task().getBusinessUnitType(), missing);
    Map<String, TechnicalDataPriceRequirement> byKey = new LinkedHashMap<>();
    missing.forEach(row -> byKey.put(row.itemKey(), row));
    Set<String> entered = new HashSet<>();
    List<PriceItem> items = new ArrayList<>();
    for (var input : request.getItems()) {
      if (input == null || !input.getUnknownFields().isEmpty() || !entered.add(input.getItemKey())) throw invalid("价格行重复或包含不支持的字段");
      var demand = byKey.get(input.getItemKey());
      if (demand == null) throw conflict("该价格行已不属于当前缺价需求，请重新检查");
      String mode = input.getEntryMode();
      if (!Set.of("FIXED", "REFERENCE", "MANUAL").contains(Objects.toString(mode, ""))) throw invalid("请选择一种补价方式");
      String formula = null;
      TechnicalDataPriceReference reference = null;
      PriceParameters parameters = null;
      if (!"FIXED".equals(mode)) {
        parameters = input.getParameters();
        if ("REFERENCE".equals(mode) && input.getReferenceId() != null) {
          reference = references.require(scope.task(), input.getReferenceId(), input.getReferenceFingerprint());
          formula = reference.formula();
          if (input.getFormula() != null && !Objects.equals(input.getFormula(), formula)) throw invalid("参考方式只修改本次参数；修改公式请切换自行填写");
        } else if ("MANUAL".equals(mode)) {
          formula = text(input.getFormula(), 8000, "公式");
        }
      }
      BigDecimal fixed = "FIXED".equals(mode) ? input.getUnitPrice() : null;
      if (fixed != null && (fixed.precision() > 24 || fixed.scale() > 12)) throw invalid("固定单价超出可保存精度");
      items.add(new PriceItem(demand.itemKey(), demand.materialNo(), demand.organizationCode(), demand.unit(), demand.currency(), mode,
          fixed, formula, reference == null ? null : reference.materialNo(), reference == null ? null : "PRICE_LINKED:" + reference.id(),
          parameters, reference, text(input.getNotes(), 2000, "说明")));
    }
    var content = new Prices(items);
    var issues = issues(content, check);
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = draft(scope.product(), actor, now);
    draft.setPriceItemsJson(codec.pricesJson(content)); draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("价格草稿已变化，请刷新后保存");
    var module = scope.module(); module.setCurrentVersionId(draft.getId()); module.setEntryMode("MANUAL");
    module.setModuleStatus(issues.isEmpty() ? "READY" : "EDITING");
    module.setLastValidationCode(issues.isEmpty() ? "PRICE_COMPLETE" : "PRICE_INCOMPLETE");
    module.setLastValidationMessage(issues.isEmpty() ? "缺价项填写完整，待本人提交审批" : String.join("；", issues));
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("价格模块已变化，请刷新后保存");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), scope.product().getRowVersion(), now) != 1) throw conflict("产品资料已变化，请刷新后保存");
    return get(productId, null, actor);
  }

  @Transactional
  public TechnicalDataPriceResponse recheckPublication(Long productId, TechnicalDataActor actor) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var scope = scope(productId, actor, found.getRowVersion());
    if (!"APPROVED".equals(scope.module().getModuleStatus()) || scope.module().getCurrentVersionId() == null) throw conflict("价格尚未审批通过");
    var version = repository.lockVersion(scope.module().getCurrentVersionId()).orElseThrow();
    publication.publish(scope.task(), scope.product(), version);
    return get(productId, null, actor);
  }

  public void claimForDispatch(Long productId) {
    var product = repository.findProduct(productId).orElseThrow();
    var task = repository.findTask(product.getTaskId()).orElseThrow();
    var check = requirements.check(task, product);
    if (!check.issues().isEmpty()) throw invalid(String.join("；", check.issues()));
    var module = tasks.findModules(productId).stream().filter(row -> "PRICE".equals(row.getModuleType())).findFirst().orElseThrow();
    ownership.require(module.getId(), task.getBusinessUnitType(), check.items());
  }

  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion version) {
    var task = repository.findTask(product.getTaskId()).orElseThrow();
    var check = requirements.check(task, product);
    var content = codec.prices(version);
    var problems = new ArrayList<>(issues(content, check));
    if (content != null && content.items() != null) for (var row : content.items()) {
      var owner = ownership.find(row.materialNo());
      if (owner == null || !Objects.equals(owner.productId(), product.getId())) problems.add(row.materialNo() + " 不属于本人原补录来源");
      if (row.reference() != null) {
        try { references.require(task, row.reference().id(), row.reference().fingerprint()); }
        catch (RuntimeException exception) { problems.add(row.materialNo() + "：" + exception.getMessage()); }
      }
    }
    return List.copyOf(problems);
  }

  private List<String> issues(Prices content, TechnicalDataPriceRequirementsResponse check) {
    List<String> result = new ArrayList<>(check.issues());
    Map<String, PriceItem> entries = new HashMap<>();
    if (content != null && content.items() != null) content.items().forEach(row -> entries.put(row.itemKey(), row));
    for (var demand : check.items()) if ("MISSING".equals(demand.status())) {
      for (String issue : TechnicalDataPriceRules.validate(entries.get(demand.itemKey()))) result.add(demand.materialNo() + "：" + issue);
    }
    return List.copyOf(result);
  }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "PRICE".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有价格模块"));
    if (expected != null && !Objects.equals(product.getRowVersion(), expected)) throw conflict("资料已被其他会话修改，请刷新后重试");
    return new Scope(task, product, module);
  }

  private QuoteTechDataVersion draft(QuoteTechProduct product, TechnicalDataActor actor, LocalDateTime now) {
    if (product.getCurrentEditVersionId() != null) {
      var value = repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
      if (!"DRAFT".equals(value.getVersionStatus()) || !Objects.equals(value.getProductId(), product.getId())) throw conflict("当前版本不能覆盖");
      return value;
    }
    var value = new QuoteTechDataVersion(); var profile = snapshots.readProfile(product.getSourceSnapshotJson());
    value.setProductId(product.getId()); value.setVersionNo(repository.maxVersionNo(product.getId()) + 1);
    value.setVersionStatus("DRAFT"); value.setContentSchemaVersion(2); value.setProductModel(profile.productModel());
    value.setNewProductFlag(Boolean.TRUE.equals(profile.newProduct()) ? 1 : 0);
    value.setPackageTotalAmount(BigDecimal.ZERO); value.setAuxiliaryTotalAmount(BigDecimal.ZERO); value.setSalaryTotalAmount(BigDecimal.ZERO);
    value.setRowVersion(0); value.setCreatedBy(actor.userId()); value.setUpdatedBy(actor.userId()); value.setCreatedAt(now); value.setUpdatedAt(now);
    return repository.insertVersion(value);
  }
  private String text(String value, int limit, String field) {
    if (value != null && value.length() > limit) throw invalid(field + "过长");
    return value == null || value.isBlank() ? null : value.trim();
  }
  private record Scope(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private static TechnicalDataTaskException invalid(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message); }
  private static TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
  private static TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
}
