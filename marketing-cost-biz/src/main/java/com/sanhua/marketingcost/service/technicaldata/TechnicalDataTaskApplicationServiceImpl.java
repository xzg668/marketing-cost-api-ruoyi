package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataModuleResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSummaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchPageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchRowResponse;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataTaskApplicationServiceImpl
    implements TechnicalDataTaskApplicationService {
  private static final Set<String> TASK_STATUSES = Set.of(
      "PENDING", "IN_PROGRESS", "SUBMITTED", "PARTIALLY_RETURNED", "APPROVED", "CANCELLED");

  private final TechnicalDataTaskRepository repository;
  private final TechnicalDataModuleRequirementEvaluator requirementEvaluator;
  private final TechnicalDataSourceSnapshotFactory snapshotFactory;
  private final TransactionTemplate transactionTemplate;

  public TechnicalDataTaskApplicationServiceImpl(
      TechnicalDataTaskRepository repository,
      TechnicalDataModuleRequirementEvaluator requirementEvaluator,
      TechnicalDataSourceSnapshotFactory snapshotFactory,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.requirementEvaluator = requirementEvaluator;
    this.snapshotFactory = snapshotFactory;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public TechnicalDataTaskPublishResponse publish(
      TechnicalDataTaskPublishRequest request, TechnicalDataActor actor) {
    TransientDataAccessException lastFailure = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        return Objects.requireNonNull(
            transactionTemplate.execute(status -> publishInTransaction(request, actor)));
      } catch (TransientDataAccessException exception) {
        lastFailure = exception;
      }
    }
    throw new TechnicalDataTaskException(
        TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,
        "并发发布技术资料任务连续3次发生数据库锁冲突，请重试："
            + (lastFailure == null ? "UNKNOWN" : lastFailure.getClass().getSimpleName()));
  }

  private TechnicalDataTaskPublishResponse publishInTransaction(
      TechnicalDataTaskPublishRequest request, TechnicalDataActor actor) {
    requireActor(actor);
    if (!actor.canPublish()) throw forbidden("当前用户无权发布技术资料任务");
    Command command = normalize(request);
    List<PreparedProduct> prepared = prepare(command.products());

    QuoteTechTask activeTask = repository.lockActiveTask(
        command.oaNo(), command.accountingMonth(), command.assigneeUserId()).orElse(null);
    boolean createdTask = activeTask == null;
    if (activeTask == null) activeTask = repository.upsertActiveTask(newTask(command, actor));
    validateTaskIdentity(activeTask, command);

    Map<Long, QuoteTechProduct> activeProducts = lockActiveProducts(prepared, command);
    List<PreparedProduct> changed = new ArrayList<>();
    for (PreparedProduct item : prepared) {
      QuoteTechProduct activeProduct = activeProducts.get(item.source().oaFormItemId());
      if (activeProduct != null
          && !item.snapshot().fingerprint().equals(activeProduct.getSourceFingerprint())) {
        changed.add(item);
      }
    }

    Long replacedTaskId = null;
    if (!changed.isEmpty()) {
      replacedTaskId = replaceChangedTask(activeTask, activeProducts, changed, command, actor);
      activeProducts = Map.of();
      activeTask = repository.upsertActiveTask(newTask(command, actor));
      createdTask = true;
      validateTaskIdentity(activeTask, command);
    }

    int createdProducts = 0;
    for (PreparedProduct item : prepared) {
      QuoteTechProduct stored = activeProducts.get(item.source().oaFormItemId());
      if (stored == null) {
        stored = repository.upsertActiveProduct(newProduct(activeTask, command, item));
        createdProducts++;
      }
      validateProductIdentity(stored, activeTask, command, item);
      ensureModules(stored, item.requirements());
    }

    String action = replacedTaskId != null ? "REPLACED"
        : createdTask ? "CREATED"
        : createdProducts > 0 ? "EXTENDED" : "REUSED";
    return new TechnicalDataTaskPublishResponse(
        action, replacedTaskId, assemble(activeTask));
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataTaskPageResponse mine(
      int current,
      int size,
      String taskStatus,
      String accountingMonth,
      TechnicalDataActor actor) {
    requireActor(actor);
    if (!actor.canReadTasks()) throw forbidden("当前用户无权查看技术资料任务");
    if (actor.shortSession()) throw forbidden("短时任务会话不允许查询任务列表");
    if (current <= 0) throw invalid("current必须大于0");
    if (size <= 0 || size > 100) throw invalid("size必须在1到100之间");
    String status = normalizeStatus(taskStatus);
    String month = StringUtils.hasText(accountingMonth) ? month(accountingMonth) : null;
    String accessMode = accessMode(actor);
    int offset = (current - 1) * size;
    long total = repository.countAccessible(
        accessMode, actor.userId(), status, month);
    List<TechnicalDataTaskSummaryResponse> records = repository.findAccessiblePage(
            accessMode, actor.userId(), status, month, offset, size).stream()
        .map(this::summary)
        .toList();
    return new TechnicalDataTaskPageResponse(total, current, size, records);
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataWorkbenchPageResponse workbench(
      int current,
      int size,
      String taskStatus,
      String accountingMonth,
      String keyword,
      TechnicalDataActor actor) {
    requireActor(actor);
    if (!actor.technician()) throw forbidden("当前用户无权查看技术资料工作台");
    if (actor.shortSession()) throw forbidden("短时任务会话不允许查询工作台列表");
    if (current <= 0) throw invalid("current必须大于0");
    if (size <= 0 || size > 100) throw invalid("size必须在1到100之间");
    String status = normalizeStatus(taskStatus);
    String month = StringUtils.hasText(accountingMonth) ? month(accountingMonth) : null;
    String search = text("keyword", keyword, 255, false);
    String accessMode = actor.admin() ? "ALL" : "ASSIGNEE";
    int offset = (current - 1) * size;
    long total = repository.countAccessibleProducts(
        accessMode, actor.userId(), status, month, search);
    List<QuoteTechProduct> products = repository.findAccessibleProductPage(
        accessMode, actor.userId(), status, month, search, offset, size);
    Map<Long, QuoteTechTask> tasks = repository.findTasks(products.stream()
            .map(QuoteTechProduct::getTaskId).distinct().toList()).stream()
        .collect(Collectors.toMap(QuoteTechTask::getId, Function.identity()));
    Map<Long, TechnicalDataProductResponse> responses = assembleProducts(products);
    List<TechnicalDataWorkbenchRowResponse> records = products.stream()
        .map(product -> {
          QuoteTechTask task = tasks.get(product.getTaskId());
          if (task == null) {
            throw error(
                TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,
                "产品关联的技术资料任务不存在：" + product.getTaskId());
          }
          return new TechnicalDataWorkbenchRowResponse(
              task.getId(), task.getTaskNo(), task.getOaNo(), task.getAccountingMonth(),
              task.getAssigneeUserId(), task.getAssigneeName(), task.getTaskStatus(),
              task.getTaskVersion(), task.getReviewRound(), task.getDueAt(),
              responses.get(product.getId()));
        })
        .toList();
    return new TechnicalDataWorkbenchPageResponse(total, current, size, records);
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataTaskResponse detail(Long taskId, TechnicalDataActor actor) {
    requireActor(actor);
    if (taskId == null || taskId <= 0) throw invalid("taskId必须大于0");
    QuoteTechTask task = repository.findTask(taskId)
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    if (!canRead(task, actor)) throw forbidden("只能查看本人负责或本人审核的技术资料任务");
    return assemble(task);
  }

  private Map<Long, QuoteTechProduct> lockActiveProducts(
      List<PreparedProduct> products, Command command) {
    Map<Long, QuoteTechProduct> values = new LinkedHashMap<>();
    for (PreparedProduct item : products) {
      repository.lockActiveProduct(
              item.source().oaFormItemId(), command.accountingMonth())
          .ifPresent(product -> values.put(item.source().oaFormItemId(), product));
    }
    return values;
  }

  private Long replaceChangedTask(
      QuoteTechTask activeTask,
      Map<Long, QuoteTechProduct> activeProducts,
      List<PreparedProduct> changed,
      Command command,
      TechnicalDataActor actor) {
    if (Objects.equals(activeTask.getSourceRequestId(), command.sourceRequestId())) {
      throw invalid("同一sourceRequestId的OA产品源数据发生变化，拒绝按幂等请求覆盖");
    }
    boolean allBelongToTask = activeProducts.values().stream()
        .allMatch(product -> Objects.equals(product.getTaskId(), activeTask.getId()));
    if (!allBelongToTask) {
      throw error(
          TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT,
          "OA产品行已分配给其他活动技术任务，不能静默改派");
    }
    Set<Long> requestedItemIds = command.products().stream()
        .map(TechnicalDataTaskPublishRequest.Product::oaFormItemId)
        .collect(Collectors.toSet());
    List<QuoteTechProduct> oldProducts = repository.lockActiveProducts(activeTask.getId());
    if (oldProducts.stream().anyMatch(
        product -> !requestedItemIds.contains(product.getOaFormItemId()))) {
      throw error(
          TechnicalDataTaskErrorCode.SOURCE_CHANGE_REQUIRES_COMPLETE_TASK,
          "OA源数据变化时必须携带原活动任务的完整产品集合，避免遗漏历史产品");
    }
    LocalDateTime now = LocalDateTime.now();
    int deactivatedProducts = repository.deactivateProducts(activeTask.getId(), now);
    if (deactivatedProducts != oldProducts.size()) {
      throw error(
          TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,
          "替代旧技术资料任务时产品行数发生并发变化");
    }
    String changedItems = changed.stream()
        .map(item -> String.valueOf(item.source().oaFormItemId()))
        .collect(Collectors.joining(","));
    int rows = repository.deactivateTask(
        activeTask.getId(),
        "OA_SOURCE_CHANGED:items=" + changedItems + ":request=" + command.sourceRequestId(),
        actor.userId(),
        now);
    if (rows != 1) {
      throw error(TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "替代旧技术资料任务失败");
    }
    return activeTask.getId();
  }

  private void ensureModules(
      QuoteTechProduct product, List<TechnicalDataModuleRequirement> requirements) {
    Map<String, QuoteTechModule> stored = repository.findModules(product.getId()).stream()
        .collect(Collectors.toMap(QuoteTechModule::getModuleType, Function.identity()));
    for (TechnicalDataModuleRequirement requirement : requirements) {
      QuoteTechModule module = stored.get(requirement.moduleType());
      if (module == null) module = repository.upsertModule(newModule(product, requirement));
      if (!moduleMatches(module, requirement)) {
        throw error(
            TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,
            "产品" + product.getOaFormItemId() + "的" + requirement.moduleType()
                + "模块判定与已保存结果不一致，拒绝覆盖已有处理状态");
      }
    }
  }

  private boolean moduleMatches(
      QuoteTechModule module, TechnicalDataModuleRequirement requirement) {
    return Objects.equals(module.getRequiredFlag(), requirement.required() ? 1 : 0)
        && Objects.equals(module.getRequirementReasonCode(), requirement.reasonCode())
        && Objects.equals(module.getRequirementReason(), requirement.reason());
  }

  private QuoteTechTask newTask(Command command, TechnicalDataActor actor) {
    QuoteTechTask task = new QuoteTechTask();
    task.setTaskNo(taskNo(command));
    task.setOaFormId(command.oaFormId());
    task.setOaNo(command.oaNo());
    task.setAccountingMonth(command.accountingMonth());
    task.setBusinessUnitType(command.businessUnitType());
    task.setApplicableOrgCode(command.applicableOrgCode());
    task.setAssigneeUserId(command.assigneeUserId());
    task.setAssigneeName(command.assigneeName());
    task.setReviewerUserId(command.reviewerUserId());
    task.setReviewerName(command.reviewerName());
    task.setTaskStatus("PENDING");
    task.setTaskVersion(0);
    task.setReviewRound(0);
    task.setReviewStatus("NOT_STARTED");
    task.setSourceSystem(command.sourceSystem());
    task.setSourceRequestId(command.sourceRequestId());
    task.setExternalSystem(command.externalSystem());
    task.setExternalTaskId(command.externalTaskId());
    task.setExternalTaskStatus(command.externalTaskStatus());
    task.setExternalLastSyncAt(StringUtils.hasText(command.externalTaskId())
        ? LocalDateTime.now() : null);
    task.setDueAt(command.dueAt());
    task.setActiveFlag(1);
    task.setActiveLockKey(QuoteTechnicalDataPersistenceServiceImpl.taskActiveLockKey(
        command.oaNo(), command.accountingMonth(), command.assigneeUserId()));
    task.setCreatedBy(actor.userId());
    task.setUpdatedBy(actor.userId());
    return task;
  }

  private QuoteTechProduct newProduct(
      QuoteTechTask task, Command command, PreparedProduct item) {
    TechnicalDataTaskPublishRequest.Product source = item.source();
    QuoteTechProduct product = new QuoteTechProduct();
    product.setTaskId(task.getId());
    product.setOaFormItemId(source.oaFormItemId());
    product.setLevelNo(source.levelNo());
    product.setMaterialNo(source.materialNo());
    product.setProductName(source.productName());
    product.setSourceModel(source.sourceModel());
    product.setSourceSpec(source.sourceSpec());
    product.setQuoteNo(command.quoteNo());
    product.setAccountingMonth(command.accountingMonth());
    product.setSourceSnapshotJson(item.snapshot().json());
    product.setSourceFingerprint(item.snapshot().fingerprint());
    product.setProductStatus("PENDING");
    product.setActiveFlag(1);
    product.setActiveLockKey(QuoteTechnicalDataPersistenceServiceImpl.productActiveLockKey(
        source.oaFormItemId(), command.accountingMonth()));
    product.setRowVersion(0);
    return product;
  }

  private QuoteTechModule newModule(
      QuoteTechProduct product, TechnicalDataModuleRequirement requirement) {
    QuoteTechModule module = new QuoteTechModule();
    module.setProductId(product.getId());
    module.setModuleType(requirement.moduleType());
    module.setRequiredFlag(requirement.required() ? 1 : 0);
    module.setRequirementReasonCode(requirement.reasonCode());
    module.setRequirementReason(requirement.reason());
    module.setEntryMode("NONE");
    module.setModuleStatus(requirement.required() ? "PENDING" : "NOT_REQUIRED");
    module.setRowVersion(0);
    return module;
  }

  private List<PreparedProduct> prepare(
      List<TechnicalDataTaskPublishRequest.Product> products) {
    List<PreparedProduct> values = new ArrayList<>();
    Set<Long> itemIds = new HashSet<>();
    for (TechnicalDataTaskPublishRequest.Product product : products) {
      TechnicalDataTaskPublishRequest.Product normalized = normalize(product);
      if (!itemIds.add(normalized.oaFormItemId())) {
        throw invalid("products存在重复oaFormItemId：" + normalized.oaFormItemId());
      }
      values.add(new PreparedProduct(
          normalized,
          snapshotFactory.create(normalized),
          requirementEvaluator.evaluate(normalized)));
    }
    values.sort(Comparator
        .comparing((PreparedProduct item) -> item.source().levelNo())
        .thenComparing(item -> item.source().oaFormItemId()));
    return List.copyOf(values);
  }

  private Command normalize(TechnicalDataTaskPublishRequest request) {
    if (request == null) throw invalid("请求不能为空");
    String accountingMonth = month(request.accountingMonth());
    Long oaFormId = positive("oaFormId", request.oaFormId());
    Long assigneeUserId = positive("assigneeUserId", request.assigneeUserId());
    Long reviewerUserId = request.reviewerUserId() == null
        ? null : positive("reviewerUserId", request.reviewerUserId());
    List<TechnicalDataTaskPublishRequest.Product> products = request.products();
    if (products == null || products.isEmpty()) throw invalid("products不能为空");
    if (products.size() > 1000) throw invalid("单个任务产品数不能超过1000");
    return new Command(
        text("sourceRequestId", request.sourceRequestId(), 128, true),
        defaultText(request.sourceSystem(), "QUOTE", 64),
        oaFormId,
        text("oaNo", request.oaNo(), 64, true),
        text("quoteNo", request.quoteNo(), 64, true),
        accountingMonth,
        text("businessUnitType", request.businessUnitType(), 32, true),
        text("applicableOrgCode", request.applicableOrgCode(), 64, true),
        assigneeUserId,
        text("assigneeName", request.assigneeName(), 128, true),
        reviewerUserId,
        reviewerUserId == null ? null
            : text("reviewerName", request.reviewerName(), 128, true),
        text("externalSystem", request.externalSystem(), 64, false),
        text("externalTaskId", request.externalTaskId(), 128, false),
        text("externalTaskStatus", request.externalTaskStatus(), 32, false),
        request.dueAt(),
        List.copyOf(products));
  }

  private TechnicalDataTaskPublishRequest.Product normalize(
      TechnicalDataTaskPublishRequest.Product product) {
    if (product == null) throw invalid("products不能包含null");
    Long itemId = positive("oaFormItemId", product.oaFormItemId());
    int levelNo = product.levelNo() == null ? 1 : product.levelNo();
    if (levelNo <= 0) throw invalid("levelNo必须大于0");
    Map<String, Object> sourceFields = product.sourceFields() == null
        ? Map.of() : Map.copyOf(product.sourceFields());
    return new TechnicalDataTaskPublishRequest.Product(
        itemId,
        levelNo,
        text("materialNo", product.materialNo(), 64, false),
        text("productName", product.productName(), 255, false),
        text("sourceModel", product.sourceModel(), 255, false),
        text("sourceSpec", product.sourceSpec(), 255, false),
        text("sourceProductProperty", product.sourceProductProperty(), 128, false),
        Boolean.TRUE.equals(product.newProduct()),
        Boolean.TRUE.equals(product.nonStandardPackage()),
        Boolean.TRUE.equals(product.validPackageSource()),
        Boolean.TRUE.equals(product.validCmsAuxiliarySource()),
        Boolean.TRUE.equals(product.validCmsSalarySource()),
        Boolean.TRUE.equals(product.auxiliaryRequested()),
        Boolean.TRUE.equals(product.salaryRequested()),
        sourceFields);
  }

  private void validateTaskIdentity(QuoteTechTask task, Command command) {
    if (task.getActiveFlag() == null || task.getActiveFlag() != 1
        || !Objects.equals(task.getOaFormId(), command.oaFormId())
        || !Objects.equals(task.getOaNo(), command.oaNo())
        || !Objects.equals(task.getAccountingMonth(), command.accountingMonth())
        || !Objects.equals(task.getAssigneeUserId(), command.assigneeUserId())
        || !Objects.equals(task.getBusinessUnitType(), command.businessUnitType())
        || !Objects.equals(task.getApplicableOrgCode(), command.applicableOrgCode())
        || !Objects.equals(task.getReviewerUserId(), command.reviewerUserId())) {
      throw error(
          TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT,
          "活动技术任务与本次报价、月份、负责人或审核人不一致");
    }
  }

  private void validateProductIdentity(
      QuoteTechProduct product,
      QuoteTechTask task,
      Command command,
      PreparedProduct item) {
    if (product.getActiveFlag() == null || product.getActiveFlag() != 1
        || !Objects.equals(product.getTaskId(), task.getId())
        || !Objects.equals(product.getOaFormItemId(), item.source().oaFormItemId())
        || !Objects.equals(product.getAccountingMonth(), command.accountingMonth())
        || !Objects.equals(product.getSourceFingerprint(), item.snapshot().fingerprint())) {
      throw error(
          TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT,
          "OA产品行已存在于其他活动任务或源数据发生并发变化");
    }
  }

  private TechnicalDataTaskResponse assemble(QuoteTechTask task) {
    List<QuoteTechProduct> products = repository.findProducts(task.getId());
    Map<Long, TechnicalDataProductResponse> responses = assembleProducts(products);
    List<TechnicalDataProductResponse> productResponses = products.stream()
        .map(product -> responses.get(product.getId()))
        .toList();
    return new TechnicalDataTaskResponse(
        task.getId(), task.getTaskNo(), task.getOaFormId(), task.getOaNo(),
        task.getAccountingMonth(), task.getBusinessUnitType(), task.getApplicableOrgCode(),
        task.getAssigneeUserId(), task.getAssigneeName(), task.getReviewerUserId(),
        task.getReviewerName(), task.getTaskStatus(), task.getTaskVersion(),
        task.getReviewRound(), task.getReviewStatus(), task.getSourceSystem(),
        task.getSourceRequestId(), task.getExternalSystem(), task.getExternalTaskId(),
        task.getExternalTaskStatus(), task.getExternalCallbackSeq(), task.getExternalLastSyncAt(),
        task.getExternalRetryCount(), task.getExternalNextRetryAt(), task.getExternalLastError(),
        task.getProxyOperatorUserId(), task.getProxyOperatorName(), task.getProxyReason(),
        task.getProxyStartedAt(), task.getDueAt(),
        task.getSubmissionFingerprint(), task.getSubmittedAt(),
        task.getCreatedAt(), task.getUpdatedAt(), productResponses);
  }

  private Map<Long, TechnicalDataProductResponse> assembleProducts(
      List<QuoteTechProduct> products) {
    List<Long> productIds = products.stream().map(QuoteTechProduct::getId).toList();
    Map<Long, List<QuoteTechModule>> modules = repository.findModules(productIds).stream()
        .collect(Collectors.groupingBy(
            QuoteTechModule::getProductId, LinkedHashMap::new, Collectors.toList()));
    List<Long> displayVersionIds = new ArrayList<>();
    products.stream().map(this::displayVersionId).filter(Objects::nonNull)
        .forEach(displayVersionIds::add);
    modules.values().stream().flatMap(List::stream)
        .map(QuoteTechModule::getCurrentVersionId).filter(Objects::nonNull)
        .forEach(displayVersionIds::add);
    Map<Long, QuoteTechDataVersion> versions = repository.findVersions(
            displayVersionIds.stream().distinct().toList()).stream()
        .collect(Collectors.toMap(QuoteTechDataVersion::getId, Function.identity()));
    return products.stream()
        .collect(Collectors.toMap(QuoteTechProduct::getId, product -> {
          List<QuoteTechModule> productModules = modules.getOrDefault(product.getId(), List.of());
          Long profileVersionId = productModules.stream()
              .filter(module -> "PROFILE".equals(module.getModuleType()))
              .map(QuoteTechModule::getCurrentVersionId).filter(Objects::nonNull)
              .findFirst().orElse(displayVersionId(product));
          return product(product, versions.get(profileVersionId), productModules);
        }, (left, right) -> left, LinkedHashMap::new));
  }

  private Long displayVersionId(QuoteTechProduct product) {
    if (product.getCurrentEditVersionId() != null) return product.getCurrentEditVersionId();
    if (product.getLatestSubmittedVersionId() != null) return product.getLatestSubmittedVersionId();
    return product.getEffectiveVersionId();
  }

  private TechnicalDataProductResponse product(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      List<QuoteTechModule> modules) {
    return new TechnicalDataProductResponse(
        product.getId(), product.getOaFormItemId(), product.getLevelNo(),
        product.getMaterialNo(), product.getProductName(), product.getSourceModel(),
        product.getSourceSpec(), product.getQuoteNo(), product.getAccountingMonth(),
        product.getSourceSnapshotJson(), product.getSourceFingerprint(),
        product.getProductStatus(), product.getCurrentEditVersionId(),
        product.getLatestSubmittedVersionId(), product.getEffectiveVersionId(),
        product.getRowVersion(), profile(product, version),
        modules.stream().map(this::module).toList());
  }

  private TechnicalDataProfileResponse profile(
      QuoteTechProduct product, QuoteTechDataVersion version) {
    if (version != null) {
      return new TechnicalDataProfileResponse(
          version.getId(), version.getVersionNo(), version.getVersionStatus(),
          version.getProductModel(), version.getProductProperty(),
          Objects.equals(version.getNewProductFlag(), 1), product.getRowVersion(),
          version.getRowVersion(), version.getUpdatedAt());
    }
    TechnicalDataSourceSnapshotFactory.SourceProfile source =
        snapshotFactory.readProfile(product.getSourceSnapshotJson());
    return new TechnicalDataProfileResponse(
        null, null, null, source.productModel(), source.productProperty(), source.newProduct(),
        product.getRowVersion(), null, product.getUpdatedAt());
  }

  private TechnicalDataModuleResponse module(QuoteTechModule module) {
    return new TechnicalDataModuleResponse(
        module.getId(), module.getModuleType(), Objects.equals(module.getRequiredFlag(), 1),
        module.getRequirementReasonCode(), module.getRequirementReason(),
        module.getEntryMode(), module.getModuleStatus(), module.getCurrentVersionId(),
        switch (module.getModuleType()) {
          case "PACKAGE" -> repository.countPackageItems(module.getCurrentVersionId());
          case "AUXILIARY" -> repository.countAuxItems(module.getCurrentVersionId());
          case "SALARY" -> repository.countSalaryItems(module.getCurrentVersionId());
          default -> 0;
        },
        module.getRowVersion());
  }

  private TechnicalDataTaskSummaryResponse summary(QuoteTechTask task) {
    return new TechnicalDataTaskSummaryResponse(
        task.getId(), task.getTaskNo(), task.getOaNo(), task.getAccountingMonth(),
        task.getBusinessUnitType(), task.getApplicableOrgCode(), task.getAssigneeUserId(),
        task.getAssigneeName(), task.getReviewerUserId(), task.getReviewerName(),
        task.getTaskStatus(), task.getReviewStatus(), task.getReviewRound(),
        task.getExternalTaskStatus(), task.getDueAt(), task.getUpdatedAt());
  }

  private boolean canRead(QuoteTechTask task, TechnicalDataActor actor) {
    if (!actor.canAccessTask(task.getId())) return false;
    if (actor.admin()) return true;
    if (!Objects.equals(task.getActiveFlag(), 1)) return false;
    return (actor.technician() && Objects.equals(task.getAssigneeUserId(), actor.userId()))
        || (actor.reviewer() && Objects.equals(task.getReviewerUserId(), actor.userId()));
  }

  private String accessMode(TechnicalDataActor actor) {
    return actor.admin() ? "ALL"
        : actor.reviewer() && actor.technician() ? "ASSIGNEE_OR_REVIEWER"
        : actor.reviewer() ? "REVIEWER" : "ASSIGNEE";
  }

  private String taskNo(Command command) {
    String requestHash = UUID.nameUUIDFromBytes(
            command.sourceRequestId().getBytes(StandardCharsets.UTF_8))
        .toString().replace("-", "").substring(0, 12).toUpperCase();
    return "TD-" + command.accountingMonth().replace("-", "") + "-"
        + command.oaFormId() + "-" + command.assigneeUserId() + "-" + requestHash;
  }

  private String normalizeStatus(String value) {
    if (!StringUtils.hasText(value)) return null;
    String status = value.trim().toUpperCase();
    if (!TASK_STATUSES.contains(status)) throw invalid("taskStatus非法：" + value);
    return status;
  }

  private String month(String value) {
    String normalized = text("accountingMonth", value, 7, true);
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException exception) {
      throw invalid("accountingMonth必须为YYYY-MM");
    }
  }

  private Long positive(String field, Long value) {
    if (value == null || value <= 0) throw invalid(field + "必须大于0");
    return value;
  }

  private String text(String field, String value, int maxLength, boolean required) {
    if (!StringUtils.hasText(value)) {
      if (required) throw invalid(field + "不能为空");
      return null;
    }
    String result = value.trim();
    if (result.length() > maxLength) throw invalid(field + "长度不能超过" + maxLength);
    return result;
  }

  private String defaultText(String value, String defaultValue, int maxLength) {
    return StringUtils.hasText(value)
        ? text("sourceSystem", value, maxLength, true) : defaultValue;
  }

  private void requireActor(TechnicalDataActor actor) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw forbidden("当前登录用户无效");
    }
  }

  private TechnicalDataTaskException invalid(String message) {
    return error(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }

  private TechnicalDataTaskException forbidden(String message) {
    return error(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }

  private TechnicalDataTaskException error(
      TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }

  private record PreparedProduct(
      TechnicalDataTaskPublishRequest.Product source,
      TechnicalDataSourceSnapshotFactory.Snapshot snapshot,
      List<TechnicalDataModuleRequirement> requirements) {}

  private record Command(
      String sourceRequestId,
      String sourceSystem,
      Long oaFormId,
      String oaNo,
      String quoteNo,
      String accountingMonth,
      String businessUnitType,
      String applicableOrgCode,
      Long assigneeUserId,
      String assigneeName,
      Long reviewerUserId,
      String reviewerName,
      String externalSystem,
      String externalTaskId,
      String externalTaskStatus,
      LocalDateTime dueAt,
      List<TechnicalDataTaskPublishRequest.Product> products) {}
}
