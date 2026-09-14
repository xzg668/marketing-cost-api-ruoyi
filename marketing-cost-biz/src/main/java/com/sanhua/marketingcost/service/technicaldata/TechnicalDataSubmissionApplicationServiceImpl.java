package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechReviewItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataSubmissionApplicationServiceImpl
    implements TechnicalDataSubmissionApplicationService {
  private static final Set<String> EDITABLE_TASK_STATUSES = Set.of(
      "PENDING", "IN_PROGRESS", "PARTIALLY_RETURNED");
  private static final Set<String> MODULE_TYPES = Set.of(
      "PROFILE", "PACKAGE", "AUXILIARY", "SALARY");
  private static final Set<String> PACKAGE_UNITS = Set.of(
      "只", "套", "片", "张", "个", "箱", "米", "kg");
  private static final Set<String> PACKAGE_PRICE_BASES = Set.of(
      "HISTORICAL_PRICE", "SUPPLIER_QUOTE", "PENDING_INQUIRY");
  private static final Set<String> AUXILIARY_PRICING_METHODS = Set.of(
      "UNIT_PRICE", "FIXED_AMOUNT");
  private static final Set<String> AUXILIARY_UNIT_PRICE_PAIRS = Set.of(
      "kg|元/kg", "g|元/kg", "g|元/g", "kg|元/g", "件|元/件");
  private static final Set<String> SALARY_LABOR_TYPES = Set.of("DIRECT", "INDIRECT");
  private static final Set<String> SALARY_TIME_UNITS = Set.of("小时/件", "分钟/件");
  private static final Set<String> SALARY_RATE_UNITS = Set.of("元/小时", "元/分钟");

  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final QuoteTechnicalDataPersistenceService persistenceService;
  private final ObjectMapper objectMapper;
  private final TechnicalDataAuditLogService auditLog;

  public TechnicalDataSubmissionApplicationServiceImpl(
      QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository,
      QuoteTechnicalDataPersistenceService persistenceService,
      ObjectMapper objectMapper,
      TechnicalDataAuditLogService auditLog) {
    this.repository = repository;
    this.taskRepository = taskRepository;
    this.persistenceService = persistenceService;
    this.objectMapper = objectMapper;
    this.auditLog = auditLog;
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataTaskValidationResponse validate(Long taskId, TechnicalDataActor actor) {
    requireActor(actor, false);
    QuoteTechTask task = repository.findTask(positive(taskId, "taskId"))
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireAccess(task, actor, false);
    // “校验”与“提交”必须使用同一套门禁，避免页面显示校验通过后又因审核人缺失而提交失败。
    return validateLoaded(task, taskRepository.findProducts(task.getId()), true);
  }

  @Override
  @Transactional
  public TechnicalDataTaskSubmissionResponse submit(
      Long taskId, TechnicalDataTaskSubmissionRequest request, TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    String idempotencyKey = idempotencyKey(request.getIdempotencyKey());
    int expectedTaskVersion = expected(request.getExpectedTaskVersion());
    QuoteTechTask task = repository.lockTask(positive(taskId, "taskId"))
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireAccess(task, actor, true);

    if ("SUBMITTED".equals(task.getTaskStatus())) {
      if (Objects.equals(task.getSubmissionIdempotencyKey(), idempotencyKey)) {
        return submittedResponse(task, true, null, taskRepository.findProducts(task.getId()));
      }
      throw conflict("任务已提交审核，不能使用新的幂等键重复提交");
    }
    if (!EDITABLE_TASK_STATUSES.contains(task.getTaskStatus())) {
      throw conflict("任务状态为" + task.getTaskStatus() + "，当前不能提交审核");
    }
    if (!Objects.equals(task.getTaskVersion(), expectedTaskVersion)) {
      throw conflict("任务已被其他会话修改；当前版本=" + task.getTaskVersion());
    }

    List<QuoteTechProduct> products = repository.lockActiveProducts(task.getId());
    prepareInheritedModulesForSubmission(task, products);
    TechnicalDataTaskValidationResponse validation = validateLoaded(task, products, true);
    if (!validation.valid()) {
      return new TechnicalDataTaskSubmissionResponse(
          false, false, task.getId(), task.getTaskNo(), task.getTaskStatus(),
          task.getReviewStatus(), task.getReviewRound(), task.getTaskVersion(), null,
          null, validation, List.of());
    }

    int reviewRound = task.getReviewRound() + 1;
    LocalDateTime submittedAt = now();
    Map<String, QuoteTechReviewItem> previousReviews = task.getReviewRound() <= 0
        ? Map.of()
        : repository.findReviewItems(task.getId(), task.getReviewRound()).stream()
            .collect(java.util.stream.Collectors.toMap(
                item -> item.getProductId() + "|" + item.getModuleType(), item -> item));
    List<TechnicalDataTaskSubmissionResponse.ProductSubmission> submittedProducts =
        new ArrayList<>();
    for (QuoteTechProduct product : products) {
      List<QuoteTechModule> requiredModules = taskRepository.findModules(product.getId()).stream()
          .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag()))
          .sorted(Comparator.comparing(QuoteTechModule::getModuleType))
          .toList();
      QuoteTechDataVersion submitted;
      if (product.getCurrentEditVersionId() != null) {
        submitted = persistenceService.freezeDraftForSubmission(
            product.getId(), product.getRowVersion(), actor.userId());
      } else if ("PARTIALLY_RETURNED".equals(task.getTaskStatus())
          && product.getLatestSubmittedVersionId() != null) {
        submitted = repository.findVersion(product.getLatestSubmittedVersionId())
            .filter(version -> Set.of(
                QuoteTechDataVersion.STATUS_SUBMITTED,
                QuoteTechDataVersion.STATUS_APPROVED).contains(version.getVersionStatus()))
            .orElseThrow(() -> conflict("继承产品缺少可用的上一轮提交版本"));
      } else {
        throw conflict("产品缺少当前可提交草稿");
      }
      for (QuoteTechModule module : requiredModules) {
        QuoteTechReviewItem previous = previousReviews.get(
            product.getId() + "|" + module.getModuleType());
        boolean inherited = previous != null && "PASSED".equals(previous.getDecision());
        QuoteTechReviewItem reviewItem = new QuoteTechReviewItem();
        reviewItem.setTaskId(task.getId());
        reviewItem.setReviewRound(reviewRound);
        reviewItem.setProductId(product.getId());
        reviewItem.setSubmittedVersionId(submitted.getId());
        reviewItem.setModuleType(module.getModuleType());
        reviewItem.setDecision(inherited ? "PASSED" : "PENDING");
        reviewItem.setInheritedFromReviewItemId(inherited ? previous.getId() : null);
        reviewItem.setDecisionReason(inherited
            ? "继承第" + previous.getReviewRound() + "轮已通过结论" : null);
        reviewItem.setDecidedBy(inherited ? previous.getDecidedBy() : null);
        reviewItem.setDecidedByName(inherited ? previous.getDecidedByName() : null);
        reviewItem.setDecidedAt(inherited ? submittedAt : null);
        reviewItem.setRowVersion(0);
        reviewItem.setDifferenceSnapshotJson(json(Map.of(
            "changeType", inherited ? "INHERITED_REVIEW"
                : reviewRound == 1 ? "INITIAL_SUBMISSION" : "RESUBMISSION",
            "baseVersionId", submitted.getCreatedFromVersionId() == null
                ? 0L : submitted.getCreatedFromVersionId(),
            "inheritedFromReviewItemId", inherited ? previous.getId() : 0L)));
        reviewItem.setValidationSnapshotJson(json(Map.ofEntries(
            Map.entry("valid", true),
            Map.entry("code", "MODULE_VALID"),
            Map.entry("moduleType", module.getModuleType()),
            Map.entry("submittedVersionId", submitted.getId()),
            Map.entry("contentFingerprint", submitted.getContentFingerprint()),
            Map.entry("validatedAt", submittedAt.toString()))));
        repository.insertReviewItem(reviewItem);
        if (inherited) {
          auditLog.record(
              task, product, reviewItem.getId(), "REVIEW_DECISION_INHERITED",
              String.valueOf(previous.getId()), "PASSED", reviewItem.getDecisionReason(),
              actor, "review-round:" + reviewRound);
        }
      }
      submittedProducts.add(new TechnicalDataTaskSubmissionResponse.ProductSubmission(
          product.getId(), product.getMaterialNo(), submitted.getId(), submitted.getVersionNo(),
          submitted.getVersionStatus(), submitted.getContentFingerprint(), requiredModules.size()));
    }

    String submissionFingerprint = submissionFingerprint(submittedProducts);
    if (repository.submitTask(
        task.getId(), expectedTaskVersion, reviewRound, idempotencyKey,
        submissionFingerprint, actor.userId(), submittedAt) != 1) {
      throw conflict("任务提交状态已被其他会话修改");
    }
    QuoteTechTask submittedTask = repository.findTask(task.getId())
        .orElseThrow(() -> conflict("提交后技术资料任务不存在"));
    auditLog.record(
        submittedTask, null, null,
        reviewRound == 1 ? "INITIAL_SUBMISSION" : "VERSION_RESUBMISSION",
        task.getTaskStatus(), "SUBMITTED", "提交第" + reviewRound + "轮审核",
        actor, idempotencyKey);
    return new TechnicalDataTaskSubmissionResponse(
        true, false, submittedTask.getId(), submittedTask.getTaskNo(),
        submittedTask.getTaskStatus(), submittedTask.getReviewStatus(),
        submittedTask.getReviewRound(), submittedTask.getTaskVersion(),
        submittedTask.getSubmissionFingerprint(), submittedTask.getSubmittedAt(),
        validation, submittedProducts);
  }

  private TechnicalDataTaskSubmissionResponse submittedResponse(
      QuoteTechTask task,
      boolean replay,
      TechnicalDataTaskValidationResponse validation,
      List<QuoteTechProduct> products) {
    Map<Long, Long> reviewCounts = new HashMap<>();
    for (QuoteTechReviewItem item : repository.findReviewItems(task.getId(), task.getReviewRound())) {
      reviewCounts.merge(item.getProductId(), 1L, Long::sum);
    }
    List<TechnicalDataTaskSubmissionResponse.ProductSubmission> values = products.stream()
        .filter(product -> product.getLatestSubmittedVersionId() != null)
        .map(product -> {
          QuoteTechDataVersion version = repository.findVersion(product.getLatestSubmittedVersionId())
              .orElseThrow(() -> conflict("任务提交版本不存在：" + product.getLatestSubmittedVersionId()));
          return new TechnicalDataTaskSubmissionResponse.ProductSubmission(
              product.getId(), product.getMaterialNo(), version.getId(), version.getVersionNo(),
              version.getVersionStatus(), version.getContentFingerprint(),
              reviewCounts.getOrDefault(product.getId(), 0L).intValue());
        }).toList();
    return new TechnicalDataTaskSubmissionResponse(
        true, replay, task.getId(), task.getTaskNo(), task.getTaskStatus(),
        task.getReviewStatus(), task.getReviewRound(), task.getTaskVersion(),
        task.getSubmissionFingerprint(), task.getSubmittedAt(), validation, values);
  }

  private TechnicalDataTaskValidationResponse validateLoaded(
      QuoteTechTask task, List<QuoteTechProduct> products, boolean forSubmission) {
    LocalDateTime checkedAt = now();
    List<TechnicalDataTaskValidationResponse.Issue> issues = new ArrayList<>();
    List<TechnicalDataTaskValidationResponse.ProductValidation> productResults = new ArrayList<>();
    if (!Integer.valueOf(1).equals(task.getActiveFlag())) {
      add(issues, null, "TASK", "activeFlag", null,
          "TASK_INACTIVE", "历史任务不能提交", "task-status");
    }
    if (forSubmission && (task.getReviewerUserId() == null || task.getReviewerUserId() <= 0)) {
      add(issues, null, "TASK", "reviewerUserId", null,
          "REVIEWER_REQUIRED", "任务未指定有效审核人", "task-reviewer");
    }
    if (products.isEmpty()) {
      add(issues, null, "TASK", "products", null,
          "PRODUCT_REQUIRED", "任务没有可提交的产品行", "product-table");
    }
    Map<String, QuoteTechReviewItem> previousReviews = "PARTIALLY_RETURNED".equals(task.getTaskStatus())
        ? repository.findReviewItems(task.getId(), task.getReviewRound()).stream()
            .collect(java.util.stream.Collectors.toMap(
                item -> item.getProductId() + "|" + item.getModuleType(), item -> item))
        : Map.of();

    for (QuoteTechProduct product : products) {
      int issueStart = issues.size();
      List<QuoteTechModule> modules = taskRepository.findModules(product.getId());
      boolean inheritedProduct = "PARTIALLY_RETURNED".equals(task.getTaskStatus())
          && product.getCurrentEditVersionId() == null
          && product.getLatestSubmittedVersionId() != null
          && modules.stream().filter(this::requiredModule)
              .allMatch(module -> "APPROVED".equals(module.getModuleStatus()));
      Long versionId = frozenTask(task) || inheritedProduct
          ? product.getLatestSubmittedVersionId() : product.getCurrentEditVersionId();
      QuoteTechDataVersion version = versionId == null ? null
          : repository.findVersion(versionId).orElse(null);
      if (version == null) {
        add(issues, product, "PROFILE", "draftVersionId", null,
            "DRAFT_REQUIRED", "产品尚未形成V1草稿", anchor(product, "PROFILE"));
      } else if (frozenTask(task) || inheritedProduct) {
        if (!QuoteTechDataVersion.STATUS_SUBMITTED.equals(version.getVersionStatus())
            && !QuoteTechDataVersion.STATUS_APPROVED.equals(version.getVersionStatus())) {
          add(issues, product, "PROFILE", "versionStatus", null,
              "SUBMITTED_VERSION_REQUIRED", "产品最近版本不是已提交版本",
              anchor(product, "PROFILE"));
        }
      } else if (!QuoteTechDataVersion.STATUS_DRAFT.equals(version.getVersionStatus())) {
        add(issues, product, "PROFILE", "versionStatus", null,
            "DRAFT_STATUS_INVALID", "产品当前版本不是可提交草稿", anchor(product, "PROFILE"));
      }

      Map<String, QuoteTechModule> byType = moduleMap(product, modules, issues);
      int required = (int) modules.stream()
          .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())).count();
      int ready = 0;
      if (inheritedProduct) {
        if (version == null
            || !Set.of(QuoteTechDataVersion.STATUS_SUBMITTED,
                QuoteTechDataVersion.STATUS_APPROVED).contains(version.getVersionStatus())
            || !StringUtils.hasText(version.getContentFingerprint())) {
          add(issues, product, "STRUCTURE", "latestSubmittedVersionId", null,
              "INHERITED_VERSION_INVALID", "上一轮已通过产品缺少不可变提交版本",
              anchor(product, "STRUCTURE"));
        } else {
          ready = required;
        }
        productResults.add(new TechnicalDataTaskValidationResponse.ProductValidation(
            product.getId(), product.getOaFormItemId(), product.getMaterialNo(),
            product.getProductName(), version == null ? null : version.getId(),
            version == null ? null : version.getVersionNo(), issues.size() == issueStart,
            required, ready));
        continue;
      }
      if (version != null) {
        QuoteTechModule profile = byType.get("PROFILE");
        QuoteTechDataVersion profileVersion = validationVersion(
            task, product, profile, version, previousReviews, issues);
        boolean profileValid = validateProfile(
            product, profileVersion, profile, issues, frozenTask(task));
        if (requiredModule(profile) && profileValid) ready++;
        QuoteTechModule packageModule = byType.get("PACKAGE");
        QuoteTechDataVersion packageVersion = validationVersion(
            task, product, packageModule, version, previousReviews, issues);
        if (requiredModule(packageModule)
            && validatePackage(
                product, packageVersion, packageModule, issues, frozenTask(task))) ready++;
        QuoteTechModule auxiliaryModule = byType.get("AUXILIARY");
        QuoteTechDataVersion auxiliaryVersion = validationVersion(
            task, product, auxiliaryModule, version, previousReviews, issues);
        if (requiredModule(auxiliaryModule)
            && validateAuxiliary(
                product, auxiliaryVersion, auxiliaryModule, issues, frozenTask(task))) ready++;
        QuoteTechModule salaryModule = byType.get("SALARY");
        QuoteTechDataVersion salaryVersion = validationVersion(
            task, product, salaryModule, version, previousReviews, issues);
        if (requiredModule(salaryModule)
            && validateSalary(
                product, salaryVersion, salaryModule, issues, frozenTask(task))) ready++;
        // 非必填模块虽不计入“已就绪/必填”统计，仍需校验原因码和NOT_REQUIRED状态。
        if (!requiredModule(packageModule)) {
          validateNotRequired(product, packageModule, "PACKAGE", issues);
        }
        if (!requiredModule(auxiliaryModule)) {
          validateNotRequired(product, auxiliaryModule, "AUXILIARY", issues);
        }
        if (!requiredModule(salaryModule)) {
          validateNotRequired(product, salaryModule, "SALARY", issues);
        }
      }
      productResults.add(new TechnicalDataTaskValidationResponse.ProductValidation(
          product.getId(), product.getOaFormItemId(), product.getMaterialNo(),
          product.getProductName(), version == null ? null : version.getId(),
          version == null ? null : version.getVersionNo(), issues.size() == issueStart,
          required, ready));
    }
    return new TechnicalDataTaskValidationResponse(
        task.getId(), task.getTaskNo(), issues.isEmpty(), products.size(), checkedAt,
        productResults, issues);
  }

  /**
   * 部分退回的只读预校验不能提前把已通过模块改绑到V2。已通过模块继续按上一轮
   * 不可变提交版本校验；只有退回后重新录入的模块按当前V2草稿校验。
   */
  private QuoteTechDataVersion validationVersion(
      QuoteTechTask task,
      QuoteTechProduct product,
      QuoteTechModule module,
      QuoteTechDataVersion currentDraft,
      Map<String, QuoteTechReviewItem> previousReviews,
      List<TechnicalDataTaskValidationResponse.Issue> issues) {
    if (!"PARTIALLY_RETURNED".equals(task.getTaskStatus())
        || module == null
        || !requiredModule(module)
        || !"APPROVED".equals(module.getModuleStatus())) {
      return currentDraft;
    }
    String type = module.getModuleType();
    QuoteTechReviewItem previous = previousReviews.get(product.getId() + "|" + type);
    if (previous == null
        || !"PASSED".equals(previous.getDecision())
        || !Objects.equals(previous.getSubmittedVersionId(), module.getCurrentVersionId())) {
      add(issues, product, type, "inheritedFromReviewItemId", null,
          "REVIEW_INHERITANCE_INVALID", type + "模块缺少上一轮已通过审核来源",
          anchor(product, type));
      return null;
    }
    QuoteTechDataVersion source = module.getCurrentVersionId() == null ? null
        : repository.findVersion(module.getCurrentVersionId()).orElse(null);
    if (source == null
        || !Objects.equals(source.getProductId(), product.getId())
        || !Set.of(
            QuoteTechDataVersion.STATUS_RETURNED,
            QuoteTechDataVersion.STATUS_SUBMITTED,
            QuoteTechDataVersion.STATUS_APPROVED).contains(source.getVersionStatus())
        || !StringUtils.hasText(source.getContentFingerprint())) {
      add(issues, product, type, "currentVersionId", null,
          "INHERITED_VERSION_INVALID", type + "模块的上一轮不可变版本无效",
          anchor(product, type));
      return null;
    }
    return source;
  }

  private void prepareInheritedModulesForSubmission(
      QuoteTechTask task, List<QuoteTechProduct> products) {
    if (!"PARTIALLY_RETURNED".equals(task.getTaskStatus())) return;
    LocalDateTime changedAt = now();
    for (QuoteTechProduct product : products) {
      Long draftId = product.getCurrentEditVersionId();
      if (draftId == null) continue;
      for (QuoteTechModule module : repository.lockModules(product.getId())) {
        if (!Integer.valueOf(1).equals(module.getRequiredFlag())
            || !"APPROVED".equals(module.getModuleStatus())) continue;
        int rowVersion = module.getRowVersion();
        module.setCurrentVersionId(draftId);
        module.setModuleStatus("READY");
        if (repository.updateModule(module, rowVersion, changedAt) != 1) {
          throw conflict("继承模块已被其他会话修改：" + module.getModuleType());
        }
      }
    }
  }

  private Map<String, QuoteTechModule> moduleMap(
      QuoteTechProduct product,
      List<QuoteTechModule> modules,
      List<TechnicalDataTaskValidationResponse.Issue> issues) {
    Map<String, QuoteTechModule> result = new LinkedHashMap<>();
    for (QuoteTechModule module : modules) {
      if (!MODULE_TYPES.contains(module.getModuleType())) {
        add(issues, product, "STRUCTURE", "moduleType", null,
            "MODULE_TYPE_INVALID", "存在非法模块：" + module.getModuleType(),
            anchor(product, "STRUCTURE"));
      } else if (result.put(module.getModuleType(), module) != null) {
        add(issues, product, module.getModuleType(), "moduleType", null,
            "MODULE_DUPLICATED", "同一产品存在重复模块", anchor(product, module.getModuleType()));
      }
    }
    for (String type : MODULE_TYPES) {
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
    requiredText(issues, product, "PROFILE", "productModel", null,
        version.getProductModel(), "产品型号不能为空");
    if (!Set.of("标准品", "非标品").contains(version.getProductProperty())) {
      add(issues, product, "PROFILE", "productProperty", null,
          "FIELD_INVALID", "产品属性必须为标准品或非标品", anchor(product, "PROFILE"));
    }
    if (version.getNewProductFlag() == null
        || !Set.of(0, 1).contains(version.getNewProductFlag())) {
      add(issues, product, "PROFILE", "newProductFlag", null,
          "FIELD_INVALID", "新品标识必须明确为是或否", anchor(product, "PROFILE"));
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
    Set<String> materials = new HashSet<>();
    for (QuoteTechPackageItem item : items) {
      Integer line = item.getLineNo();
      requiredText(issues, product, "PACKAGE", "componentMaterialNo", line,
          item.getComponentMaterialNo(), "包装组件料号不能为空");
      requiredText(issues, product, "PACKAGE", "componentName", line,
          item.getComponentName(), "包装组件名称不能为空");
      positive(issues, product, "PACKAGE", "quantity", line, item.getQuantity(), "包装用量必须大于0");
      positive(issues, product, "PACKAGE", "standardQuantity", line,
          item.getStandardQuantity(), "包装标准用量必须大于0");
      positive(issues, product, "PACKAGE", "conversionFactor", line,
          item.getConversionFactor(), "包装换算系数必须大于0");
      if (!PACKAGE_UNITS.contains(item.getOriginalUnit())) {
        add(issues, product, "PACKAGE", "originalUnit", line,
            "FIELD_INVALID", "包装单位无效", anchor(product, "PACKAGE"));
      }
      if (!PACKAGE_PRICE_BASES.contains(item.getPriceBasisType())) {
        add(issues, product, "PACKAGE", "priceBasisType", line,
            "FIELD_INVALID", "包装价格依据无效", anchor(product, "PACKAGE"));
      }
      unique(issues, product, "PACKAGE", "componentMaterialNo", line,
          item.getComponentMaterialNo(), materials, "包装组件料号重复");
      validateReferencedItem(product, module, line, item.getSourceReferenceId(),
          item.getSourceSnapshotJson(), issues);
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
    Set<String> materials = new HashSet<>();
    for (QuoteTechAuxItem item : items) {
      Integer line = item.getLineNo();
      requiredText(issues, product, "AUXILIARY", "subjectCode", line,
          item.getSubjectCode(), "辅料科目不能为空");
      requiredText(issues, product, "AUXILIARY", "auxiliaryMaterialNo", line,
          item.getAuxiliaryMaterialNo(), "辅料料号不能为空");
      requiredText(issues, product, "AUXILIARY", "auxiliaryName", line,
          item.getAuxiliaryName(), "辅料名称不能为空");
      if (!AUXILIARY_PRICING_METHODS.contains(item.getPricingMethod())) {
        add(issues, product, "AUXILIARY", "pricingMethod", line,
            "FIELD_INVALID", "辅料计价方式无效", anchor(product, "AUXILIARY"));
      }
      positive(issues, product, "AUXILIARY", "quantity", line, item.getQuantity(), "辅料用量必须大于0");
      positive(issues, product, "AUXILIARY", "standardQuantity", line,
          item.getStandardQuantity(), "辅料标准用量必须大于0");
      positive(issues, product, "AUXILIARY", "referenceUnitPrice", line,
          item.getReferenceUnitPrice(), "辅料参考单价必须大于0");
      positive(issues, product, "AUXILIARY", "amount", line, item.getAmount(), "辅料金额必须大于0");
      if (!AUXILIARY_UNIT_PRICE_PAIRS.contains(item.getOriginalUnit() + "|" + item.getPriceUnit())) {
        add(issues, product, "AUXILIARY", "priceUnit", line,
            "FIELD_INVALID", "辅料用量单位与计价单位不兼容", anchor(product, "AUXILIARY"));
      }
      if (item.getLossRate() == null || item.getLossRate().signum() < 0
          || item.getLossRate().compareTo(BigDecimal.ONE) >= 0) {
        add(issues, product, "AUXILIARY", "lossRate", line,
            "FIELD_INVALID", "辅料损耗率必须大于等于0且小于1", anchor(product, "AUXILIARY"));
      }
      unique(issues, product, "AUXILIARY", "auxiliaryMaterialNo", line,
          item.getAuxiliaryMaterialNo(), materials, "辅料料号重复");
      validateReferencedItem(product, module, line, item.getSourceReferenceId(),
          item.getSourceSnapshotJson(), issues);
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
    Set<String> processLabor = new HashSet<>();
    for (QuoteTechSalaryItem item : items) {
      Integer line = item.getLineNo();
      requiredText(issues, product, "SALARY", "processCode", line,
          item.getProcessCode(), "工序编码不能为空");
      requiredText(issues, product, "SALARY", "processName", line,
          item.getProcessName(), "工序名称不能为空");
      if (!SALARY_LABOR_TYPES.contains(item.getLaborType())) {
        add(issues, product, "SALARY", "laborType", line,
            "FIELD_INVALID", "人工类型无效", anchor(product, "SALARY"));
      }
      positive(issues, product, "SALARY", "workingHours", line,
          item.getWorkingHours(), "标准工时必须大于0");
      positive(issues, product, "SALARY", "standardHours", line,
          item.getStandardHours(), "标准小时必须大于0");
      positive(issues, product, "SALARY", "wageRate", line,
          item.getWageRate(), "工资率必须大于0");
      positive(issues, product, "SALARY", "hourlyRate", line,
          item.getHourlyRate(), "标准小时工资率必须大于0");
      positive(issues, product, "SALARY", "personCoefficient", line,
          item.getPersonCoefficient(), "人数/系数必须大于0");
      positive(issues, product, "SALARY", "amount", line, item.getAmount(), "工资金额必须大于0");
      if (!SALARY_TIME_UNITS.contains(item.getOriginalTimeUnit())) {
        add(issues, product, "SALARY", "originalTimeUnit", line,
            "FIELD_INVALID", "工时单位无效", anchor(product, "SALARY"));
      }
      if (!SALARY_RATE_UNITS.contains(item.getRateUnit())) {
        add(issues, product, "SALARY", "rateUnit", line,
            "FIELD_INVALID", "工资计价单位无效", anchor(product, "SALARY"));
      }
      unique(issues, product, "SALARY", "processCode", line,
          text(item.getProcessCode()) + "|" + text(item.getLaborType()),
          processLabor, "工序和人工类型重复");
      validateReferencedItem(product, module, line, item.getSourceReferenceId(),
          item.getSourceSnapshotJson(), issues);
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
    String expectedStatus = "APPROVED".equals(module.getModuleStatus())
        && Set.of(
            QuoteTechDataVersion.STATUS_RETURNED,
            QuoteTechDataVersion.STATUS_SUBMITTED,
            QuoteTechDataVersion.STATUS_APPROVED).contains(version.getVersionStatus())
        ? "APPROVED" : frozen ? "SUBMITTED" : "READY";
    if (!expectedStatus.equals(module.getModuleStatus())) {
      add(issues, product, type, "moduleStatus", null,
          "MODULE_NOT_READY", type + "模块状态应为" + expectedStatus, anchor(product, type));
    }
    if (!"PROFILE".equals(type)
        && !("MANUAL".equals(module.getEntryMode()) || "REFERENCE".equals(module.getEntryMode()))) {
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

  private boolean frozenTask(QuoteTechTask task) {
    return Set.of("SUBMITTED", "APPROVED").contains(task.getTaskStatus());
  }

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

  private String submissionFingerprint(
      List<TechnicalDataTaskSubmissionResponse.ProductSubmission> products) {
    String canonical = products.stream()
        .sorted(Comparator.comparing(TechnicalDataTaskSubmissionResponse.ProductSubmission::productId))
        .map(value -> value.productId() + ":" + value.submittedVersionId()
            + ":" + value.contentFingerprint())
        .reduce((left, right) -> left + "|" + right).orElse("");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("运行环境缺少SHA-256", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("提交校验快照序列化失败", exception);
    }
  }

  private void requireActor(TechnicalDataActor actor, boolean write) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) throw forbidden("当前登录用户无效");
    if (write ? !actor.canEdit() : !actor.canReadTasks()) {
      throw forbidden(write ? "当前用户无权提交技术资料" : "当前用户无权校验技术资料");
    }
  }

  private void requireAccess(QuoteTechTask task, TechnicalDataActor actor, boolean write) {
    if (!actor.canAccessTask(task.getId())) {
      throw forbidden("短时访问会话不允许跨任务操作");
    }
    if (!Integer.valueOf(1).equals(task.getActiveFlag())) throw forbidden("历史技术资料任务只能查看");
    if (write && actor.admin()
        && !Objects.equals(task.getProxyOperatorUserId(), actor.userId())) {
      throw forbidden("管理员需从任务管理页填写原因并开启受控代录");
    }
    if (!actor.admin() && !Objects.equals(task.getAssigneeUserId(), actor.userId())) {
      if (write || !Objects.equals(task.getReviewerUserId(), actor.userId())) {
        throw forbidden(write ? "只能提交本人负责的技术资料任务" : "只能查看本人负责或审核的技术资料任务");
      }
    }
  }

  private String idempotencyKey(String value) {
    String result = text(value);
    if (result.length() < 8 || result.length() > 128) {
      throw invalid("idempotencyKey长度必须为8到128个字符");
    }
    return result;
  }

  private int expected(Integer value) {
    if (value == null || value < 0) throw invalid("expectedTaskVersion必须大于等于0");
    return value;
  }

  private Long positive(Long value, String field) {
    if (value == null || value <= 0) throw invalid(field + "必须大于0");
    return value;
  }

  private String text(String value) { return value == null ? "" : value.trim(); }

  private LocalDateTime now() { return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE); }

  private TechnicalDataTaskException invalid(String message) {
    return error(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }

  private TechnicalDataTaskException forbidden(String message) {
    return error(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }

  private TechnicalDataTaskException conflict(String message) {
    return error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }

  private TechnicalDataTaskException error(TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }
}
