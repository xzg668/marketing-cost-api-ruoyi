package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryItemRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalarySaveRequest;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.TechnicalDataSalaryReferenceMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataSalaryApplicationServiceImpl
    implements TechnicalDataSalaryApplicationService {
  private static final String TECHNICAL_VERSION = "TECHNICAL_VERSION";
  private static final String CMS_EFFECTIVE = "CMS_EFFECTIVE";
  private static final String HOURS_PER_PIECE = "小时/件";
  private static final String MINUTES_PER_PIECE = "分钟/件";
  private static final String YUAN_PER_HOUR = "元/小时";
  private static final String YUAN_PER_MINUTE = "元/分钟";
  private static final Set<String> EDITABLE_TASK_STATUSES = Set.of(
      "PENDING", "IN_PROGRESS", "PARTIALLY_RETURNED");
  private static final Set<String> LABOR_TYPES = Set.of("DIRECT", "INDIRECT");
  private static final Map<String, String> LABOR_TYPE_LABELS = Map.of(
      "DIRECT", "直接人工", "INDIRECT", "间接人工");

  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final TechnicalDataSalaryReferenceMapper referenceMapper;
  private final CmsCostSourceEffectiveMapper cmsMapper;
  private final TechnicalDataSourceSnapshotFactory snapshotFactory;
  private final ObjectMapper objectMapper;

  public TechnicalDataSalaryApplicationServiceImpl(
      QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository,
      TechnicalDataSalaryReferenceMapper referenceMapper,
      CmsCostSourceEffectiveMapper cmsMapper,
      TechnicalDataSourceSnapshotFactory snapshotFactory,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.taskRepository = taskRepository;
    this.referenceMapper = referenceMapper;
    this.cmsMapper = cmsMapper;
    this.snapshotFactory = snapshotFactory;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataSalaryResponse get(Long productId, TechnicalDataActor actor) {
    ReadContext context = readContext(productId, actor);
    Long displayVersionId = displayVersionId(context.product(), context.module());
    QuoteTechDataVersion draft = displayVersionId == null
        ? null : repository.findVersion(displayVersionId).orElse(null);
    List<QuoteTechSalaryItem> items = draft == null
        ? List.of() : repository.findSalaryItems(draft.getId());
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
  public TechnicalDataSalaryReferenceResponse references(
      Long productId, String keyword, TechnicalDataActor actor) {
    ReadContext context = readContext(productId, actor);
    String query = trim(keyword, 255, "keyword");
    List<TechnicalDataSalaryReferenceResponse.Candidate> candidates = new ArrayList<>();
    referenceMapper.selectApprovedSources(
            context.product().getId(), context.task().getBusinessUnitType(),
            context.task().getApplicableOrgCode(), context.product().getAccountingMonth(),
            query, null)
        .stream().map(this::historyCandidate).forEach(candidates::add);
    cmsCandidate(context, query).ifPresent(candidates::add);
    return new TechnicalDataSalaryReferenceResponse(
        context.product().getId(), query, candidates.size(), candidates);
  }

  @Override
  @Transactional
  public TechnicalDataSalaryResponse applyReference(
      Long productId,
      TechnicalDataSalaryReferenceRequest request,
      TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    String sourceType = required(request.getSourceType(), 32, "sourceType");
    String sourceId = required(request.getSourceId(), 128, "sourceId");
    WriteContext context = writeContext(productId, expected(request.getExpectedVersion()), actor);
    if (sameReferenceRetry(context, sourceType, sourceId)) {
      QuoteTechDataVersion draft = requireCurrentDraft(context.product());
      return response(readContext(productId, actor), draft, repository.findSalaryItems(draft.getId()));
    }
    ReferenceSelection source = switch (sourceType) {
      case TECHNICAL_VERSION -> historySelection(context, sourceId);
      case CMS_EFFECTIVE -> cmsSelection(context, sourceId);
      default -> throw invalid("工资参照来源类型无效");
    };
    QuoteTechDataVersion draft = ensureDraft(context, actor);
    replaceItems(draft.getId(), source.items());
    readyModule(context.module(), draft.getId(), "REFERENCE", source.type(), source.id(),
        source.version(), source.fingerprint(), source.snapshotJson(), source.items().size());
    finishWrite(context, draft, total(source.items()), actor);
    return response(readContext(productId, actor), draft, repository.findSalaryItems(draft.getId()));
  }

  @Override
  @Transactional
  public TechnicalDataSalaryResponse save(
      Long productId, TechnicalDataSalarySaveRequest request, TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    List<QuoteTechSalaryItem> items = normalizeItems(request.getItems());
    WriteContext context = writeContext(productId, expected(request.getExpectedVersion()), actor);
    if (sameManualRetry(context, items)) {
      QuoteTechDataVersion draft = requireCurrentDraft(context.product());
      return response(readContext(productId, actor), draft, repository.findSalaryItems(draft.getId()));
    }
    QuoteTechDataVersion draft = ensureDraft(context, actor);
    replaceItems(draft.getId(), items);
    readyModule(context.module(), draft.getId(), "MANUAL", null, null, null, null, null,
        items.size());
    finishWrite(context, draft, total(items), actor);
    return response(readContext(productId, actor), draft, repository.findSalaryItems(draft.getId()));
  }

  @Override
  @Transactional
  public TechnicalDataSalaryResponse delete(
      Long productId, Long itemId, Integer expectedVersionValue, TechnicalDataActor actor) {
    requireActor(actor, true);
    WriteContext context = writeContext(productId, expected(expectedVersionValue), actor);
    QuoteTechDataVersion draft = requireCurrentDraft(context.product());
    if (repository.deleteSalaryItemIfDraft(draft.getId(), positive(itemId, "itemId")) != 1) {
      throw invalid("工资明细不存在或不属于当前草稿");
    }
    List<QuoteTechSalaryItem> remaining = repository.findSalaryItems(draft.getId());
    if (remaining.isEmpty()) pendingModule(context.module(), draft.getId());
    else readyModule(context.module(), draft.getId(), "MANUAL", null, null, null, null, null,
        remaining.size());
    finishWrite(context, draft, total(remaining), actor);
    return response(readContext(productId, actor), draft, remaining);
  }

  private ReadContext readContext(Long productId, TechnicalDataActor actor) {
    requireActor(actor, false);
    QuoteTechProduct product = repository.findProduct(positive(productId, "productId"))
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "技术资料产品不存在"));
    QuoteTechTask task = repository.findTask(product.getTaskId())
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireAccess(task, product, actor, false);
    QuoteTechModule module = taskRepository.findModules(product.getId()).stream()
        .filter(item -> "SALARY".equals(item.getModuleType())).findFirst()
        .orElseThrow(() -> conflict("产品缺少SALARY模块"));
    return new ReadContext(task, product, module);
  }

  private WriteContext writeContext(
      Long productId, int expectedVersion, TechnicalDataActor actor) {
    QuoteTechProduct product = repository.lockProduct(positive(productId, "productId"))
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "技术资料产品不存在"));
    QuoteTechTask task = repository.lockTask(product.getTaskId())
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireAccess(task, product, actor, true);
    QuoteTechModule module = repository.lockModules(product.getId()).stream()
        .filter(item -> "SALARY".equals(item.getModuleType())).findFirst()
        .orElseThrow(() -> conflict("产品缺少SALARY模块"));
    if (!Integer.valueOf(1).equals(module.getRequiredFlag())) {
      throw invalid("当前产品无需补充工资信息");
    }
    requireReturnedModuleWritable(task, module);
    return new WriteContext(task, product, module, expectedVersion,
        !Objects.equals(product.getRowVersion(), expectedVersion));
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
      throw conflict("工资模块本轮未退回，继续展示V1且禁止修改");
    }
  }

  private boolean sameManualRetry(WriteContext context, List<QuoteTechSalaryItem> requested) {
    if (!context.versionMismatch()) return false;
    if (!"MANUAL".equals(context.module().getEntryMode())
        || context.product().getCurrentEditVersionId() == null) {
      throw conflict("数据已被其他会话修改；当前版本=" + context.product().getRowVersion());
    }
    List<QuoteTechSalaryItem> stored =
        repository.findSalaryItems(context.product().getCurrentEditVersionId());
    if (!sameItems(stored, requested)) {
      throw conflict("数据已被其他会话修改；当前版本=" + context.product().getRowVersion());
    }
    return true;
  }

  private boolean sameReferenceRetry(WriteContext context, String sourceType, String sourceId) {
    if (!context.versionMismatch()) return false;
    if (!"REFERENCE".equals(context.module().getEntryMode())
        || !Objects.equals(sourceType, context.module().getReferenceSourceType())
        || !Objects.equals(sourceId, context.module().getReferenceSourceId())) {
      throw conflict("数据已被其他会话修改；当前版本=" + context.product().getRowVersion());
    }
    return true;
  }

  private boolean sameItems(List<QuoteTechSalaryItem> left, List<QuoteTechSalaryItem> right) {
    if (left.size() != right.size()) return false;
    for (int index = 0; index < left.size(); index++) {
      if (!Objects.equals(snapshotItem(left.get(index)), snapshotItem(right.get(index)))) return false;
    }
    return true;
  }

  private QuoteTechDataVersion ensureDraft(WriteContext context, TechnicalDataActor actor) {
    if (context.versionMismatch()) {
      throw conflict("数据已被其他会话修改；当前版本=" + context.product().getRowVersion());
    }
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

  private void replaceItems(Long draftId, List<QuoteTechSalaryItem> items) {
    repository.deleteAllSalaryItemsIfDraft(draftId);
    if (items.isEmpty() || repository.insertSalaryItemsIfDraft(draftId, items) != items.size()) {
      throw conflict("工资明细批量保存失败");
    }
  }

  private void finishWrite(
      WriteContext context, QuoteTechDataVersion draft, BigDecimal total, TechnicalDataActor actor) {
    LocalDateTime changedAt = now();
    int draftVersion = draft.getRowVersion();
    draft.setSalaryTotalAmount(total);
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draftVersion, changedAt) != 1) {
      throw conflict("工资草稿已被其他会话修改");
    }
    draft.setRowVersion(draftVersion + 1);
    if (repository.updateModule(context.module(), context.module().getRowVersion(), changedAt) != 1) {
      throw conflict("工资模块已被其他会话修改");
    }
    QuoteTechProduct product = context.product();
    product.setCurrentEditVersionId(draft.getId());
    product.setProductStatus("EDITING");
    if (repository.updateProductPointers(product, context.expectedVersion(), changedAt) != 1) {
      throw conflict("产品已被其他会话修改");
    }
    product.setRowVersion(context.expectedVersion() + 1);
    if ("PENDING".equals(context.task().getTaskStatus())) {
      repository.markTaskInProgress(context.task().getId(), actor.userId(), changedAt);
    }
  }

  private void readyModule(
      QuoteTechModule module, Long draftId, String entryMode, String sourceType,
      String sourceId, String sourceVersion, String fingerprint, String snapshot, int itemCount) {
    module.setEntryMode(entryMode);
    module.setModuleStatus("READY");
    module.setCurrentVersionId(draftId);
    module.setReferenceSourceType(sourceType);
    module.setReferenceSourceId(sourceId);
    module.setReferenceSourceVersion(sourceVersion);
    module.setReferenceFingerprint(fingerprint);
    module.setReferenceSnapshotJson(snapshot);
    module.setReferencedAt("REFERENCE".equals(entryMode) ? now() : null);
    module.setLastValidationCode("SALARY_COMPLETE");
    module.setLastValidationMessage(("REFERENCE".equals(entryMode) ? "已参照" : "已录入")
        + itemCount + "项工资明细");
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
    module.setLastValidationCode("SALARY_EMPTY");
    module.setLastValidationMessage("工资明细为空");
  }

  private List<QuoteTechSalaryItem> normalizeItems(List<TechnicalDataSalaryItemRequest> requested) {
    List<TechnicalDataSalaryItemRequest> values = requested == null ? List.of() : requested;
    if (values.size() > 500) throw invalid("工资明细最多500项");
    List<QuoteTechSalaryItem> result = new ArrayList<>();
    Set<String> processKeys = new HashSet<>();
    for (TechnicalDataSalaryItemRequest value : values) {
      if (value == null) continue;
      if (!value.getUnknownFields().isEmpty()) throw invalid("工资明细包含未知字段");
      if (blank(value)) continue;
      int lineNo = result.size() + 1;
      String processCode = required(value.getProcessCode(), 64, "第" + lineNo + "行工序编码");
      String processName = required(value.getProcessName(), 255, "第" + lineNo + "行工序名称");
      String laborType = required(value.getLaborType(), 64, "第" + lineNo + "行人工类型");
      if (!LABOR_TYPES.contains(laborType)) throw invalid("第" + lineNo + "行人工类型无效");
      if (!processKeys.add(processCode.toUpperCase() + "|" + laborType)) {
        throw invalid("工序和人工类型重复：" + processCode + "/" + LABOR_TYPE_LABELS.get(laborType));
      }
      BigDecimal workingHours = positiveDecimal(value.getWorkingHours(), lineNo, "标准工时", 12, 8);
      String timeUnit = required(value.getTimeUnit(), 32, "第" + lineNo + "行工时单位");
      BigDecimal standardHours = standardHours(workingHours, timeUnit, lineNo);
      BigDecimal timeFactor = HOURS_PER_PIECE.equals(timeUnit)
          ? BigDecimal.ONE : BigDecimal.ONE.divide(new BigDecimal("60"), 8, RoundingMode.HALF_UP);
      BigDecimal wageRate = positiveDecimal(value.getWageRate(), lineNo, "工资率", 12, 8);
      String rateUnit = required(value.getRateUnit(), 32, "第" + lineNo + "行计价单位");
      BigDecimal hourlyRate = hourlyRate(wageRate, rateUnit, lineNo);
      BigDecimal coefficient = positiveCoefficient(value.getPersonCoefficient(), lineNo);
      BigDecimal amount = calculateAmount(standardHours, hourlyRate, coefficient, lineNo);

      QuoteTechSalaryItem item = new QuoteTechSalaryItem();
      item.setLineNo(lineNo);
      item.setSortSeq(lineNo);
      item.setProcessCode(processCode);
      item.setProcessName(processName);
      item.setLaborType(laborType);
      item.setWorkingHours(workingHours.setScale(8));
      item.setOriginalTimeUnit(timeUnit);
      item.setStandardHours(standardHours);
      item.setStandardTimeUnit("HOUR");
      item.setConversionFactor(timeFactor.setScale(8));
      item.setWageRate(wageRate.setScale(8));
      item.setRateUnit(rateUnit);
      item.setHourlyRate(hourlyRate);
      item.setPersonCoefficient(coefficient);
      item.setAmount(amount);
      item.setRemark(trim(value.getRemark(), 1000, "第" + lineNo + "行备注"));
      result.add(item);
    }
    if (result.isEmpty()) throw invalid("请至少录入一条完整工资明细");
    return result;
  }

  private BigDecimal standardHours(BigDecimal value, String unit, int lineNo) {
    BigDecimal result;
    if (HOURS_PER_PIECE.equals(unit)) result = value;
    else if (MINUTES_PER_PIECE.equals(unit)) {
      result = value.divide(new BigDecimal("60"), 8, RoundingMode.HALF_UP);
    } else throw invalid("第" + lineNo + "行工时单位无效");
    result = result.setScale(8, RoundingMode.HALF_UP);
    if (result.signum() <= 0) throw invalid("第" + lineNo + "行换算后工时精度不足");
    return result;
  }

  private BigDecimal hourlyRate(BigDecimal value, String unit, int lineNo) {
    BigDecimal result;
    if (YUAN_PER_HOUR.equals(unit)) result = value;
    else if (YUAN_PER_MINUTE.equals(unit)) result = value.multiply(new BigDecimal("60"));
    else throw invalid("第" + lineNo + "行工资计价单位无效");
    if (result.precision() - result.scale() > 12) throw invalid("第" + lineNo + "行小时工资率超出范围");
    return result.setScale(8, RoundingMode.HALF_UP);
  }

  private BigDecimal positiveCoefficient(BigDecimal value, int lineNo) {
    BigDecimal result = value == null ? BigDecimal.ONE : value;
    if (result.signum() <= 0 || result.scale() > 8
        || result.precision() - result.scale() > 12) {
      throw invalid("第" + lineNo + "行人数/系数必须大于0且最多12位整数和8位小数");
    }
    return result.setScale(8);
  }

  private BigDecimal calculateAmount(
      BigDecimal standardHours, BigDecimal hourlyRate, BigDecimal coefficient, int lineNo) {
    BigDecimal amount = standardHours.multiply(hourlyRate).multiply(coefficient);
    if (amount.precision() - amount.scale() > 12) throw invalid("第" + lineNo + "行工资金额超出范围");
    return amount.setScale(8, RoundingMode.HALF_UP);
  }

  private boolean blank(TechnicalDataSalaryItemRequest item) {
    return !StringUtils.hasText(item.getProcessCode())
        && !StringUtils.hasText(item.getProcessName())
        && !StringUtils.hasText(item.getLaborType()) && item.getWorkingHours() == null
        && !StringUtils.hasText(item.getTimeUnit()) && item.getWageRate() == null
        && !StringUtils.hasText(item.getRateUnit()) && item.getPersonCoefficient() == null
        && !StringUtils.hasText(item.getRemark());
  }

  private ReferenceSelection historySelection(WriteContext context, String sourceId) {
    Long versionId = parsePositive(sourceId, "sourceId");
    TechnicalDataSalaryHistoryRow row = referenceMapper.selectApprovedSources(
            context.product().getId(), context.task().getBusinessUnitType(),
            context.task().getApplicableOrgCode(), context.product().getAccountingMonth(),
            null, versionId).stream().findFirst()
        .orElseThrow(() -> invalid("工资参照来源不存在、已失效、跨组织或缺少关键字段"));
    List<QuoteTechSalaryItem> sourceItems = repository.findSalaryItems(versionId);
    List<QuoteTechSalaryItem> copied = copyHistoryItems(row, sourceItems);
    String snapshot = json(Map.of(
        "sourceType", TECHNICAL_VERSION, "sourceProductId", row.getSourceProductId(),
        "sourceVersionId", row.getSourceVersionId(), "sourceVersionNo", row.getSourceVersionNo(),
        "materialNo", row.getMaterialNo(), "validFromMonth", row.getValidFromMonth(),
        "contentFingerprint", row.getContentFingerprint(),
        "items", sourceItems.stream().map(this::snapshotItem).toList()));
    return new ReferenceSelection(TECHNICAL_VERSION, sourceId, "V" + row.getSourceVersionNo(),
        row.getContentFingerprint(), snapshot, copied);
  }

  private ReferenceSelection cmsSelection(WriteContext context, String sourceId) {
    String expectedId = cmsSourceId(context.product());
    if (!expectedId.equals(sourceId)) throw invalid("CMS工资参照来源不属于当前产品和年度");
    List<CmsCostSourceEffective> rows = validCmsRows(context);
    if (rows.isEmpty()) throw invalid("CMS工资参照来源不存在、已失效或缺少关键字段");
    List<QuoteTechSalaryItem> items = cmsItems(rows);
    String fingerprint = cmsFingerprint(rows);
    String version = cmsVersion(rows);
    String snapshot = json(Map.of(
        "sourceType", CMS_EFFECTIVE, "sourceId", sourceId, "sourceVersion", version,
        "parentCode", context.product().getMaterialNo(), "contentFingerprint", fingerprint,
        "items", items.stream().map(this::snapshotItem).toList()));
    return new ReferenceSelection(CMS_EFFECTIVE, sourceId, version, fingerprint, snapshot, items);
  }

  private java.util.Optional<TechnicalDataSalaryReferenceResponse.Candidate> cmsCandidate(
      ReadContext context, String keyword) {
    List<CmsCostSourceEffective> rows = validCmsRows(context);
    if (rows.isEmpty()) return java.util.Optional.empty();
    if (StringUtils.hasText(keyword) && !containsIgnoreCase(context.product().getMaterialNo(), keyword)
        && !containsIgnoreCase(context.product().getProductName(), keyword)
        && rows.stream().noneMatch(row -> containsIgnoreCase(row.getSourceType(), keyword)
            || containsIgnoreCase(row.getSubjectName(), keyword))) {
      return java.util.Optional.empty();
    }
    List<QuoteTechSalaryItem> values = cmsItems(rows);
    return java.util.Optional.of(new TechnicalDataSalaryReferenceResponse.Candidate(
        CMS_EFFECTIVE, cmsSourceId(context.product()), cmsVersion(rows), null, null,
        context.product().getMaterialNo(), context.product().getProductName(),
        snapshotFactory.readProfile(context.product().getSourceSnapshotJson()).productModel(),
        rows.stream().map(CmsCostSourceEffective::getPeriod).min(String::compareTo).orElse(null),
        null, cmsFingerprint(rows), total(values), values.size(),
        values.stream().map(this::item).toList()));
  }

  private List<CmsCostSourceEffective> validCmsRows(ReadContext context) {
    int year = Integer.parseInt(context.product().getAccountingMonth().substring(0, 4));
    List<CmsCostSourceEffective> rows = cmsMapper.selectList(
        new QueryWrapper<CmsCostSourceEffective>()
            .eq("cost_year", year)
            .in("source_type", List.of("SALARY_DIRECT", "SALARY_INDIRECT"))
            .eq("parent_code", context.product().getMaterialNo())
            .eq("business_unit_type", context.task().getBusinessUnitType())
            .le("period", context.product().getAccountingMonth())
            .orderByAsc("source_type").orderByAsc("id"));
    if (rows.stream().anyMatch(row -> row.getId() == null
        || !("SALARY_DIRECT".equals(row.getSourceType())
            || "SALARY_INDIRECT".equals(row.getSourceType()))
        || !StringUtils.hasText(row.getPeriod())
        || row.getAmountYuan() == null || row.getAmountYuan().signum() <= 0)) {
      return List.of();
    }
    return rows;
  }

  private List<CmsCostSourceEffective> validCmsRows(WriteContext context) {
    return validCmsRows(new ReadContext(context.task(), context.product(), context.module()));
  }

  private List<QuoteTechSalaryItem> cmsItems(List<CmsCostSourceEffective> rows) {
    List<QuoteTechSalaryItem> result = new ArrayList<>();
    for (int index = 0; index < rows.size(); index++) {
      CmsCostSourceEffective row = rows.get(index);
      boolean direct = "SALARY_DIRECT".equals(row.getSourceType());
      BigDecimal amount = row.getAmountYuan().setScale(8, RoundingMode.HALF_UP);
      QuoteTechSalaryItem item = new QuoteTechSalaryItem();
      item.setLineNo(index + 1);
      item.setSortSeq(index + 1);
      item.setProcessCode(direct ? "CMS-DIRECT" : "CMS-INDIRECT");
      item.setProcessName(direct ? "CMS直接人工工资" : "CMS间接人工工资");
      item.setLaborType(direct ? "DIRECT" : "INDIRECT");
      item.setWorkingHours(BigDecimal.ONE.setScale(8));
      item.setOriginalTimeUnit(HOURS_PER_PIECE);
      item.setStandardHours(BigDecimal.ONE.setScale(8));
      item.setStandardTimeUnit("HOUR");
      item.setConversionFactor(BigDecimal.ONE.setScale(8));
      item.setWageRate(amount);
      item.setRateUnit(YUAN_PER_HOUR);
      item.setHourlyRate(amount);
      item.setPersonCoefficient(BigDecimal.ONE.setScale(8));
      item.setAmount(amount);
      item.setSourceReferenceId(String.valueOf(row.getId()));
      item.setSourceReferenceVersion(row.getPeriod());
      item.setSourceSnapshotJson(json(Map.ofEntries(
          Map.entry("id", row.getId()), Map.entry("sourceType", row.getSourceType()),
          Map.entry("period", row.getPeriod()),
          Map.entry("subjectCode", nullToEmpty(row.getSubjectCode())),
          Map.entry("subjectName", nullToEmpty(row.getSubjectName())),
          Map.entry("amountYuan", row.getAmountYuan()),
          Map.entry("sourceRowIds", nullToEmpty(row.getSourceRowIds())))));
      item.setRemark("参照CMS有效工资来源；每件金额按1小时标准工时折算展示");
      result.add(item);
    }
    return result;
  }

  private List<QuoteTechSalaryItem> copyHistoryItems(
      TechnicalDataSalaryHistoryRow source, List<QuoteTechSalaryItem> values) {
    List<QuoteTechSalaryItem> result = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      QuoteTechSalaryItem value = values.get(index);
      QuoteTechSalaryItem copy = copy(value, index + 1);
      copy.setSourceReferenceId(String.valueOf(source.getSourceVersionId()));
      copy.setSourceReferenceVersion("V" + source.getSourceVersionNo());
      copy.setSourceSnapshotJson(json(snapshotItem(value)));
      result.add(copy);
    }
    return result;
  }

  private QuoteTechSalaryItem copy(QuoteTechSalaryItem value, int lineNo) {
    QuoteTechSalaryItem copy = new QuoteTechSalaryItem();
    copy.setLineNo(lineNo);
    copy.setSortSeq(lineNo);
    copy.setProcessCode(value.getProcessCode());
    copy.setProcessName(value.getProcessName());
    copy.setLaborType(value.getLaborType());
    copy.setWorkingHours(value.getWorkingHours());
    copy.setOriginalTimeUnit(value.getOriginalTimeUnit());
    copy.setStandardHours(value.getStandardHours());
    copy.setStandardTimeUnit(value.getStandardTimeUnit());
    copy.setConversionFactor(value.getConversionFactor());
    copy.setWageRate(value.getWageRate());
    copy.setRateUnit(value.getRateUnit());
    copy.setHourlyRate(value.getHourlyRate());
    copy.setPersonCoefficient(value.getPersonCoefficient());
    copy.setAmount(value.getAmount());
    copy.setRemark(value.getRemark());
    return copy;
  }

  private TechnicalDataSalaryReferenceResponse.Candidate historyCandidate(
      TechnicalDataSalaryHistoryRow row) {
    List<QuoteTechSalaryItem> values = repository.findSalaryItems(row.getSourceVersionId());
    return new TechnicalDataSalaryReferenceResponse.Candidate(
        TECHNICAL_VERSION, String.valueOf(row.getSourceVersionId()),
        "V" + row.getSourceVersionNo(), row.getSourceProductId(), row.getSourceVersionId(),
        row.getMaterialNo(), row.getProductName(), row.getProductModel(), row.getValidFromMonth(),
        null, row.getContentFingerprint(), total(values), values.size(),
        values.stream().map(this::item).toList());
  }

  private TechnicalDataSalaryResponse response(
      ReadContext context, QuoteTechDataVersion draft, List<QuoteTechSalaryItem> values) {
    String productModel = draft == null
        ? snapshotFactory.readProfile(context.product().getSourceSnapshotJson()).productModel()
        : draft.getProductModel();
    return new TechnicalDataSalaryResponse(
        context.task().getId(), context.product().getId(), context.product().getMaterialNo(),
        context.product().getProductName(), productModel, context.product().getAccountingMonth(),
        draft == null ? null : draft.getId(), draft == null ? null : draft.getVersionNo(),
        draft == null ? null : draft.getVersionStatus(), context.product().getRowVersion(),
        draft == null ? null : draft.getRowVersion(), context.module().getModuleStatus(),
        context.module().getEntryMode(), context.module().getReferenceSourceType(),
        context.module().getReferenceSourceId(), context.module().getReferenceSourceVersion(),
        total(values), values.size(), values.stream().map(this::item).toList());
  }

  private TechnicalDataSalaryResponse.Item item(QuoteTechSalaryItem value) {
    return new TechnicalDataSalaryResponse.Item(
        value.getId(), value.getLineNo(), value.getProcessCode(), value.getProcessName(),
        value.getLaborType(), LABOR_TYPE_LABELS.getOrDefault(
            value.getLaborType(), value.getLaborType()), value.getWorkingHours(),
        value.getOriginalTimeUnit(), value.getStandardHours(), value.getStandardTimeUnit(),
        value.getConversionFactor(), value.getWageRate(), value.getRateUnit(),
        value.getHourlyRate(), value.getPersonCoefficient(), value.getAmount(),
        calculationExpression(value), value.getRemark());
  }

  private String calculationExpression(QuoteTechSalaryItem value) {
    return plain(value.getWorkingHours()) + " " + value.getOriginalTimeUnit()
        + " × " + plain(value.getWageRate()) + " " + value.getRateUnit()
        + " × " + plain(value.getPersonCoefficient()) + "人/系数 = "
        + money(value.getAmount()) + " 元/件";
  }

  private Map<String, Object> snapshotItem(QuoteTechSalaryItem value) {
    return Map.ofEntries(
        Map.entry("lineNo", value.getLineNo()), Map.entry("processCode", value.getProcessCode()),
        Map.entry("processName", value.getProcessName()),
        Map.entry("laborType", value.getLaborType()),
        Map.entry("workingHours", value.getWorkingHours()),
        Map.entry("timeUnit", value.getOriginalTimeUnit()),
        Map.entry("standardHours", value.getStandardHours()),
        Map.entry("standardTimeUnit", value.getStandardTimeUnit()),
        Map.entry("timeConversionFactor", value.getConversionFactor()),
        Map.entry("wageRate", value.getWageRate()), Map.entry("rateUnit", value.getRateUnit()),
        Map.entry("hourlyRate", value.getHourlyRate()),
        Map.entry("personCoefficient", value.getPersonCoefficient()),
        Map.entry("amount", value.getAmount()),
        Map.entry("remark", nullToEmpty(value.getRemark())));
  }

  private BigDecimal total(List<QuoteTechSalaryItem> values) {
    return values.stream().map(QuoteTechSalaryItem::getAmount).filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(8, RoundingMode.HALF_UP);
  }

  private String cmsSourceId(QuoteTechProduct product) {
    return "CMS:SALARY:" + product.getAccountingMonth().substring(0, 4)
        + ":" + product.getMaterialNo();
  }

  private String cmsVersion(List<CmsCostSourceEffective> rows) {
    return "CMS-" + rows.stream().map(CmsCostSourceEffective::getPeriod)
        .max(String::compareTo).orElse("UNKNOWN");
  }

  private String cmsFingerprint(List<CmsCostSourceEffective> rows) {
    return sha256(json(rows.stream().map(row -> Map.ofEntries(
        Map.entry("id", row.getId()), Map.entry("sourceType", row.getSourceType()),
        Map.entry("period", row.getPeriod()),
        Map.entry("subjectCode", nullToEmpty(row.getSubjectCode())),
        Map.entry("subjectName", nullToEmpty(row.getSubjectName())),
        Map.entry("amountYuan", row.getAmountYuan()),
        Map.entry("sourceRowIds", nullToEmpty(row.getSourceRowIds())))).toList()));
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前运行环境不支持SHA-256", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw conflict("工资参照快照生成失败");
    }
  }

  private BigDecimal positiveDecimal(
      BigDecimal value, int lineNo, String field, int integerDigits, int scale) {
    if (value == null || value.signum() <= 0) throw invalid("第" + lineNo + "行" + field + "必须大于0");
    if (value.scale() > scale || value.precision() - value.scale() > integerDigits) {
      throw invalid("第" + lineNo + "行" + field + "最多" + integerDigits + "位整数和" + scale + "位小数");
    }
    return value;
  }

  private void requireActor(TechnicalDataActor actor, boolean write) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) throw forbidden("当前登录用户无效");
    if (write ? !actor.canEdit() : !actor.canReadTasks()) {
      throw forbidden(write ? "当前用户无权编辑工资信息" : "当前用户无权查看工资信息");
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

  private Long parsePositive(String value, String field) {
    try { return positive(Long.valueOf(value), field); }
    catch (NumberFormatException exception) { throw invalid(field + "必须为正整数"); }
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

  private boolean containsIgnoreCase(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword.toLowerCase());
  }

  private String plain(BigDecimal value) {
    if (value == null) return "0";
    return value.stripTrailingZeros().toPlainString();
  }

  private String money(BigDecimal value) {
    if (value == null) return "0.00";
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private String nullToEmpty(String value) { return value == null ? "" : value; }

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

  private record ReadContext(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private record WriteContext(
      QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module,
      int expectedVersion, boolean versionMismatch) {}
  private record ReferenceSelection(
      String type, String id, String version, String fingerprint,
      String snapshotJson, List<QuoteTechSalaryItem> items) {}
}
