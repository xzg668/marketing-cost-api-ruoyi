package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.security.BusinessUnitContext;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataModuleResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchPageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchRowResponse;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.SysUser;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataTaskApplicationServiceImpl
    implements TechnicalDataTaskApplicationService {
  private static final Set<String> TASK_STATUSES = Set.of(
      "UNASSIGNED", "PENDING", "IN_PROGRESS", "PREPARED", "SUBMITTED", "RETURN_PENDING", "PARTIALLY_RETURNED", "APPROVED", "CANCELLED");

  private com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy oaWorkflowAccess;
  @org.springframework.beans.factory.annotation.Autowired
  public void setOaWorkflowAccess(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy policy) { this.oaWorkflowAccess=policy; }
  private final TechnicalDataTaskRepository repository;
  private final TechnicalDataReadPolicy readPolicy;
  private final TechnicalDataModuleRequirementEvaluator requirementEvaluator;
  private final TechnicalDataSourceSnapshotFactory snapshotFactory;
  private final TransactionTemplate transactionTemplate;
  private final TechnicalDataQuoteSourceReader sourceReader;
  private final TechnicalDataAssigneeResolver assigneeResolver;
  private final TechnicalDataVersionContentCodec contentCodec;
  private final TechnicalDataOaDispatchService oaDispatch;
  private final com.sanhua.marketingcost.integration.oa.OaMessageCodec messageCodec;
  private final TechnicalDataRequirementRefreshService requirementRefresh;
  private final TechnicalDataPendingProductQuery pendingProducts;

  public TechnicalDataTaskApplicationServiceImpl(
      TechnicalDataTaskRepository repository,
      TechnicalDataModuleRequirementEvaluator requirementEvaluator,
      TechnicalDataSourceSnapshotFactory snapshotFactory,
      PlatformTransactionManager transactionManager,
      TechnicalDataQuoteSourceReader sourceReader,
      TechnicalDataAssigneeResolver assigneeResolver,
      TechnicalDataVersionContentCodec contentCodec, TechnicalDataOaDispatchService oaDispatch,
      com.sanhua.marketingcost.integration.oa.OaMessageCodec messageCodec,
      TechnicalDataRequirementRefreshService requirementRefresh, TechnicalDataPendingProductQuery pendingProducts,
      TechnicalDataReadPolicy readPolicy) {
    this.readPolicy=readPolicy;
    this.pendingProducts = pendingProducts;
    this.repository = repository;
    this.sourceReader = sourceReader;
    this.assigneeResolver = assigneeResolver;
    this.contentCodec = contentCodec;
    this.oaDispatch = oaDispatch;
    this.messageCodec = messageCodec;
    this.requirementRefresh = requirementRefresh;
    this.requirementEvaluator = requirementEvaluator;
    this.snapshotFactory = snapshotFactory;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    // 锁住产品后必须能读到前一批刚提交的模块；RR 的旧读视图会把它们误当作不存在。
    this.transactionTemplate.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public TechnicalDataTaskPublishResponse publish(
      TechnicalDataTaskPublishRequest request, TechnicalDataActor actor) {
    // 仅准备事务可在并发冲突后重试；OA 写调用始终在提交事务外且只发送一次。
    TechnicalDataTaskPublishResponse prepared = Objects.requireNonNull(
        transactionTemplate.execute(status -> publishInTransaction(request, actor)));
    var batch = oaDispatch.deliver(prepared.batchId());
    return new TechnicalDataTaskPublishResponse(prepared.requestId(), prepared.items().stream()
        .map(item -> new TechnicalDataTaskPublishResponse.Item(item.oaFormItemId(), item.action(),
            detail(item.task().id(), actor))).toList(), batch.id(), batch.result());
  }

  private TechnicalDataTaskPublishResponse publishInTransaction(
      TechnicalDataTaskPublishRequest request, TechnicalDataActor actor) {
    requireActor(actor);
    if (!actor.canPublish()) throw forbidden("当前用户无权发布技术资料任务");
    if (actor.shortSession()) throw forbidden("短时任务会话不能批量分派产品");
    Command command = normalize(request);
    String fingerprint = messageCodec.canonicalHash(request);
    var replay = oaDispatch.replay(command.requestId(), fingerprint, actor);
    if (replay != null) {
      var items = oaDispatch.taskIds(replay.id()).stream().map(id -> {
        var task = repository.findTask(id).orElseThrow();
        return new TechnicalDataTaskPublishResponse.Item(task.getOaFormItemId(), "REPLAY", assemble(task, actor));
      }).toList();
      return new TechnicalDataTaskPublishResponse(command.requestId(), items, replay.id(), replay.result());
    }
    SysUser assignee = assigneeResolver.resolve(command.assigneeUserId());
    List<QuoteTechTask> selected = new ArrayList<>();
    List<TechnicalDataTaskPublishResponse.Item> results = new ArrayList<>();
    // 一个本地事务：某项归属或数据校验失败时整批回滚，调用方不会遇到半批成功。
    for (Long itemId : command.itemIds()) {
      var source = sourceReader.lockAndRead(itemId, command.accountingMonth());
      oaWorkflowAccess.requireCosting(source.product().oaFormId());
      requireCurrentCheck(request.getCheckFingerprints().get(itemId), source, "分派");
      requireSupplementGap(source);
      QuoteTechTask task = repository.lockActiveTask(itemId, command.accountingMonth()).orElse(null);
      String action = task == null ? "CREATED" : "REUSED";
      if (task == null) {
        task = repository.upsertActiveTask(newTask(
            command.requestId(), command.accountingMonth(), command.dueAt(), source.product(), assignee, actor));
        requireSameAssignment(task, command, source.product());
        var snapshot = snapshotFactory.create(source.product());
        QuoteTechProduct product = repository.upsertActiveProduct(newProduct(task, source.product(), snapshot));
        if (!Objects.equals(product.getTaskId(), task.getId())
            || !Objects.equals(product.getSourceFingerprint(), snapshot.fingerprint())) {
          throw error(TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT, "产品行已被其他活动任务占用");
        }
        for (var requirement : requirementEvaluator.evaluate(source.facts())) {
          repository.upsertModule(newModule(product, requirement));
        }
      } else {
        requireSameContext(task, source.product());
        requirementRefresh.refresh(task, source.check().modules());
        if ("UNASSIGNED".equals(task.getTaskStatus()) && task.getAssigneeUserId() == null) {
          if (repository.assignUnassignedTask(
              task.getId(), task.getTaskVersion(), assignee.getUserId(), displayName(assignee),
              command.requestId(), command.dueAt(), actor.userId()) != 1) {
            throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "产品补录任务已被其他人分派，请刷新后重试");
          }
          task = repository.findTask(task.getId()).orElseThrow();
          action = "ASSIGNED";
        } else {
          requireSameAssignment(task, command, source.product());
        }
      }
      selected.add(task);
      results.add(new TechnicalDataTaskPublishResponse.Item(itemId, action,
          assemble(repository.findTask(task.getId()).orElseThrow(), actor)));
    }
    String batchId = oaDispatch.prepare(selected, command.assigneeUserId(), request.getModuleAssignees(),
        command.requestId(), fingerprint, actor);
    return new TechnicalDataTaskPublishResponse(command.requestId(), results, batchId, null);
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataWorkbenchPageResponse workbench(
      int current,
      int size,
      String taskStatus,
      String accountingMonth,
      String keyword,
      String oaNo,
      TechnicalDataActor actor) {
    requireActor(actor);
    if (!actor.technician() && !actor.canEdit() && !actor.canViewSupplementOverview()) throw forbidden("当前用户无权查看技术资料工作台");
    if (actor.shortSession()) throw forbidden("短时任务会话不允许查询工作台列表");
    if (current <= 0) throw invalid("current必须大于0");
    if (size <= 0 || size > 100) throw invalid("size必须在1到100之间");
    oaNo = text("oaNo", oaNo, 128, false);
    String status = normalizeStatus(taskStatus);
    String month = StringUtils.hasText(accountingMonth) ? month(accountingMonth) : null;
    String search = text("keyword", keyword, 255, false);
    String accessMode = actor.admin() ? "ALL" : actor.canViewSupplementOverview() ? "FINANCE" : "ASSIGNEE";
    String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    if ("FINANCE".equals(accessMode) && businessUnitType == null) throw forbidden("未确定当前业务单元");
    int offset = (current - 1) * size;
    long unassigned = pendingProducts.count(accessMode, businessUnitType, month, search, oaNo);
    long pendingCount = status == null || "UNASSIGNED".equals(status) ? unassigned : 0;
    long taskCount = repository.countAccessibleProducts(accessMode, actor.userId(), businessUnitType, status, month, search, oaNo);
    long total = pendingCount + taskCount;
    List<TechnicalDataWorkbenchRowResponse> records = new ArrayList<>();
    if (offset < pendingCount) records.addAll(pendingProducts.page(accessMode, businessUnitType, month, search, oaNo, offset, size));
    int taskOffset = (int) Math.max(0, offset - pendingCount);
    List<QuoteTechProduct> products = records.size() == size || taskCount == 0 ? List.of()
        : repository.findAccessibleProductPage(accessMode, actor.userId(), businessUnitType,
            status, month, search, oaNo, taskOffset, size - records.size());
    Map<Long, QuoteTechTask> tasks = repository.findTasks(products.stream()
            .map(QuoteTechProduct::getTaskId).distinct().toList()).stream()
        .collect(Collectors.toMap(QuoteTechTask::getId, Function.identity()));
    Map<Long, TechnicalDataProductResponse> responses = assembleProducts(products, actor);
    Map<Long, List<QuoteTechModule>> modules = repository.findModules(products.stream().map(QuoteTechProduct::getId).toList())
        .stream().collect(Collectors.groupingBy(QuoteTechModule::getProductId));
    records.addAll(products.stream()
        .map(product -> {
          QuoteTechTask task = tasks.get(product.getTaskId());
          if (task == null) {
            throw error(
                TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,
                "产品关联的技术资料任务不存在：" + product.getTaskId());
          }
          return new TechnicalDataWorkbenchRowResponse(
              task.getId(), task.getTaskNo(), task.getOaNo(), task.getAccountingMonth(),
              task.getAssigneeUserId(), task.getAssigneeName(), visibleTaskStatus(task, List.of(responses.get(product.getId())), actor),
              task.getTaskVersion(), task.getReviewRound(), task.getDueAt(),
              actor.assignedModules(task, modules.getOrDefault(product.getId(), List.of())),
              modules.getOrDefault(product.getId(), List.of()).stream()
                  .filter(module -> actor.canEditModule(task, module)).map(QuoteTechModule::getModuleType).toList(),
              responses.get(product.getId()), null);
        })
        .toList());
    // 统计遵循月份、关键字和权限范围，不受当前页或状态筛选影响。
    long allTasks = repository.countAccessibleProducts(accessMode, actor.userId(), businessUnitType, null, month, search, oaNo);
    long unassignedTasks = repository.countAccessibleProducts(
        accessMode, actor.userId(), businessUnitType, "UNASSIGNED", month, search, oaNo);
    long pending = unassigned + unassignedTasks;
    for (String pendingStatus : List.of("PENDING", "IN_PROGRESS", "RETURN_PENDING", "PARTIALLY_RETURNED")) {
      pending += repository.countAccessibleProducts(accessMode, actor.userId(), businessUnitType, pendingStatus, month, search, oaNo);
    }
    long approving = repository.countAccessibleProducts(accessMode, actor.userId(), businessUnitType, "PREPARED", month, search, oaNo)
        + repository.countAccessibleProducts(accessMode, actor.userId(), businessUnitType, "SUBMITTED", month, search, oaNo);
    long approved = repository.countAccessibleProducts(accessMode, actor.userId(), businessUnitType, "APPROVED", month, search, oaNo);
    var summary = new TechnicalDataWorkbenchPageResponse.Summary(
        allTasks + unassigned, pending, approving, approved, unassigned + unassignedTasks);
    return new TechnicalDataWorkbenchPageResponse(total, current, size, actor.canViewSupplementOverview(), summary, records);
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataTaskResponse detail(Long taskId, TechnicalDataActor actor) {
    requireActor(actor);
    if (taskId == null || taskId <= 0) throw invalid("taskId必须大于0");
    QuoteTechTask task = repository.findTask(taskId)
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    var modules = repository.findModules(repository.findProducts(taskId).stream()
        .map(QuoteTechProduct::getId).toList());
    if (!actor.canReadTask(task, modules)) throw forbidden("只能查看本人参与或本人审核的技术资料任务");
    return assemble(task, actor);
  }

  private QuoteTechTask newTask(
      String requestId,
      String accountingMonth,
      LocalDateTime dueAt,
      TechnicalDataProductSource source,
      SysUser assignee,
      TechnicalDataActor actor) {
    QuoteTechTask task = new QuoteTechTask();
    task.setTaskNo("TD-" + accountingMonth.replace("-", "") + "-"
        + source.oaFormItemId() + "-" + UUID.randomUUID().toString().substring(0, 8));
    task.setOaFormId(source.oaFormId());
    task.setOaFormItemId(source.oaFormItemId());
    task.setOaNo(source.oaNo());
    task.setAccountingMonth(accountingMonth);
    task.setBusinessUnitType(source.businessUnitType());
    task.setApplicableOrgCode(source.applicableOrgCode());
    task.setAssigneeUserId(assignee == null ? null : assignee.getUserId());
    task.setAssigneeName(assignee == null ? null : displayName(assignee));
    task.setTaskStatus(assignee == null ? "UNASSIGNED" : "PENDING");
    task.setTaskVersion(0);
    task.setReviewRound(0);
    task.setReviewStatus("NOT_STARTED");
    task.setSourceSystem("QUOTE");
    task.setSourceRequestId(requestId);
    task.setDueAt(dueAt);
    task.setActiveFlag(1);
    task.setActiveLockKey(QuoteTechnicalDataPersistenceServiceImpl.taskActiveLockKey(
        source.oaFormItemId(), accountingMonth));
    task.setCreatedBy(actor.userId());
    task.setUpdatedBy(actor.userId());
    return task;
  }

  private String displayName(SysUser user) {
    return StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
  }

  private QuoteTechProduct newProduct(
      QuoteTechTask task, TechnicalDataProductSource source,
      TechnicalDataSourceSnapshotFactory.Snapshot snapshot) {
    QuoteTechProduct product = new QuoteTechProduct();
    product.setTaskId(task.getId());
    product.setContentSchemaVersion(2);
    product.setOaFormItemId(source.oaFormItemId());
    product.setLevelNo(source.levelNo() == null ? 1 : source.levelNo());
    product.setMaterialNo(source.materialNo());
    product.setProductName(source.productName());
    product.setSourceModel(source.sourceModel());
    product.setSourceSpec(source.sourceSpec());
    product.setQuoteNo(source.oaNo());
    product.setAccountingMonth(task.getAccountingMonth());
    product.setSourceSnapshotJson(snapshot.json());
    product.setSourceFingerprint(snapshot.fingerprint());
    product.setProductStatus("PENDING");
    product.setActiveFlag(1);
    product.setActiveLockKey(QuoteTechnicalDataPersistenceServiceImpl.productActiveLockKey(
        source.oaFormItemId(), task.getAccountingMonth()));
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
    module.setSourceAvailability(requirement.availability().name());
    module.setSourceReference(requirement.sourceReference());
    module.setSourceCheckedAt(requirement.checkedAt());
    module.setEntryMode("NONE");
    module.setModuleStatus(requirement.availability() == TechnicalDataAvailability.AVAILABLE
        ? "NOT_REQUIRED" : "PENDING");
    module.setRowVersion(0);
    return module;
  }

  private Command normalize(TechnicalDataTaskPublishRequest request) {
    if (request == null) throw invalid("请求不能为空");
    if (!request.getUnknownFields().isEmpty()) {
      throw invalid("请求包含来源只读字段或未知字段：" + String.join(",", request.getUnknownFields().keySet()));
    }
    List<Long> itemIds = request.getOaFormItemIds();
    if (itemIds == null || itemIds.isEmpty() || itemIds.size() > 1000) {
      throw invalid("oaFormItemIds必须包含1至1000个产品行");
    }
    itemIds.forEach(id -> positive("oaFormItemId", id));
    if (itemIds.stream().distinct().count() != itemIds.size()) throw invalid("产品行不能重复勾选");
    if (request.getCheckFingerprints() == null || !request.getCheckFingerprints().keySet().equals(Set.copyOf(itemIds))
        || request.getCheckFingerprints().values().stream().anyMatch(value -> value == null || !value.matches("[a-f0-9]{64}"))) {
      throw invalid("每个产品须携带本次来源检查版本，请先检查资料缺口");
    }
    return new Command(text("requestId", request.getRequestId(), 128, true),
        month(request.getAccountingMonth()), positive("assigneeUserId", request.getAssigneeUserId()),
        request.getDueAt(), itemIds.stream().sorted().toList());
  }


  private void requireCurrentCheck(
      String expectedFingerprint,
      TechnicalDataQuoteSourceReader.Source source,
      String action) {
    if (!source.check().sharedModules().isEmpty()) {
      var shared = source.check().sharedModules().getFirst();
      throw error(TechnicalDataTaskErrorCode.SHARED_MODULE_CONFLICT,
          shared.message() + (shared.sourceTaskId() == null ? "" : "（原任务 " + shared.sourceTaskId() + "）"));
    }
    if (!Objects.equals(expectedFingerprint, source.check().fingerprint())) {
      throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT,
          "产品 " + source.product().oaFormItemId() + " 的来源或缺口已变化，请重新检查后再" + action);
    }
  }

  private void requireSupplementGap(TechnicalDataQuoteSourceReader.Source source) {
    if (source.check().modules().stream().noneMatch(TechnicalDataModuleRequirement::required)) {
      throw invalid("产品 " + source.product().oaFormItemId() + " 没有已确认的补录缺口，无需分派或办理");
    }
  }

  private void requireSameContext(QuoteTechTask task, TechnicalDataProductSource source) {
    if (!Objects.equals(task.getOaFormId(), source.oaFormId())
        || !Objects.equals(task.getOaFormItemId(), source.oaFormItemId())
        || !Objects.equals(task.getBusinessUnitType(), source.businessUnitType())
        || !Objects.equals(task.getApplicableOrgCode(), source.applicableOrgCode())) {
      throw error(TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT, "活动任务与产品业务上下文不一致");
    }
  }

  private void requireSameAssignment(
      QuoteTechTask task, Command command, TechnicalDataProductSource source) {
    requireSameContext(task, source);
    if (!Objects.equals(task.getAssigneeUserId(), command.assigneeUserId())) {
      throw error(TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT,
          "产品 " + source.oaFormItemId() + " 已分派给 " + task.getAssigneeName() + "，请在原任务中办理改派");
    }
  }

  @Override
  public TechnicalDataTaskResponse submittedDetail(com.sanhua.marketingcost.entity.QuoteTechSubmission submission,
      TechnicalDataActor actor) {
    var task=repository.findTask(submission.getTaskId()).orElseThrow();
    if (!actor.canReadTask(task,repository.findModules(submission.getProductId()))
        || submission.getSentAt()==null) throw forbidden("只能查看已提交的资料");
    return assemble(task,actor,submission);
  }

  private TechnicalDataTaskResponse assemble(QuoteTechTask task, TechnicalDataActor actor) {
    return assemble(task,actor,null);
  }

  private TechnicalDataTaskResponse assemble(QuoteTechTask task, TechnicalDataActor actor,
      com.sanhua.marketingcost.entity.QuoteTechSubmission submission) {
    List<QuoteTechProduct> products = repository.findProducts(task.getId());
    Map<Long, TechnicalDataProductResponse> responses = assembleProducts(products, actor, submission);
    List<TechnicalDataProductResponse> productResponses = products.stream()
        .map(product -> responses.get(product.getId()))
        .toList();
    return new TechnicalDataTaskResponse(
        task.getId(), task.getTaskNo(), task.getOaFormId(), task.getOaNo(),
        task.getAccountingMonth(), task.getBusinessUnitType(), task.getApplicableOrgCode(),
        task.getAssigneeUserId(), task.getAssigneeName(), visibleTaskStatus(task, productResponses, actor), task.getTaskVersion(),
        task.getReviewRound(), task.getReviewStatus(), task.getSourceSystem(),
        task.getSourceRequestId(), task.getExternalSystem(), task.getExternalTaskId(),
        task.getExternalTaskStatus(), task.getExternalCallbackSeq(), task.getExternalLastSyncAt(),
        task.getExternalRetryCount(), task.getExternalNextRetryAt(), task.getExternalLastError(),
        task.getDueAt(),
        task.getSubmissionFingerprint(), task.getSubmittedAt(),
        task.getCreatedAt(), task.getUpdatedAt(), productResponses);
  }

  private Map<Long, TechnicalDataProductResponse> assembleProducts(
      List<QuoteTechProduct> products, TechnicalDataActor actor) {
    return assembleProducts(products,actor,null);
  }

  private Map<Long, TechnicalDataProductResponse> assembleProducts(
      List<QuoteTechProduct> products, TechnicalDataActor actor,
      com.sanhua.marketingcost.entity.QuoteTechSubmission submission) {
    Set<String> submittedTypes=new java.util.HashSet<>();
    if (submission!=null) messageCodec.read(submission.getModuleTypesJson()).forEach(node -> submittedTypes.add(node.asText()));
    Map<Long,TechnicalDataProductResponse> result=new LinkedHashMap<>();
    for (var product:products) {
      var modules=repository.findModules(product.getId()).stream()
          .filter(module -> submission==null || submittedTypes.contains(module.getModuleType())).toList();
      Map<String,QuoteTechDataVersion> visible=new LinkedHashMap<>();
      for (var module:modules) {
        Long id=submission==null ? readPolicy.readVersion(product,module,actor,null) : submission.getTechnicalVersionId();
        if (submission!=null) readPolicy.projectReadOnly(module,id);
        if (id!=null) {
          var versions=repository.findVersions(List.of(id));
          if (versions.isEmpty() || !Objects.equals(versions.getFirst().getProductId(),product.getId())) {
            throw error(TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,"模块版本不存在或不属于当前产品");
          }
          visible.put(module.getModuleType(),versions.getFirst());
        }
      }
      var profileVersion=visible.get("PROFILE");
      var source=snapshotFactory.readProfile(product.getSourceSnapshotJson());
      var supplements=new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SupplementSnapshot(
          contentCodec.productFees(profileVersion),contentCodec.drawingBom(visible.get("DRAWING_BOM")),
          contentCodec.manufacturing(visible.get("MANUFACTURING")),contentCodec.packaging(visible.get("PACKAGE")),
          contentCodec.solder(visible.get("SOLDER")),contentCodec.netLoss(visible.get("NET_LOSS")),
          contentCodec.prices(visible.get("PRICE")),null);
      boolean ownDraft=submission==null && modules.stream().anyMatch(module -> readPolicy.ownsDraft(module,actor));
      String state=ownDraft?product.getProductStatus():visible.isEmpty()?"PENDING":
          visible.values().stream().allMatch(version -> "APPROVED".equals(version.getVersionStatus()))?"APPROVED":"SUBMITTED";
      Long latest=visible.values().stream().filter(version -> !"DRAFT".equals(version.getVersionStatus()))
          .map(QuoteTechDataVersion::getId).max(Long::compareTo).orElse(null);
      result.put(product.getId(),new TechnicalDataProductResponse(
          product.getId(),product.getOaFormItemId(),product.getLevelNo(),product.getMaterialNo(),product.getProductName(),
          product.getSourceModel(),product.getSourceSpec(),source.annualVolume(),source.annualVolumeUnit(),
          product.getQuoteNo(),product.getAccountingMonth(),product.getSourceSnapshotJson(),product.getSourceFingerprint(),
          state,ownDraft?product.getCurrentEditVersionId():null,latest,product.getEffectiveVersionId(),
          product.getRowVersion(),profile(product,profileVersion),modules.stream().sorted(Comparator.comparingInt(
              module -> TechnicalDataModuleType.orderOf(module.getModuleType()))).map(this::module).toList(),
          product.getContentSchemaVersion(),supplements));
    }
    return result;
  }

  private String visibleTaskStatus(QuoteTechTask task, List<TechnicalDataProductResponse> products, TechnicalDataActor actor) {
    if ("UNASSIGNED".equals(task.getTaskStatus())) return task.getTaskStatus();
    if (!actor.canViewSupplementOverview()) {
      var own = products.stream().flatMap(product -> product.modules().stream())
          .filter(module -> module.required() && Objects.equals(module.assigneeUserId(), actor.userId()))
          .map(TechnicalDataModuleResponse::moduleStatus).toList();
      if (own.isEmpty()) return task.getTaskStatus();
      if (own.stream().allMatch("APPROVED"::equals)) return "APPROVED";
      if (own.stream().allMatch(state -> Set.of("SUBMITTED", "APPROVED").contains(state))) return "SUBMITTED";
      if (own.contains("FROZEN")) return "PREPARED";
      if (own.contains("RETURNED")) return "PARTIALLY_RETURNED";
      if (own.stream().anyMatch(state -> Set.of("READY", "EDITING").contains(state))) return "IN_PROGRESS";
      return "PENDING";
    }
    if (Set.of("RETURN_PENDING", "PARTIALLY_RETURNED").contains(task.getTaskStatus())) return task.getTaskStatus();
    if (products.stream().allMatch(product -> "APPROVED".equals(product.productStatus()))) return "APPROVED";
    return products.stream().anyMatch(product -> product.latestSubmittedVersionId()!=null)?"SUBMITTED":"PENDING";
  }

  private TechnicalDataProfileResponse profile(
      QuoteTechProduct product, QuoteTechDataVersion version) {
    var source = snapshotFactory.readProfile(product.getSourceSnapshotJson());
    var fees = contentCodec.productFees(version);
    return new TechnicalDataProfileResponse(
        version == null ? null : version.getId(), version == null ? null : version.getVersionNo(),
        version == null ? null : version.getVersionStatus(),
        source.productModel(), version == null ? null : version.getProductProperty(), source.newProduct(),
        fees == null ? null : fees.includesNewToolingMouldCertificationFee(),
        fees == null ? null : TechnicalDataProductFeeRules.display(fees.unitToolingFee()),
        fees == null ? null : TechnicalDataProductFeeRules.display(fees.unitMouldFee()),
        fees == null ? null : TechnicalDataProductFeeRules.display(fees.unitCertificationFee()),
        product.getRowVersion(), version == null ? null : version.getRowVersion(),
        version == null ? product.getUpdatedAt() : version.getUpdatedAt());
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
        module.getRowVersion(), module.getSourceAvailability(),
        module.getSourceReference(), module.getSourceCheckedAt(), module.getAssigneeUserId(), module.getAssigneeName());
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

  private record Command(
      String requestId, String accountingMonth, Long assigneeUserId,
      LocalDateTime dueAt, List<Long> itemIds) {}


}
