package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryItemRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliarySaveRequest;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.TechnicalDataAuxiliaryReferenceMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataAuxiliaryApplicationServiceImpl
    implements TechnicalDataAuxiliaryApplicationService {
  private static final String TECHNICAL_VERSION = "TECHNICAL_VERSION";
  private static final String CMS_EFFECTIVE = "CMS_EFFECTIVE";
  private static final Set<String> EDITABLE_TASK_STATUSES = Set.of(
      "PENDING", "IN_PROGRESS", "PARTIALLY_RETURNED");
  private static final Set<String> PRICING_METHODS = Set.of("UNIT_PRICE", "FIXED_AMOUNT");
  private static final Map<String, String> PRICING_METHOD_LABELS = Map.of(
      "UNIT_PRICE", "按用量计价", "FIXED_AMOUNT", "固定每件");
  private static final Map<String, Conversion> CONVERSIONS = Map.of(
      "kg|元/kg", new Conversion("kg", BigDecimal.ONE),
      "g|元/kg", new Conversion("kg", new BigDecimal("0.001")),
      "g|元/g", new Conversion("g", BigDecimal.ONE),
      "kg|元/g", new Conversion("g", new BigDecimal("1000")),
      "件|元/件", new Conversion("件", BigDecimal.ONE));

  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final TechnicalDataAuxiliaryReferenceMapper referenceMapper;
  private final CmsCostSourceEffectiveMapper cmsMapper;
  private final TechnicalDataSourceSnapshotFactory snapshotFactory;
  private final ObjectMapper objectMapper;

  public TechnicalDataAuxiliaryApplicationServiceImpl(
      QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository,
      TechnicalDataAuxiliaryReferenceMapper referenceMapper,
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
  public TechnicalDataAuxiliaryResponse get(Long productId, TechnicalDataActor actor) {
    ReadContext context = readContext(productId, actor);
    Long displayVersionId = displayVersionId(context.product(), context.module());
    QuoteTechDataVersion draft = displayVersionId == null
        ? null : repository.findVersion(displayVersionId).orElse(null);
    List<QuoteTechAuxItem> items = draft == null ? List.of() : repository.findAuxItems(draft.getId());
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
  public TechnicalDataAuxiliaryReferenceResponse references(
      Long productId, String keyword, TechnicalDataActor actor) {
    ReadContext context = readContext(productId, actor);
    String query = trim(keyword, 255, "keyword");
    List<TechnicalDataAuxiliaryReferenceResponse.Candidate> candidates = new ArrayList<>();
    referenceMapper.selectApprovedSources(
            context.product().getId(), context.task().getBusinessUnitType(),
            context.task().getApplicableOrgCode(), context.product().getAccountingMonth(),
            query, null)
        .stream().map(this::historyCandidate).forEach(candidates::add);
    cmsCandidate(context, query).ifPresent(candidates::add);
    return new TechnicalDataAuxiliaryReferenceResponse(
        context.product().getId(), query, candidates.size(), candidates);
  }

  @Override
  @Transactional
  public TechnicalDataAuxiliaryResponse applyReference(
      Long productId,
      TechnicalDataAuxiliaryReferenceRequest request,
      TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    String sourceType = required(request.getSourceType(), 32, "sourceType");
    String sourceId = required(request.getSourceId(), 128, "sourceId");
    int expectedVersion = expected(request.getExpectedVersion());
    WriteContext context = writeContext(productId, expectedVersion, actor);
    if (sameReferenceRetry(context, sourceType, sourceId)) {
      QuoteTechDataVersion draft = requireCurrentDraft(context.product());
      return response(readContext(productId, actor), draft, repository.findAuxItems(draft.getId()));
    }

    ReferenceSelection source = switch (sourceType) {
      case TECHNICAL_VERSION -> historySelection(context, sourceId);
      case CMS_EFFECTIVE -> cmsSelection(context, sourceId);
      default -> throw invalid("辅料参照来源类型无效");
    };
    QuoteTechDataVersion draft = ensureDraft(context, actor);
    replaceItems(draft.getId(), source.items());
    readyModule(context.module(), draft.getId(), "REFERENCE", source.type(), source.id(),
        source.version(), source.fingerprint(), source.snapshotJson(), source.items().size());
    finishWrite(context, draft, total(source.items()), actor);
    return response(readContext(productId, actor), draft, repository.findAuxItems(draft.getId()));
  }

  @Override
  @Transactional
  public TechnicalDataAuxiliaryResponse save(
      Long productId, TechnicalDataAuxiliarySaveRequest request, TechnicalDataActor actor) {
    requireActor(actor, true);
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) throw invalid("请求包含未知字段");
    List<QuoteTechAuxItem> items = normalizeItems(request.getItems());
    int expectedVersion = expected(request.getExpectedVersion());
    WriteContext context = writeContext(productId, expectedVersion, actor);
    if (sameManualRetry(context, items)) {
      QuoteTechDataVersion draft = requireCurrentDraft(context.product());
      return response(readContext(productId, actor), draft, repository.findAuxItems(draft.getId()));
    }
    QuoteTechDataVersion draft = ensureDraft(context, actor);
    replaceItems(draft.getId(), items);
    readyModule(context.module(), draft.getId(), "MANUAL", null, null, null, null, null,
        items.size());
    finishWrite(context, draft, total(items), actor);
    return response(readContext(productId, actor), draft, repository.findAuxItems(draft.getId()));
  }

  @Override
  @Transactional
  public TechnicalDataAuxiliaryResponse delete(
      Long productId, Long itemId, Integer expectedVersionValue, TechnicalDataActor actor) {
    requireActor(actor, true);
    WriteContext context = writeContext(productId, expected(expectedVersionValue), actor);
    QuoteTechDataVersion draft = requireCurrentDraft(context.product());
    if (repository.deleteAuxItemIfDraft(draft.getId(), positive(itemId, "itemId")) != 1) {
      throw invalid("辅料明细不存在或不属于当前草稿");
    }
    List<QuoteTechAuxItem> remaining = repository.findAuxItems(draft.getId());
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
        .filter(item -> "AUXILIARY".equals(item.getModuleType())).findFirst()
        .orElseThrow(() -> conflict("产品缺少AUXILIARY模块"));
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
        .filter(item -> "AUXILIARY".equals(item.getModuleType())).findFirst()
        .orElseThrow(() -> conflict("产品缺少AUXILIARY模块"));
    if (!Integer.valueOf(1).equals(module.getRequiredFlag())) {
      throw invalid("当前产品无需补充辅料信息");
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
      throw conflict("辅料模块本轮未退回，继续展示V1且禁止修改");
    }
  }

  private boolean sameManualRetry(WriteContext context, List<QuoteTechAuxItem> requested) {
    if (!context.versionMismatch()) return false;
    if (!"MANUAL".equals(context.module().getEntryMode())
        || context.product().getCurrentEditVersionId() == null) {
      throw conflict("数据已被其他会话修改；当前版本=" + context.product().getRowVersion());
    }
    List<QuoteTechAuxItem> stored = repository.findAuxItems(context.product().getCurrentEditVersionId());
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

  private boolean sameItems(List<QuoteTechAuxItem> left, List<QuoteTechAuxItem> right) {
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

  private void replaceItems(Long draftId, List<QuoteTechAuxItem> items) {
    repository.deleteAllAuxItemsIfDraft(draftId);
    if (items.isEmpty() || repository.insertAuxItemsIfDraft(draftId, items) != items.size()) {
      throw conflict("辅料明细批量保存失败");
    }
  }

  private void finishWrite(
      WriteContext context, QuoteTechDataVersion draft, BigDecimal total, TechnicalDataActor actor) {
    LocalDateTime changedAt = now();
    int draftVersion = draft.getRowVersion();
    draft.setAuxiliaryTotalAmount(total);
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draftVersion, changedAt) != 1) {
      throw conflict("辅料草稿已被其他会话修改");
    }
    draft.setRowVersion(draftVersion + 1);
    if (repository.updateModule(context.module(), context.module().getRowVersion(), changedAt) != 1) {
      throw conflict("辅料模块已被其他会话修改");
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
    module.setLastValidationCode("AUXILIARY_COMPLETE");
    module.setLastValidationMessage(("REFERENCE".equals(entryMode) ? "已参照" : "已录入")
        + itemCount + "项辅料明细");
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
    module.setLastValidationCode("AUXILIARY_EMPTY");
    module.setLastValidationMessage("辅料明细为空");
  }

  private List<QuoteTechAuxItem> normalizeItems(List<TechnicalDataAuxiliaryItemRequest> requested) {
    List<TechnicalDataAuxiliaryItemRequest> values = requested == null ? List.of() : requested;
    if (values.size() > 500) throw invalid("辅料明细最多500项");
    List<QuoteTechAuxItem> result = new ArrayList<>();
    Set<String> materialNos = new HashSet<>();
    for (TechnicalDataAuxiliaryItemRequest value : values) {
      if (value == null) continue;
      if (!value.getUnknownFields().isEmpty()) throw invalid("辅料明细包含未知字段");
      if (blank(value)) continue;
      int lineNo = result.size() + 1;
      String materialNo = required(value.getAuxiliaryMaterialNo(), 64, "第" + lineNo + "行辅料料号");
      if (!materialNos.add(materialNo.toUpperCase())) throw invalid("辅料料号重复：" + materialNo);
      String name = required(value.getAuxiliaryName(), 255, "第" + lineNo + "行辅料名称");
      String subjectCode = required(value.getSubjectCode(), 64, "第" + lineNo + "行辅料科目");
      String pricingMethod = required(value.getPricingMethod(), 64, "第" + lineNo + "行计价方式");
      if (!PRICING_METHODS.contains(pricingMethod)) throw invalid("第" + lineNo + "行计价方式无效");
      BigDecimal quantity = positiveDecimal(value.getQuantity(), lineNo, "用量", 12, 8);
      String unit = required(value.getUnit(), 32, "第" + lineNo + "行单位");
      BigDecimal price = positiveDecimal(value.getReferenceUnitPrice(), lineNo, "参考单价", 12, 8);
      String priceUnit = required(value.getPriceUnit(), 32, "第" + lineNo + "行计价单位");
      BigDecimal lossRate = lossRate(value.getLossRate(), lineNo);
      Conversion conversion = CONVERSIONS.get(unit + "|" + priceUnit);
      if (conversion == null) throw invalid("第" + lineNo + "行用量单位与计价单位不兼容");
      BigDecimal standardQuantity = quantity.multiply(conversion.factor())
          .setScale(8, RoundingMode.HALF_UP);
      if (standardQuantity.signum() <= 0) throw invalid("第" + lineNo + "行换算后用量精度不足");
      BigDecimal amount = calculateAmount(standardQuantity, price, lossRate, lineNo);
      QuoteTechAuxItem item = new QuoteTechAuxItem();
      item.setLineNo(lineNo);
      item.setSortSeq(lineNo);
      item.setSubjectCode(subjectCode);
      item.setSubjectName(defaultText(value.getSubjectName(), name, 255, "第" + lineNo + "行科目名称"));
      item.setAuxiliaryMaterialNo(materialNo);
      item.setAuxiliaryName(name);
      item.setAuxiliarySpec(trim(value.getAuxiliarySpec(), 255, "第" + lineNo + "行规格"));
      item.setPricingMethod(pricingMethod);
      item.setQuantity(quantity.setScale(8));
      item.setOriginalUnit(unit);
      item.setStandardQuantity(standardQuantity);
      item.setStandardUnit(conversion.standardUnit());
      item.setConversionFactor(conversion.factor().setScale(8));
      item.setReferenceUnitPrice(price.setScale(8));
      item.setPriceUnit(priceUnit);
      item.setLossRate(lossRate);
      item.setAmount(amount);
      item.setRemark(trim(value.getRemark(), 1000, "第" + lineNo + "行备注"));
      result.add(item);
    }
    if (result.isEmpty()) throw invalid("请至少录入一条完整辅料明细");
    return result;
  }

  private BigDecimal calculateAmount(
      BigDecimal standardQuantity, BigDecimal price, BigDecimal lossRate, int lineNo) {
    BigDecimal amount = standardQuantity.multiply(price).multiply(BigDecimal.ONE.add(lossRate));
    if (amount.precision() - amount.scale() > 12) throw invalid("第" + lineNo + "行辅料金额超出范围");
    return amount.setScale(8, RoundingMode.HALF_UP);
  }

  private boolean blank(TechnicalDataAuxiliaryItemRequest item) {
    return !StringUtils.hasText(item.getSubjectCode()) && !StringUtils.hasText(item.getSubjectName())
        && !StringUtils.hasText(item.getAuxiliaryMaterialNo())
        && !StringUtils.hasText(item.getAuxiliaryName())
        && !StringUtils.hasText(item.getAuxiliarySpec())
        && !StringUtils.hasText(item.getPricingMethod()) && item.getQuantity() == null
        && !StringUtils.hasText(item.getUnit()) && item.getReferenceUnitPrice() == null
        && !StringUtils.hasText(item.getPriceUnit()) && item.getLossRate() == null
        && !StringUtils.hasText(item.getRemark());
  }

  private ReferenceSelection historySelection(WriteContext context, String sourceId) {
    Long versionId = parsePositive(sourceId, "sourceId");
    TechnicalDataAuxiliaryHistoryRow row = referenceMapper.selectApprovedSources(
            context.product().getId(), context.task().getBusinessUnitType(),
            context.task().getApplicableOrgCode(), context.product().getAccountingMonth(),
            null, versionId).stream().findFirst()
        .orElseThrow(() -> invalid("辅料参照来源不存在、已失效、跨组织或缺少关键字段"));
    List<QuoteTechAuxItem> sourceItems = repository.findAuxItems(versionId);
    List<QuoteTechAuxItem> copied = copyHistoryItems(row, sourceItems);
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
    if (!expectedId.equals(sourceId)) throw invalid("CMS辅料参照来源不属于当前产品和年度");
    List<CmsCostSourceEffective> rows = validCmsRows(context);
    if (rows.isEmpty()) throw invalid("CMS辅料参照来源不存在、已失效或缺少关键字段");
    List<QuoteTechAuxItem> items = cmsItems(rows);
    String fingerprint = cmsFingerprint(rows);
    String version = cmsVersion(rows);
    String snapshot = json(Map.of(
        "sourceType", CMS_EFFECTIVE, "sourceId", sourceId, "sourceVersion", version,
        "parentCode", context.product().getMaterialNo(), "contentFingerprint", fingerprint,
        "items", items.stream().map(this::snapshotItem).toList()));
    return new ReferenceSelection(CMS_EFFECTIVE, sourceId, version, fingerprint, snapshot, items);
  }

  private java.util.Optional<TechnicalDataAuxiliaryReferenceResponse.Candidate> cmsCandidate(
      ReadContext context, String keyword) {
    List<CmsCostSourceEffective> rows = validCmsRows(context);
    if (rows.isEmpty()) return java.util.Optional.empty();
    if (StringUtils.hasText(keyword) && !containsIgnoreCase(context.product().getMaterialNo(), keyword)
        && !containsIgnoreCase(context.product().getProductName(), keyword)
        && rows.stream().noneMatch(row -> containsIgnoreCase(row.getSubjectCode(), keyword)
            || containsIgnoreCase(row.getSubjectName(), keyword))) {
      return java.util.Optional.empty();
    }
    List<QuoteTechAuxItem> values = cmsItems(rows);
    return java.util.Optional.of(new TechnicalDataAuxiliaryReferenceResponse.Candidate(
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
            .eq("cost_year", year).eq("source_type", "AUX_SUBJECT")
            .eq("parent_code", context.product().getMaterialNo())
            .eq("business_unit_type", context.task().getBusinessUnitType())
            .le("period", context.product().getAccountingMonth())
            .orderByAsc("subject_code").orderByAsc("id"));
    if (rows.stream().anyMatch(row -> row.getId() == null
        || !StringUtils.hasText(row.getSubjectCode())
        || !StringUtils.hasText(row.getSubjectName())
        || !StringUtils.hasText(row.getPeriod())
        || row.getAmountYuan() == null || row.getAmountYuan().signum() < 0)) {
      return List.of();
    }
    return rows;
  }

  private List<CmsCostSourceEffective> validCmsRows(WriteContext context) {
    return validCmsRows(new ReadContext(context.task(), context.product(), context.module()));
  }

  private List<QuoteTechAuxItem> cmsItems(List<CmsCostSourceEffective> rows) {
    List<QuoteTechAuxItem> result = new ArrayList<>();
    for (int index = 0; index < rows.size(); index++) {
      CmsCostSourceEffective row = rows.get(index);
      QuoteTechAuxItem item = new QuoteTechAuxItem();
      item.setLineNo(index + 1);
      item.setSortSeq(index + 1);
      item.setSubjectCode(row.getSubjectCode().trim());
      item.setSubjectName(row.getSubjectName().trim());
      item.setAuxiliaryMaterialNo(row.getSubjectCode().trim());
      item.setAuxiliaryName(row.getSubjectName().trim());
      item.setAuxiliarySpec("CMS有效辅料科目汇总");
      item.setPricingMethod("FIXED_AMOUNT");
      item.setQuantity(BigDecimal.ONE.setScale(8));
      item.setOriginalUnit("件");
      item.setStandardQuantity(BigDecimal.ONE.setScale(8));
      item.setStandardUnit("件");
      item.setConversionFactor(BigDecimal.ONE.setScale(8));
      item.setReferenceUnitPrice(row.getAmountYuan().setScale(8, RoundingMode.HALF_UP));
      item.setPriceUnit("元/件");
      item.setLossRate(BigDecimal.ZERO.setScale(8));
      item.setAmount(row.getAmountYuan().setScale(8, RoundingMode.HALF_UP));
      item.setSourceReferenceId(String.valueOf(row.getId()));
      item.setSourceReferenceVersion(row.getPeriod());
      item.setSourceSnapshotJson(json(Map.of(
          "id", row.getId(), "period", row.getPeriod(), "subjectCode", row.getSubjectCode(),
          "subjectName", row.getSubjectName(), "amountYuan", row.getAmountYuan(),
          "sourceRowIds", row.getSourceRowIds())));
      item.setRemark("参照CMS有效辅料来源");
      result.add(item);
    }
    return result;
  }

  private List<QuoteTechAuxItem> copyHistoryItems(
      TechnicalDataAuxiliaryHistoryRow source, List<QuoteTechAuxItem> values) {
    List<QuoteTechAuxItem> result = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      QuoteTechAuxItem value = values.get(index);
      QuoteTechAuxItem copy = copy(value, index + 1);
      copy.setSourceReferenceId(String.valueOf(source.getSourceVersionId()));
      copy.setSourceReferenceVersion("V" + source.getSourceVersionNo());
      copy.setSourceSnapshotJson(json(snapshotItem(value)));
      result.add(copy);
    }
    return result;
  }

  private QuoteTechAuxItem copy(QuoteTechAuxItem value, int lineNo) {
    QuoteTechAuxItem copy = new QuoteTechAuxItem();
    copy.setLineNo(lineNo);
    copy.setSortSeq(lineNo);
    copy.setSubjectCode(value.getSubjectCode());
    copy.setSubjectName(value.getSubjectName());
    copy.setAuxiliaryMaterialNo(value.getAuxiliaryMaterialNo());
    copy.setAuxiliaryName(value.getAuxiliaryName());
    copy.setAuxiliarySpec(value.getAuxiliarySpec());
    copy.setPricingMethod(value.getPricingMethod());
    copy.setQuantity(value.getQuantity());
    copy.setOriginalUnit(value.getOriginalUnit());
    copy.setStandardQuantity(value.getStandardQuantity());
    copy.setStandardUnit(value.getStandardUnit());
    copy.setConversionFactor(value.getConversionFactor());
    copy.setReferenceUnitPrice(value.getReferenceUnitPrice());
    copy.setPriceUnit(value.getPriceUnit());
    copy.setLossRate(value.getLossRate());
    copy.setAmount(value.getAmount());
    copy.setRemark(value.getRemark());
    return copy;
  }

  private TechnicalDataAuxiliaryReferenceResponse.Candidate historyCandidate(
      TechnicalDataAuxiliaryHistoryRow row) {
    List<QuoteTechAuxItem> values = repository.findAuxItems(row.getSourceVersionId());
    return new TechnicalDataAuxiliaryReferenceResponse.Candidate(
        TECHNICAL_VERSION, String.valueOf(row.getSourceVersionId()),
        "V" + row.getSourceVersionNo(), row.getSourceProductId(), row.getSourceVersionId(),
        row.getMaterialNo(), row.getProductName(), row.getProductModel(), row.getValidFromMonth(),
        null, row.getContentFingerprint(), total(values), values.size(),
        values.stream().map(this::item).toList());
  }

  private TechnicalDataAuxiliaryResponse response(
      ReadContext context, QuoteTechDataVersion draft, List<QuoteTechAuxItem> values) {
    String productModel = draft == null
        ? snapshotFactory.readProfile(context.product().getSourceSnapshotJson()).productModel()
        : draft.getProductModel();
    return new TechnicalDataAuxiliaryResponse(
        context.task().getId(), context.product().getId(), context.product().getMaterialNo(),
        context.product().getProductName(), productModel, context.product().getAccountingMonth(),
        draft == null ? null : draft.getId(), draft == null ? null : draft.getVersionNo(),
        draft == null ? null : draft.getVersionStatus(), context.product().getRowVersion(),
        draft == null ? null : draft.getRowVersion(), context.module().getModuleStatus(),
        context.module().getEntryMode(), context.module().getReferenceSourceType(),
        context.module().getReferenceSourceId(), context.module().getReferenceSourceVersion(),
        total(values), values.size(), values.stream().map(this::item).toList());
  }

  private TechnicalDataAuxiliaryResponse.Item item(QuoteTechAuxItem value) {
    return new TechnicalDataAuxiliaryResponse.Item(
        value.getId(), value.getLineNo(), value.getSubjectCode(), value.getSubjectName(),
        value.getAuxiliaryMaterialNo(), value.getAuxiliaryName(), value.getAuxiliarySpec(),
        value.getPricingMethod(), PRICING_METHOD_LABELS.getOrDefault(
            value.getPricingMethod(), value.getPricingMethod()), value.getQuantity(),
        value.getOriginalUnit(), value.getStandardQuantity(), value.getStandardUnit(),
        value.getConversionFactor(), value.getReferenceUnitPrice(), value.getPriceUnit(),
        value.getLossRate(), value.getAmount(), value.getRemark());
  }

  private Map<String, Object> snapshotItem(QuoteTechAuxItem value) {
    return Map.ofEntries(
        Map.entry("lineNo", value.getLineNo()), Map.entry("subjectCode", value.getSubjectCode()),
        Map.entry("subjectName", nullToEmpty(value.getSubjectName())),
        Map.entry("auxiliaryMaterialNo", value.getAuxiliaryMaterialNo()),
        Map.entry("auxiliaryName", value.getAuxiliaryName()),
        Map.entry("auxiliarySpec", nullToEmpty(value.getAuxiliarySpec())),
        Map.entry("pricingMethod", value.getPricingMethod()),
        Map.entry("quantity", value.getQuantity()), Map.entry("unit", value.getOriginalUnit()),
        Map.entry("standardQuantity", value.getStandardQuantity()),
        Map.entry("standardUnit", value.getStandardUnit()),
        Map.entry("conversionFactor", value.getConversionFactor()),
        Map.entry("referenceUnitPrice", value.getReferenceUnitPrice()),
        Map.entry("priceUnit", value.getPriceUnit()), Map.entry("lossRate", value.getLossRate()),
        Map.entry("amount", value.getAmount()), Map.entry("remark", nullToEmpty(value.getRemark())));
  }

  private BigDecimal total(List<QuoteTechAuxItem> values) {
    return values.stream().map(QuoteTechAuxItem::getAmount).filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(8, RoundingMode.HALF_UP);
  }

  private String cmsSourceId(QuoteTechProduct product) {
    return "CMS:" + product.getAccountingMonth().substring(0, 4) + ":" + product.getMaterialNo();
  }

  private String cmsVersion(List<CmsCostSourceEffective> rows) {
    return "CMS-" + rows.stream().map(CmsCostSourceEffective::getPeriod)
        .max(String::compareTo).orElse("UNKNOWN");
  }

  private String cmsFingerprint(List<CmsCostSourceEffective> rows) {
    return sha256(json(rows.stream().map(row -> Map.of(
        "id", row.getId(), "period", row.getPeriod(), "subjectCode", row.getSubjectCode(),
        "subjectName", row.getSubjectName(), "amountYuan", row.getAmountYuan(),
        "sourceRowIds", row.getSourceRowIds())).toList()));
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
      throw conflict("辅料参照快照生成失败");
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

  private BigDecimal lossRate(BigDecimal value, int lineNo) {
    BigDecimal result = value == null ? BigDecimal.ZERO : value;
    if (result.scale() > 8 || result.signum() < 0 || result.compareTo(BigDecimal.ONE) > 0) {
      throw invalid("第" + lineNo + "行损耗率必须在0到1之间且最多8位小数");
    }
    return result.setScale(8);
  }

  private void requireActor(TechnicalDataActor actor, boolean write) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) throw forbidden("当前登录用户无效");
    if (write ? !actor.canEdit() : !actor.canReadTasks()) {
      throw forbidden(write ? "当前用户无权编辑辅料信息" : "当前用户无权查看辅料信息");
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

  private String defaultText(String value, String fallback, int max, String field) {
    String result = trim(value, max, field);
    return StringUtils.hasText(result) ? result : fallback;
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

  private record Conversion(String standardUnit, BigDecimal factor) {}
  private record ReadContext(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private record WriteContext(
      QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module,
      int expectedVersion, boolean versionMismatch) {}
  private record ReferenceSelection(
      String type, String id, String version, String fingerprint,
      String snapshotJson, List<QuoteTechAuxItem> items) {}
}
