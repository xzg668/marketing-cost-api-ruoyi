package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageItemRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageSaveRequest;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.TechnicalDataPackageReferenceMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataPackageApplicationServiceImpl
    implements TechnicalDataPackageApplicationService {
  private static final Set<String> EDITABLE_TASK_STATUSES = Set.of(
      "PENDING", "IN_PROGRESS", "PARTIALLY_RETURNED");
  private static final Set<String> UNITS = Set.of(
      "只", "套", "片", "张", "个", "箱", "米", "kg");
  private static final Map<String, String> PRICE_BASIS_LABELS = Map.of(
      "HISTORICAL_PRICE", "历史价格",
      "SUPPLIER_QUOTE", "供应商报价",
      "PENDING_INQUIRY", "待询价");

  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final TechnicalDataPackageReferenceMapper referenceMapper;
  private final TechnicalDataSourceSnapshotFactory snapshotFactory;
  private final ObjectMapper objectMapper;

  public TechnicalDataPackageApplicationServiceImpl(
      QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository,
      TechnicalDataPackageReferenceMapper referenceMapper,
      TechnicalDataSourceSnapshotFactory snapshotFactory,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.taskRepository = taskRepository;
    this.referenceMapper = referenceMapper;
    this.snapshotFactory = snapshotFactory;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataPackageResponse get(Long productId, TechnicalDataActor actor) {
    ReadContext context = readContext(productId, actor);
    Long displayVersionId = displayVersionId(context.product(), context.module());
    QuoteTechDataVersion draft = displayVersionId == null
        ? null : repository.findVersion(displayVersionId).orElse(null);
    List<QuoteTechPackageItem> items = draft == null
        ? List.of() : repository.findPackageItems(draft.getId());
    return response(context, draft, items);
  }

  private Long displayVersionId(QuoteTechProduct product, QuoteTechModule module) {
    if (module.getCurrentVersionId() != null) return module.getCurrentVersionId();
    if (product.getCurrentEditVersionId() != null) return product.getCurrentEditVersionId();
    if (product.getLatestSubmittedVersionId() != null) return product.getLatestSubmittedVersionId();
    return product.getEffectiveVersionId();
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataPackageReferenceResponse references(
      Long productId, String keyword, TechnicalDataActor actor) {
    ReadContext context = readContext(productId, actor);
    String query = trim(keyword, 255, "keyword");
    List<TechnicalDataPackageReferenceResponse.Candidate> candidates = referenceMapper
        .selectApprovedSources(
            context.product().getId(), context.task().getBusinessUnitType(),
            context.task().getApplicableOrgCode(), context.product().getAccountingMonth(),
            query, null)
        .stream()
        .filter(row -> StringUtils.hasText(row.getContentFingerprint()))
        .map(this::candidate)
        .toList();
    return new TechnicalDataPackageReferenceResponse(
        context.product().getId(), query, candidates.size(), candidates);
  }

  @Override
  @Transactional
  public TechnicalDataPackageResponse applyReference(
      Long productId,
      TechnicalDataPackageReferenceRequest request,
      TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    Long sourceVersionId = positive(request.getSourceVersionId(), "sourceVersionId");
    int expectedVersion = expected(request.getExpectedVersion());
    WriteContext context = writeContext(productId, expectedVersion, actor);
    TechnicalDataPackageReferenceRow source = referenceMapper.selectApprovedSources(
            context.product().getId(), context.task().getBusinessUnitType(),
            context.task().getApplicableOrgCode(), context.product().getAccountingMonth(),
            null, sourceVersionId)
        .stream().findFirst()
        .orElseThrow(() -> invalid("包装参照来源不存在、已失效、跨组织或没有有效明细"));
    if (!StringUtils.hasText(source.getContentFingerprint())) {
      throw invalid("包装参照来源缺少提交内容指纹");
    }
    List<QuoteTechPackageItem> sourceItems = repository.findPackageItems(sourceVersionId);
    if (sourceItems.isEmpty()) throw invalid("包装参照来源没有可复制明细");

    QuoteTechDataVersion draft = ensureDraft(context, actor);
    replaceItems(draft.getId(), copySourceItems(source, sourceItems));
    String snapshotJson = json(Map.of(
        "sourceType", "TECHNICAL_VERSION",
        "sourceProductId", source.getSourceProductId(),
        "sourceVersionId", source.getSourceVersionId(),
        "sourceVersionNo", source.getSourceVersionNo(),
        "materialNo", source.getMaterialNo(),
        "productModel", nullToEmpty(source.getProductModel()),
        "validFromMonth", source.getValidFromMonth(),
        "contentFingerprint", source.getContentFingerprint(),
        "items", sourceItems.stream().map(this::snapshotItem).toList()));
    readyModule(
        context.module(), draft.getId(), "REFERENCE", "TECHNICAL_VERSION",
        String.valueOf(source.getSourceProductId()), "V" + source.getSourceVersionNo(),
        source.getContentFingerprint(), snapshotJson, sourceItems.size());
    finishWrite(context, draft, actor);
    return response(readContext(productId, actor), draft,
        repository.findPackageItems(draft.getId()));
  }

  @Override
  @Transactional
  public TechnicalDataPackageResponse save(
      Long productId, TechnicalDataPackageSaveRequest request, TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    int expectedVersion = expected(request.getExpectedVersion());
    List<QuoteTechPackageItem> items = normalizeItems(request.getItems());
    WriteContext context = writeContext(productId, expectedVersion, actor);
    QuoteTechDataVersion draft = ensureDraft(context, actor);
    replaceItems(draft.getId(), items);
    readyModule(context.module(), draft.getId(), "MANUAL", null, null, null, null, null,
        items.size());
    finishWrite(context, draft, actor);
    return response(readContext(productId, actor), draft,
        repository.findPackageItems(draft.getId()));
  }

  @Override
  @Transactional
  public TechnicalDataPackageResponse delete(
      Long productId, Long itemId, Integer expectedVersionValue, TechnicalDataActor actor) {
    requireActor(actor, true);
    Long targetItemId = positive(itemId, "itemId");
    WriteContext context = writeContext(productId, expected(expectedVersionValue), actor);
    QuoteTechDataVersion draft = requireCurrentDraft(context.product());
    if (repository.deletePackageItemIfDraft(draft.getId(), targetItemId) != 1) {
      throw invalid("包装明细不存在或不属于当前草稿");
    }
    List<QuoteTechPackageItem> remaining = repository.findPackageItems(draft.getId());
    if (remaining.isEmpty()) pendingModule(context.module(), draft.getId());
    else readyModule(context.module(), draft.getId(), "MANUAL", null, null, null, null, null,
        remaining.size());
    finishWrite(context, draft, actor);
    return response(readContext(productId, actor), draft, remaining);
  }

  private ReadContext readContext(Long productId, TechnicalDataActor actor) {
    requireActor(actor, false);
    Long id = positive(productId, "productId");
    QuoteTechProduct product = repository.findProduct(id)
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "技术资料产品不存在"));
    QuoteTechTask task = repository.findTask(product.getTaskId())
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireAccess(task, product, actor, false);
    QuoteTechModule module = taskRepository.findModules(product.getId()).stream()
        .filter(item -> "PACKAGE".equals(item.getModuleType()))
        .findFirst().orElseThrow(() -> conflict("产品缺少PACKAGE模块"));
    return new ReadContext(task, product, module);
  }

  private WriteContext writeContext(
      Long productId, int expectedVersion, TechnicalDataActor actor) {
    Long id = positive(productId, "productId");
    QuoteTechProduct product = repository.lockProduct(id)
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "技术资料产品不存在"));
    QuoteTechTask task = repository.lockTask(product.getTaskId())
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireAccess(task, product, actor, true);
    if (!Objects.equals(product.getRowVersion(), expectedVersion)) {
      throw conflict("数据已被其他会话修改；当前版本=" + product.getRowVersion());
    }
    QuoteTechModule module = repository.lockModules(product.getId()).stream()
        .filter(item -> "PACKAGE".equals(item.getModuleType()))
        .findFirst().orElseThrow(() -> conflict("产品缺少PACKAGE模块"));
    if (!Integer.valueOf(1).equals(module.getRequiredFlag())) {
      throw invalid("当前产品无需补充包装信息");
    }
    requireReturnedModuleWritable(task, module);
    return new WriteContext(task, product, module, expectedVersion);
  }

  private void requireAccess(
      QuoteTechTask task, QuoteTechProduct product, TechnicalDataActor actor, boolean write) {
    if (!actor.canAccessTask(task.getId())) {
      throw forbidden("短时访问会话不允许跨任务操作");
    }
    if (!Objects.equals(task.getActiveFlag(), 1) || !Objects.equals(product.getActiveFlag(), 1)) {
      throw forbidden("历史技术资料只能查看");
    }
    if (!actor.admin() && !Objects.equals(task.getAssigneeUserId(), actor.userId())) {
      if (write || !actor.reviewer()
          || !Objects.equals(task.getReviewerUserId(), actor.userId())) {
        throw forbidden("只能访问本人负责或本人审核的技术资料产品");
      }
    }
    if (write && !EDITABLE_TASK_STATUSES.contains(task.getTaskStatus())) {
      throw conflict("任务状态为" + task.getTaskStatus() + "，当前不可修改");
    }
  }

  private void requireReturnedModuleWritable(QuoteTechTask task, QuoteTechModule module) {
    if ("PARTIALLY_RETURNED".equals(task.getTaskStatus())
        && !Set.of("RETURNED", "EDITING").contains(module.getModuleStatus())) {
      throw conflict("包装模块本轮未退回，继续展示V1且禁止修改");
    }
  }

  private QuoteTechDataVersion ensureDraft(WriteContext context, TechnicalDataActor actor) {
    if (context.product().getCurrentEditVersionId() != null) {
      return requireCurrentDraft(context.product());
    }
    if (context.product().getLatestSubmittedVersionId() != null) {
      throw conflict("产品已有提交历史，请先从历史版本复制下一草稿");
    }
    TechnicalDataSourceSnapshotFactory.SourceProfile profile =
        snapshotFactory.readProfile(context.product().getSourceSnapshotJson());
    QuoteTechDataVersion draft = new QuoteTechDataVersion();
    draft.setProductId(context.product().getId());
    draft.setVersionNo(repository.maxVersionNo(context.product().getId()) + 1);
    draft.setVersionStatus(QuoteTechDataVersion.STATUS_DRAFT);
    draft.setProductModel(profile.productModel());
    draft.setProductProperty(profile.productProperty());
    draft.setNewProductFlag(profile.newProduct() ? 1 : 0);
    draft.setPackageTotalAmount(BigDecimal.ZERO);
    draft.setAuxiliaryTotalAmount(BigDecimal.ZERO);
    draft.setSalaryTotalAmount(BigDecimal.ZERO);
    draft.setRowVersion(0);
    draft.setCreatedBy(actor.userId());
    draft.setUpdatedBy(actor.userId());
    draft.setCreatedAt(now());
    draft.setUpdatedAt(now());
    repository.insertVersion(draft);
    context.product().setCurrentEditVersionId(draft.getId());
    return draft;
  }

  private QuoteTechDataVersion requireCurrentDraft(QuoteTechProduct product) {
    if (product.getCurrentEditVersionId() == null) throw invalid("产品尚未创建当前草稿");
    QuoteTechDataVersion draft = repository.lockVersion(product.getCurrentEditVersionId())
        .orElseThrow(() -> conflict("产品当前草稿不存在"));
    if (!Objects.equals(draft.getProductId(), product.getId())
        || !QuoteTechDataVersion.STATUS_DRAFT.equals(draft.getVersionStatus())) {
      throw conflict("产品当前版本不是可编辑草稿");
    }
    return draft;
  }

  private void replaceItems(Long draftId, List<QuoteTechPackageItem> items) {
    repository.deleteAllPackageItemsIfDraft(draftId);
    if (items.isEmpty()
        || repository.insertPackageItemsIfDraft(draftId, items) != items.size()) {
      throw conflict("包装明细批量保存失败");
    }
  }

  private void finishWrite(
      WriteContext context, QuoteTechDataVersion draft, TechnicalDataActor actor) {
    LocalDateTime changedAt = now();
    int draftVersion = draft.getRowVersion();
    draft.setPackageTotalAmount(BigDecimal.ZERO);
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draftVersion, changedAt) != 1) {
      throw conflict("包装草稿已被其他会话修改");
    }
    draft.setRowVersion(draftVersion + 1);
    if (repository.updateModule(
        context.module(), context.module().getRowVersion(), changedAt) != 1) {
      throw conflict("包装模块已被其他会话修改");
    }
    QuoteTechProduct product = context.product();
    product.setCurrentEditVersionId(draft.getId());
    product.setProductStatus("EDITING");
    if (repository.updateProductPointers(
        product, context.expectedVersion(), changedAt) != 1) {
      throw conflict("产品已被其他会话修改");
    }
    product.setRowVersion(context.expectedVersion() + 1);
    if ("PENDING".equals(context.task().getTaskStatus())) {
      repository.markTaskInProgress(context.task().getId(), actor.userId(), changedAt);
    }
  }

  private void readyModule(
      QuoteTechModule module,
      Long draftId,
      String entryMode,
      String sourceType,
      String sourceId,
      String sourceVersion,
      String sourceFingerprint,
      String sourceSnapshot,
      int itemCount) {
    module.setEntryMode(entryMode);
    module.setModuleStatus("READY");
    module.setCurrentVersionId(draftId);
    module.setReferenceSourceType(sourceType);
    module.setReferenceSourceId(sourceId);
    module.setReferenceSourceVersion(sourceVersion);
    module.setReferenceFingerprint(sourceFingerprint);
    module.setReferenceSnapshotJson(sourceSnapshot);
    module.setReferencedAt("REFERENCE".equals(entryMode) ? now() : null);
    module.setLastValidationCode("PACKAGE_COMPLETE");
    module.setLastValidationMessage(("REFERENCE".equals(entryMode) ? "已参照" : "已录入")
        + itemCount + "项包装明细");
  }

  private void pendingModule(QuoteTechModule module, Long draftId) {
    module.setEntryMode("NONE");
    module.setModuleStatus("PENDING");
    module.setCurrentVersionId(draftId);
    module.setReferenceSourceType(null);
    module.setReferenceSourceId(null);
    module.setReferenceSourceVersion(null);
    module.setReferenceFingerprint(null);
    module.setReferenceSnapshotJson(null);
    module.setReferencedAt(null);
    module.setLastValidationCode("PACKAGE_EMPTY");
    module.setLastValidationMessage("包装明细为空");
  }

  private List<QuoteTechPackageItem> normalizeItems(
      List<TechnicalDataPackageItemRequest> requested) {
    List<TechnicalDataPackageItemRequest> values = requested == null ? List.of() : requested;
    if (values.size() > 500) throw invalid("包装明细最多500项");
    List<QuoteTechPackageItem> result = new ArrayList<>();
    Set<String> materialNos = new HashSet<>();
    for (TechnicalDataPackageItemRequest value : values) {
      if (value == null) continue;
      if (!value.getUnknownFields().isEmpty()) throw invalid("包装明细包含未知字段");
      if (blank(value)) continue;
      int lineNo = result.size() + 1;
      String materialNo = required(value.getComponentMaterialNo(), 64, "第" + lineNo + "行组件料号");
      if (!materialNos.add(materialNo.toUpperCase())) {
        throw invalid("包装组件料号重复：" + materialNo);
      }
      String name = required(value.getComponentName(), 255, "第" + lineNo + "行名称");
      String spec = trim(value.getComponentSpec(), 255, "第" + lineNo + "行规格");
      BigDecimal quantity = quantity(value.getQuantity(), lineNo);
      String unit = required(value.getUnit(), 32, "第" + lineNo + "行单位");
      if (!UNITS.contains(unit)) throw invalid("第" + lineNo + "行单位不在允许范围：" + unit);
      String priceBasis = required(value.getPriceBasisType(), 64, "第" + lineNo + "行价格依据");
      if (!PRICE_BASIS_LABELS.containsKey(priceBasis)) {
        throw invalid("第" + lineNo + "行价格依据无效");
      }
      QuoteTechPackageItem item = new QuoteTechPackageItem();
      item.setLineNo(lineNo);
      item.setSortSeq(lineNo);
      item.setComponentMaterialNo(materialNo);
      item.setComponentName(name);
      item.setComponentSpec(spec);
      item.setQuantity(quantity);
      item.setOriginalUnit(unit);
      item.setStandardQuantity(quantity);
      item.setStandardUnit(unit);
      item.setConversionFactor(BigDecimal.ONE);
      item.setPriceBasisType(priceBasis);
      item.setRemark(trim(value.getRemark(), 1000, "第" + lineNo + "行备注"));
      result.add(item);
    }
    if (result.isEmpty()) throw invalid("请至少录入一条完整包装明细");
    return result;
  }

  private boolean blank(TechnicalDataPackageItemRequest item) {
    return !StringUtils.hasText(item.getComponentMaterialNo())
        && !StringUtils.hasText(item.getComponentName())
        && !StringUtils.hasText(item.getComponentSpec())
        && item.getQuantity() == null
        && !StringUtils.hasText(item.getUnit())
        && !StringUtils.hasText(item.getPriceBasisType())
        && !StringUtils.hasText(item.getRemark());
  }

  private BigDecimal quantity(BigDecimal value, int lineNo) {
    if (value == null || value.signum() <= 0) throw invalid("第" + lineNo + "行用量必须大于0");
    if (value.scale() > 8 || value.precision() - value.scale() > 12) {
      throw invalid("第" + lineNo + "行用量最多12位整数和8位小数");
    }
    return value.setScale(8);
  }

  private List<QuoteTechPackageItem> copySourceItems(
      TechnicalDataPackageReferenceRow source, List<QuoteTechPackageItem> values) {
    List<QuoteTechPackageItem> result = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      QuoteTechPackageItem value = values.get(index);
      QuoteTechPackageItem copy = new QuoteTechPackageItem();
      copy.setLineNo(index + 1);
      copy.setSortSeq(index + 1);
      copy.setComponentMaterialNo(value.getComponentMaterialNo());
      copy.setComponentName(value.getComponentName());
      copy.setComponentSpec(value.getComponentSpec());
      copy.setQuantity(value.getQuantity());
      copy.setOriginalUnit(value.getOriginalUnit());
      copy.setStandardQuantity(value.getStandardQuantity());
      copy.setStandardUnit(value.getStandardUnit());
      copy.setConversionFactor(value.getConversionFactor());
      copy.setPriceBasisType(value.getPriceBasisType());
      copy.setReferenceUnitPrice(value.getReferenceUnitPrice());
      copy.setAmount(value.getAmount());
      copy.setSourceReferenceId(String.valueOf(source.getSourceVersionId()));
      copy.setSourceReferenceVersion("V" + source.getSourceVersionNo());
      copy.setSourceSnapshotJson(json(snapshotItem(value)));
      copy.setRemark(value.getRemark());
      result.add(copy);
    }
    return result;
  }

  private TechnicalDataPackageReferenceResponse.Candidate candidate(
      TechnicalDataPackageReferenceRow row) {
    List<TechnicalDataPackageResponse.Item> items = repository
        .findPackageItems(row.getSourceVersionId()).stream().map(this::item).toList();
    return new TechnicalDataPackageReferenceResponse.Candidate(
        row.getSourceProductId(), row.getSourceVersionId(), row.getSourceVersionNo(),
        row.getMaterialNo(), row.getProductName(), row.getProductModel(),
        row.getValidFromMonth(), null, "TECHNICAL_VERSION", row.getContentFingerprint(),
        items.size(), items);
  }

  private TechnicalDataPackageResponse response(
      ReadContext context, QuoteTechDataVersion draft, List<QuoteTechPackageItem> items) {
    String productModel = draft == null
        ? snapshotFactory.readProfile(context.product().getSourceSnapshotJson()).productModel()
        : draft.getProductModel();
    return new TechnicalDataPackageResponse(
        context.task().getId(), context.product().getId(), context.product().getMaterialNo(),
        context.product().getProductName(), productModel, context.product().getAccountingMonth(),
        draft == null ? null : draft.getId(), draft == null ? null : draft.getVersionNo(),
        draft == null ? null : draft.getVersionStatus(), context.product().getRowVersion(),
        draft == null ? null : draft.getRowVersion(), context.module().getModuleStatus(),
        context.module().getEntryMode(), context.module().getReferenceSourceType(),
        context.module().getReferenceSourceId(), context.module().getReferenceSourceVersion(),
        items.size(), items.stream().map(this::item).toList());
  }

  private TechnicalDataPackageResponse.Item item(QuoteTechPackageItem value) {
    return new TechnicalDataPackageResponse.Item(
        value.getId(), value.getLineNo(), value.getComponentMaterialNo(),
        value.getComponentName(), value.getComponentSpec(), value.getQuantity(),
        value.getOriginalUnit(), value.getPriceBasisType(),
        PRICE_BASIS_LABELS.getOrDefault(value.getPriceBasisType(), value.getPriceBasisType()),
        value.getRemark());
  }

  private Map<String, Object> snapshotItem(QuoteTechPackageItem value) {
    return Map.ofEntries(
        Map.entry("lineNo", value.getLineNo()),
        Map.entry("componentMaterialNo", value.getComponentMaterialNo()),
        Map.entry("componentName", value.getComponentName()),
        Map.entry("componentSpec", nullToEmpty(value.getComponentSpec())),
        Map.entry("quantity", value.getQuantity()),
        Map.entry("unit", value.getOriginalUnit()),
        Map.entry("priceBasisType", value.getPriceBasisType()),
        Map.entry("remark", nullToEmpty(value.getRemark())));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw conflict("包装参照快照生成失败");
    }
  }

  private void requireActor(TechnicalDataActor actor, boolean write) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw forbidden("当前登录用户无效");
    }
    if (write ? !actor.canEdit() : !actor.canReadTasks()) {
      throw forbidden(write ? "当前用户无权编辑包装信息" : "当前用户无权查看包装信息");
    }
  }

  private int expected(Integer value) {
    if (value == null || value < 0) throw invalid("expectedVersion必须大于等于0");
    return value;
  }

  private Long positive(Long value, String field) {
    if (value == null || value <= 0) throw invalid(field + "必须大于0");
    return value;
  }

  private String required(String value, int max, String field) {
    String result = trim(value, max, field);
    if (!StringUtils.hasText(result)) throw invalid(field + "不能为空");
    return result;
  }

  private String trim(String value, int max, String field) {
    if (!StringUtils.hasText(value)) return null;
    String result = value.trim();
    if (result.length() > max) throw invalid(field + "长度不能超过" + max);
    return result;
  }

  private String nullToEmpty(String value) { return value == null ? "" : value; }

  private LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }

  private TechnicalDataTaskException invalid(String message) {
    return error(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }

  private TechnicalDataTaskException forbidden(String message) {
    return error(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }

  private TechnicalDataTaskException conflict(String message) {
    return error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }

  private TechnicalDataTaskException error(
      TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }

  private record ReadContext(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private record WriteContext(
      QuoteTechTask task,
      QuoteTechProduct product,
      QuoteTechModule module,
      int expectedVersion) {}
}
