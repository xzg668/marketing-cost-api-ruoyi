package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 共用提交校验；只读草稿/冻结内容，不继承旧逐模块审核状态，也不执行 OA 发送。 */
@Service
public class TechnicalDataSubmissionValidationService {

  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataDependencies dependencies;
  private final TechnicalDataManufacturingApplicationService manufacturing;
  private final TechnicalDataPackageApplicationService packaging;
  private final TechnicalDataAuxiliaryApplicationService auxiliary;
  private final TechnicalDataSolderApplicationService solder;
  private final TechnicalDataSalaryApplicationService salary;
  private final TechnicalDataNetLossApplicationService netLoss;
  private final TechnicalDataPriceApplicationService prices;

  public TechnicalDataSubmissionValidationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository, TechnicalDataVersionContentCodec codec, TechnicalDataDependencies dependencies,
      TechnicalDataManufacturingApplicationService manufacturing, TechnicalDataPackageApplicationService packaging,
      TechnicalDataAuxiliaryApplicationService auxiliary, TechnicalDataSolderApplicationService solder,
      TechnicalDataSalaryApplicationService salary, TechnicalDataNetLossApplicationService netLoss, TechnicalDataPriceApplicationService prices) {
    this.repository = repository; this.taskRepository = taskRepository; this.codec = codec;
    this.dependencies = dependencies;
    this.manufacturing = manufacturing;
    this.packaging = packaging;
    this.auxiliary = auxiliary;
    this.solder = solder;
    this.salary = salary;
    this.netLoss = netLoss;
    this.prices = prices;
  }

  @Transactional(readOnly = true)
  public TechnicalDataTaskValidationResponse validate(Long taskId, Long assigneeUserId, TechnicalDataActor actor) {
    var task = repository.findTask(taskId).orElseThrow(() -> new IllegalArgumentException("产品任务不存在"));
    var products = taskRepository.findProducts(taskId);
    var allModules = taskRepository.findModules(products.stream().map(QuoteTechProduct::getId).toList());
    if (actor == null || !actor.canReadTask(task, allModules) || assigneeUserId == null
        || !actor.admin() && !Objects.equals(actor.userId(), assigneeUserId)) {
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "无权读取此产品任务");
    }
    List<TechnicalDataTaskValidationResponse.Issue> issues = new ArrayList<>();
    List<TechnicalDataTaskValidationResponse.ProductValidation> results = new ArrayList<>();
    if (!Integer.valueOf(1).equals(task.getActiveFlag()) || products.size() != 1
        || !Integer.valueOf(2).equals(products.getFirst().getContentSchemaVersion())) {
      add(issues, null, "TASK", "taskId", null, "PRODUCT_TASK_REQUIRED", "个人提交须为一个活动产品的九模块任务", "task-status");
    }
    for (var product : products) {
      int start = issues.size();
      var modules = taskRepository.findModules(product.getId());
      var byType = moduleMap(product, modules, issues);
      var own = modules.stream().filter(module -> requiredModule(module)
          && Objects.equals(module.getAssigneeUserId(), assigneeUserId)).toList();
      if (own.isEmpty()) add(issues, product, "TASK", "assigneeUserId", null, "PERSON_SCOPE_EMPTY", "此人没有负责的补录模块", "task-status");
      boolean frozen = !own.isEmpty() && own.stream().allMatch(module -> Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(module.getModuleStatus()));
      Long versionId = frozen ? own.getFirst().getCurrentVersionId() : product.getCurrentEditVersionId();
      var version = versionId == null ? null : repository.findVersion(versionId).orElse(null);
      if (version == null || !Objects.equals(version.getProductId(), product.getId())) {
        add(issues, product, "STRUCTURE", "versionId", null, "VERSION_REQUIRED", "产品缺少当前草稿或提交版本", anchor(product, "STRUCTURE"));
      } else if (!frozen && !"DRAFT".equals(version.getVersionStatus())
          || frozen && !Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(version.getVersionStatus())) {
        add(issues, product, "STRUCTURE", "versionStatus", null, "VERSION_STATUS_INVALID", "产品版本状态与任务不一致", anchor(product, "STRUCTURE"));
      }
      int ready = 0;
      var dependencyIssues = frozen && version != null ? dependencies.stale(version, modules)
          : dependencies.pending(modules, own.stream().map(QuoteTechModule::getModuleType).collect(java.util.stream.Collectors.toSet()));
      for (var issue : dependencyIssues) add(issues, product, issue.moduleType(), "dependency", null,
          "DEPENDENCY_NOT_READY", issue.message(), anchor(product, issue.sourceModuleType()));
      int required = own.size();
      for (String type : TechnicalDataModuleType.codesForVersion(product.getContentSchemaVersion())) {
        var module = byType.get(type);
        if (!own.contains(module)) continue;
        if (!Set.of("AVAILABLE", "MISSING").contains(module.getSourceAvailability() == null ? "UNCONFIRMED" : module.getSourceAvailability())) {
          add(issues, product, type, "sourceAvailability", null, "SOURCE_NOT_CONFIRMED", "本模块来源检查未确认或失败，请先复查", anchor(product, type));
        }
        boolean valid = switch (type) {
          case "PROFILE" -> validateProfile(product, version, module, issues, frozen);
          case "PACKAGE" -> validatePackage(product, version, module, issues, frozen);
          case "AUXILIARY" -> validateAuxiliary(product, version, module, issues, frozen);
          case "SALARY" -> validateSalary(product, version, module, issues, frozen);
          default -> validateSupplement(product, version, module, issues, frozen);
        };
        if (valid) ready++;
      }
      results.add(new TechnicalDataTaskValidationResponse.ProductValidation(product.getId(), product.getOaFormItemId(),
          product.getMaterialNo(), product.getProductName(), versionId, version == null ? null : version.getVersionNo(),
          start == issues.size(), required, ready));
    }
    return new TechnicalDataTaskValidationResponse(taskId, task.getTaskNo(), issues.isEmpty(), products.size(),
        LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE), results, issues);
  }

  private boolean validateSupplement(QuoteTechProduct product, QuoteTechDataVersion version, QuoteTechModule module,
      List<TechnicalDataTaskValidationResponse.Issue> issues, boolean frozen) {
    if (version == null) return false;
    int before = issues.size();
    int itemCount = codec.supplementItemCount(version, module.getModuleType());
    // 原关系已由 U9 补齐时，可保存真实复查证据而无新增原料行；仍逐项核验来源，不造空白明细。
    if ("MANUFACTURING".equals(module.getModuleType()) && codec.manufacturing(version) != null
        && codec.manufacturing(version).evidence() != null) itemCount = Math.max(1, itemCount);
    validateModule(product, version, module, module.getModuleType(), issues, frozen, itemCount);
    if ("DRAWING_BOM".equals(module.getModuleType()) && !frozen) {
      for (String message : TechnicalDataDrawingRules.validate(codec.drawingBom(version), product)) {
        add(issues, product, "DRAWING_BOM", "source", null, "DRAWING_SOURCE_INVALID", message, anchor(product, "DRAWING_BOM"));
      }
    }
    if ("MANUFACTURING".equals(module.getModuleType()) && !frozen) {
      for (String message : manufacturing.validateCurrent(product, codec.manufacturing(version))) {
        add(issues, product, "MANUFACTURING", "materials", null, "MANUFACTURING_INPUT_INVALID", message, anchor(product, "MANUFACTURING"));
      }
    }
    if ("SOLDER".equals(module.getModuleType()) && !frozen) {
      for (String message : solder.validateCurrent(product, version)) {
        add(issues, product, "SOLDER", "items", null, "SOLDER_INPUT_INVALID", message, anchor(product, "SOLDER"));
      }
    }
    if ("NET_LOSS".equals(module.getModuleType())) {
      for (String message : frozen ? TechnicalDataNetLossRules.validate(codec.netLoss(version)) : netLoss.validateCurrent(product, version)) {
        add(issues, product, "NET_LOSS", "rate", null, "NET_LOSS_INPUT_INVALID", message, anchor(product, "NET_LOSS"));
      }
    }
    if ("PRICE".equals(module.getModuleType())) {
      var value = codec.prices(version);
      var messages = frozen ? value == null || value.items() == null ? List.<String>of("尚未填写价格")
          : value.items().stream().flatMap(row -> TechnicalDataPriceRules.validate(row).stream()).toList()
          : prices.validateCurrent(product, version);
      for (String message : messages) add(issues, product, "PRICE", "items", null, "PRICE_INPUT_INVALID", message, anchor(product, "PRICE"));
    }
    return before == issues.size();
  }

  private Map<String, QuoteTechModule> moduleMap(
      QuoteTechProduct product,
      List<QuoteTechModule> modules,
      List<TechnicalDataTaskValidationResponse.Issue> issues) {
    Map<String, QuoteTechModule> result = new LinkedHashMap<>();
    Set<String> expectedTypes = TechnicalDataModuleType.codesForVersion(product.getContentSchemaVersion());
    for (QuoteTechModule module : modules) {
      if (!expectedTypes.contains(module.getModuleType())) {
        add(issues, product, "STRUCTURE", "moduleType", null,
            "MODULE_TYPE_INVALID", "存在非法模块：" + module.getModuleType(),
            anchor(product, "STRUCTURE"));
      } else if (result.put(module.getModuleType(), module) != null) {
        add(issues, product, module.getModuleType(), "moduleType", null,
            "MODULE_DUPLICATED", "同一产品存在重复模块", anchor(product, module.getModuleType()));
      }
    }
    for (String type : expectedTypes) {
      if (!result.containsKey(type)) {
        add(issues, product, type, "module", null,
            "MODULE_MISSING", "产品缺少" + type + "模块", anchor(product, type));
      }
    }
    return result;
  }

  private boolean validateProfile(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      QuoteTechModule module,
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      boolean frozen) {
    int before = issues.size();
    if (version == null) return false;
    validateModule(product, version, module, "PROFILE", issues, frozen, 1);
    if (!"标准品".equals(version.getProductProperty()) && !"非标品".equals(version.getProductProperty())) {
      add(issues, product, "PROFILE", "productProperty", null,
          "FIELD_INVALID", "产品属性必须为标准品或非标品", anchor(product, "PROFILE"));
    }
    for (var issue : TechnicalDataProductFeeRules.validate(codec.productFees(version))) {
      add(issues, product, "PROFILE", issue.field(), null, "FIELD_INVALID", issue.message(), anchor(product, "PROFILE"));
    }
    return issues.size() == before;
  }

  private boolean validatePackage(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      QuoteTechModule module,
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      boolean frozen) {
    if (version == null) return false;
    List<QuoteTechPackageItem> items = repository.findPackageItems(version.getId());
    if (!requiredModule(module)) return validateNotRequired(product, module, "PACKAGE", issues);
    int before = issues.size();
    validateModule(product, version, module, "PACKAGE", issues, frozen, items.size());
    for (String message : frozen ? TechnicalDataPackageRules.validate(codec.packaging(version), items)
        : packaging.validateCurrent(product, version)) {
      add(issues, product, "PACKAGE", "packaging", null, "PACKAGE_INPUT_INVALID", message, anchor(product, "PACKAGE"));
    }
    return issues.size() == before;
  }

  private boolean validateAuxiliary(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      QuoteTechModule module,
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      boolean frozen) {
    if (version == null) return false;
    List<QuoteTechAuxItem> items = repository.findAuxItems(version.getId());
    if (!requiredModule(module)) return validateNotRequired(product, module, "AUXILIARY", issues);
    int before = issues.size();
    validateModule(product, version, module, "AUXILIARY", issues, frozen, items.size());
    for (String message : frozen ? TechnicalDataAuxiliaryRules.validate(items, codec) : auxiliary.validateCurrent(product, version)) {
      add(issues, product, "AUXILIARY", "amount", null, "AUXILIARY_INPUT_INVALID", message, anchor(product, "AUXILIARY"));
    }
    return issues.size() == before;
  }

  private boolean validateSalary(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      QuoteTechModule module,
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      boolean frozen) {
    if (version == null) return false;
    List<QuoteTechSalaryItem> items = repository.findSalaryItems(version.getId());
    if (!requiredModule(module)) return validateNotRequired(product, module, "SALARY", issues);
    int before = issues.size();
    validateModule(product, version, module, "SALARY", issues, frozen, items.size());
    for (String message : frozen ? TechnicalDataSalaryRules.validate(items, codec) : salary.validateCurrent(product, version)) {
      add(issues, product, "SALARY", "source", null, "SALARY_INPUT_INVALID", message, anchor(product, "SALARY"));
    }
    return issues.size() == before;
  }

  private void validateModule(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      QuoteTechModule module,
      String type,
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      boolean frozen,
      int itemCount) {
    if (module == null) return;
    if (!Integer.valueOf(1).equals(module.getRequiredFlag())) {
      add(issues, product, type, "requiredFlag", null,
          "MODULE_REQUIREMENT_INVALID", type + "模块必须标记为必填", anchor(product, type));
      return;
    }
    if (!Objects.equals(module.getCurrentVersionId(), version.getId())) {
      add(issues, product, type, "currentVersionId", null,
          "MODULE_VERSION_MISMATCH", type + "模块未绑定当前版本", anchor(product, type));
    }
    String expectedStatus = frozen ? version.getVersionStatus() : "READY";
    if (frozen ? !expectedStatus.equals(module.getModuleStatus()) : !Set.of("READY", "RETURNED").contains(module.getModuleStatus())) {
      add(issues, product, type, "moduleStatus", null,
          "MODULE_NOT_READY", type + "模块状态应为" + expectedStatus, anchor(product, type));
    }
    if (!"PROFILE".equals(type)
        && !("MANUAL".equals(module.getEntryMode()) || "REFERENCE".equals(module.getEntryMode())
            || Set.of("AUXILIARY", "SALARY").contains(type) && "UPLOAD".equals(module.getEntryMode()))) {
      add(issues, product, type, "entryMode", null,
          "ENTRY_MODE_REQUIRED", type + "模块未选择参照或录入", anchor(product, type));
    }
    if ("PROFILE".equals(type) && !"MANUAL".equals(module.getEntryMode())) {
      add(issues, product, type, "entryMode", null,
          "ENTRY_MODE_REQUIRED", "产品基本信息未保存", anchor(product, type));
    }
    if (!StringUtils.hasText(module.getLastValidationCode())
        || module.getLastValidationCode().contains("INVALID")
        || module.getLastValidationCode().contains("EMPTY")) {
      add(issues, product, type, "lastValidationCode", null,
          "MODULE_VALIDATION_MISSING", type + "模块没有有效服务端校验结果", anchor(product, type));
    }
    if (!"PROFILE".equals(type) && itemCount <= 0) {
      add(issues, product, type, "items", null,
          "DETAIL_REQUIRED", type + "模块至少需要一条完整明细", anchor(product, type));
    }
    if ("REFERENCE".equals(module.getEntryMode())
        && (!StringUtils.hasText(module.getReferenceSourceType())
            || !StringUtils.hasText(module.getReferenceSourceId())
            || !StringUtils.hasText(module.getReferenceFingerprint())
            || !StringUtils.hasText(module.getReferenceSnapshotJson()))) {
      add(issues, product, type, "referenceSnapshot", null,
          "REFERENCE_SNAPSHOT_INCOMPLETE", type + "参照来源快照不完整", anchor(product, type));
    }
  }

  private boolean validateNotRequired(
      QuoteTechProduct product,
      QuoteTechModule module,
      String type,
      List<TechnicalDataTaskValidationResponse.Issue> issues) {
    int before = issues.size();
    if (module == null) return false;
    if (!StringUtils.hasText(module.getRequirementReasonCode())) {
      add(issues, product, type, "requirementReasonCode", null,
          "SKIP_REASON_REQUIRED", type + "非必填模块缺少原因码", anchor(product, type));
    }
    if (!"NOT_REQUIRED".equals(module.getModuleStatus())) {
      add(issues, product, type, "moduleStatus", null,
          "SKIP_STATUS_INVALID", type + "非必填模块状态必须为NOT_REQUIRED", anchor(product, type));
    }
    return issues.size() == before;
  }

  private void validateReferencedItem(
      QuoteTechProduct product,
      QuoteTechModule module,
      Integer line,
      String sourceId,
      String sourceSnapshot,
      List<TechnicalDataTaskValidationResponse.Issue> issues) {
    if (module != null && "REFERENCE".equals(module.getEntryMode())
        && (!StringUtils.hasText(sourceId) || !StringUtils.hasText(sourceSnapshot))) {
      add(issues, product, module.getModuleType(), "sourceSnapshotJson", line,
          "REFERENCE_ITEM_SNAPSHOT_INCOMPLETE", "参照明细缺少来源行快照",
          anchor(product, module.getModuleType()));
    }
  }

  private void requiredText(
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      QuoteTechProduct product,
      String module,
      String field,
      Integer line,
      String value,
      String message) {
    if (!StringUtils.hasText(value)) {
      add(issues, product, module, field, line, "FIELD_REQUIRED", message, anchor(product, module));
    }
  }

  private void positive(
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      QuoteTechProduct product,
      String module,
      String field,
      Integer line,
      BigDecimal value,
      String message) {
    if (value == null || value.signum() <= 0) {
      add(issues, product, module, field, line, "FIELD_INVALID", message, anchor(product, module));
    }
  }

  private void unique(
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      QuoteTechProduct product,
      String module,
      String field,
      Integer line,
      String value,
      Set<String> seen,
      String message) {
    String key = text(value).toUpperCase();
    if (StringUtils.hasText(key) && !seen.add(key)) {
      add(issues, product, module, field, line, "BUSINESS_KEY_DUPLICATED",
          message, anchor(product, module));
    }
  }

  private boolean requiredModule(QuoteTechModule module) {
    return module != null && Integer.valueOf(1).equals(module.getRequiredFlag());
  }


  private String text(String value) { return value == null ? "" : value.trim(); }

  private String anchor(QuoteTechProduct product, String module) {
    return "product-" + product.getId() + "-" + module.toLowerCase();
  }

  private void add(
      List<TechnicalDataTaskValidationResponse.Issue> issues,
      QuoteTechProduct product,
      String module,
      String field,
      Integer line,
      String code,
      String message,
      String anchor) {
    issues.add(new TechnicalDataTaskValidationResponse.Issue(
        product == null ? null : product.getId(),
        product == null ? null : product.getOaFormItemId(),
        product == null ? null : product.getMaterialNo(),
        product == null ? null : product.getProductName(),
        module, field, line, code, message, anchor));
  }

}
