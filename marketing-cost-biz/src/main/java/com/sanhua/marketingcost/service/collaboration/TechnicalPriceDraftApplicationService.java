package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.collaboration.FormalPriceReferenceSearchResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftCreateRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftSaveRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftValidateRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceGapWorkspaceResponse;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationGapMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 正式价格只读搜索、同一缺口单一草稿及分价格类型校验。 */
@Service
public class TechnicalPriceDraftApplicationService {
  private static final Set<String> EDITABLE = Set.of("EDITING", "VALIDATED", "REJECTED");
  private static final List<String> PRICE_TYPES =
      List.of("FIXED_PURCHASE", "LINKED", "RANGE", "SETTLE_FIXED");

  private final QuoteCollaborationTaskRepository taskRepository;
  private final QuotePriceDraftRepository draftRepository;
  private final QuoteCollaborationGapMapper gapMapper;
  private final FormalPriceReferenceGateway referenceGateway;
  private final CollaborationCurrentPrincipalProvider principalProvider;
  private final BusinessChangeLogMapper changeLogMapper;
  private final ObjectMapper objectMapper;
  private final CollaborationIdempotency idempotency;
  private final FixedPriceDraftValidator fixedPriceValidator;
  private final LinkedPriceDraftValidator linkedPriceValidator;
  private final RangePriceDraftValidator rangePriceValidator;

  public TechnicalPriceDraftApplicationService(
      QuoteCollaborationTaskRepository taskRepository,
      QuotePriceDraftRepository draftRepository,
      QuoteCollaborationGapMapper gapMapper,
      FormalPriceReferenceGateway referenceGateway,
      CollaborationCurrentPrincipalProvider principalProvider,
      BusinessChangeLogMapper changeLogMapper,
      ObjectMapper objectMapper,
      CollaborationIdempotency idempotency,
      FixedPriceDraftValidator fixedPriceValidator,
      LinkedPriceDraftValidator linkedPriceValidator,
      RangePriceDraftValidator rangePriceValidator) {
    this.taskRepository = taskRepository;
    this.draftRepository = draftRepository;
    this.gapMapper = gapMapper;
    this.referenceGateway = referenceGateway;
    this.principalProvider = principalProvider;
    this.changeLogMapper = changeLogMapper;
    this.objectMapper = objectMapper;
    this.idempotency = idempotency;
    this.fixedPriceValidator = fixedPriceValidator;
    this.linkedPriceValidator = linkedPriceValidator;
    this.rangePriceValidator = rangePriceValidator;
  }

  @Transactional(readOnly = true)
  public TechnicalPriceGapWorkspaceResponse workspace(Long taskId) {
    Owned owned = ownedTask(taskId);
    List<QuoteCollaborationGap> gaps = priceGaps(owned);
    Map<Long, QuotePriceDraft> drafts = new HashMap<>();
    for (QuotePriceDraft draft : draftRepository.findByProductTask(owned.task().getId(), owned.scope())) {
      drafts.put(draft.getId(), draft);
    }
    List<TechnicalPriceGapWorkspaceResponse.Item> items = gaps.stream()
        .map(gap -> workspaceItem(gap, drafts.get(gap.getCurrentPriceDraftId())))
        .toList();
    int saved = (int) items.stream().filter(item -> item.draftId() != null).count();
    return new TechnicalPriceGapWorkspaceResponse(
        owned.task().getId(), owned.task().getTaskVersion(), owned.task().getProductCode(),
        owned.task().getProductName(), owned.task().getAccountingMonth(),
        owned.task().getApplicableOrgCode(), items.size(), saved, items);
  }

  @Transactional(readOnly = true)
  public FormalPriceReferenceSearchResponse search(
      Long gapId, String keyword, String priceType) {
    GapOwned owned = ownedGap(gapId, false);
    String query = firstText(keyword, owned.gap().getMaterialCode(),
        owned.gap().getMaterialModel(), owned.gap().getMaterialSpec());
    String type = normalizePriceType(priceType, true);
    List<FormalPriceReference> rows = referenceGateway.search(
        owned.task().getBusinessUnitType(), owned.task().getApplicableOrgCode(),
        owned.task().getAccountingMonth(), query, type);
    List<FormalPriceReferenceSearchResponse.Item> items = rows.stream()
        .map(this::referenceItem).toList();
    return new FormalPriceReferenceSearchResponse(
        query, type, owned.task().getApplicableOrgCode(), items.size(), items);
  }

  @Transactional
  public TechnicalPriceDraftResponse create(
      Long gapId, TechnicalPriceDraftCreateRequest request) {
    GapOwned owned = ownedGap(gapId, true);
    if (owned.gap().getCurrentPriceDraftId() != null) {
      return response(requireDraft(
          owned.gap().getCurrentPriceDraftId(), owned.scope()), owned.scope());
    }
    if (request == null) throw new IllegalArgumentException("价格草稿创建参数不能为空");
    boolean copy = request.referenceSourceId() != null || StringUtils.hasText(request.referenceSourceType());
    String priceType = normalizePriceType(request.priceType(), !copy);
    FormalPriceReference reference = null;
    if (copy) {
      if (!StringUtils.hasText(request.referenceSourceType()) || request.referenceSourceId() == null) {
        throw new IllegalArgumentException("复制价格时参考来源类型和记录ID必须同时填写");
      }
      reference = referenceGateway.findEffective(
          owned.task().getBusinessUnitType(), owned.task().getApplicableOrgCode(),
          owned.task().getAccountingMonth(), request.referenceSourceType(), request.referenceSourceId())
          .orElseThrow(() -> new IllegalArgumentException("参考正式价格不存在、已失效或不在当前组织"));
      priceType = reference.priceType();
    }
    if (priceType == null) {
      throw new IllegalArgumentException("直接填写时请选择价格类型");
    }
    QuotePriceDraft draft = newDraft(owned, priceType, reference);
    draftRepository.saveDraft(draft);
    List<QuotePriceDraftField> fields = reference == null
        ? directFields(draft.getId(), priceType)
        : copiedFields(draft.getId(), reference);
    draftRepository.saveFields(fields);
    int bound = gapMapper.bindCurrentPriceDraft(owned.gap().getId(), draft.getId(),
        owned.scope().businessUnitType(), owned.scope().applicableOrgCode(),
        owned.principal().userId(), owned.principal().userName());
    if (bound != 1) {
      throw new CollaborationOptimisticLockException("价格缺口草稿", owned.gap().getId(), 1);
    }
    return response(draft, owned.scope());
  }

  @Transactional(readOnly = true)
  public TechnicalPriceDraftResponse detail(Long draftId) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuotePriceDraft draft = draftRepository.findById(
        requireId(draftId, "价格草稿ID"), currentScopeForDraft(draftId, principal))
        .orElseThrow(TechnicalPriceDraftApplicationService::notFound);
    GapOwned owned = ownedGap(draft.getGapId(), false);
    if (!Objects.equals(owned.task().getId(), draft.getProductTaskId())) throw notFound();
    return response(draft, owned.scope());
  }

  @Transactional
  public TechnicalPriceDraftResponse save(
      Long draftId, TechnicalPriceDraftSaveRequest request) {
    if (request == null || request.expectedVersion() == null) {
      throw new IllegalArgumentException("expectedVersion不能为空");
    }
    DraftOwned owned = ownedDraft(draftId);
    requireEditable(owned.draft());
    List<QuotePriceDraftField> current = draftRepository.findFields(draftId, owned.scope());
    List<QuotePriceDraftField> fields = mergeFields(
        current, request.fields(), draftId, owned.draft().getPriceType());
    QuotePriceDraft changed = copyMaster(owned.draft());
    changed.setSupplierCode(trim(request.supplierCode()));
    changed.setSupplierName(trim(request.supplierName()));
    changed.setUnit(trim(request.unit()));
    changed.setTaxIncluded(request.taxIncluded());
    changed.setTaxRate(decimal(request.taxRate(), "税率"));
    changed.setEffectiveFrom(request.effectiveFrom());
    changed.setEffectiveTo(request.effectiveTo());
    changed.setUpdatedBy(owned.principal().userId());
    changed.setUpdatedByName(owned.principal().userName());
    changed.setDraftFingerprint(fingerprint(changed, fields));
    QuotePriceDraft saved = draftRepository.updateEditable(
        changed, request.expectedVersion(), owned.scope());
    draftRepository.replaceEditableFields(saved.getId(), fields, owned.scope());
    resetGapValidation(owned, saved);
    return response(saved, owned.scope());
  }

  @Transactional
  public TechnicalPriceDraftResponse validate(
      Long draftId, TechnicalPriceDraftValidateRequest request) {
    if (request == null || request.expectedVersion() == null) {
      throw new IllegalArgumentException("expectedVersion不能为空");
    }
    DraftOwned owned = ownedDraft(draftId);
    requireEditable(owned.draft());
    List<QuotePriceDraftField> fields = draftRepository.findFields(draftId, owned.scope())
        .stream().map(this::cloneValidationField).toList();
    boolean valid;
    String validationMessage;
    List<QuotePriceDraftField> validatedFields;
    if (isFixed(owned.draft().getPriceType())) {
      FixedPriceDraftValidator.Result result = fixedPriceValidator.validate(
          owned.task(), owned.draft(), fields);
      valid = result.valid();
      validationMessage = result.message();
      validatedFields = result.fields();
    } else if ("LINKED".equals(owned.draft().getPriceType())) {
      LinkedPriceDraftValidator.Result result = linkedPriceValidator.validate(
          owned.task(), owned.draft(), fields);
      valid = result.valid();
      validationMessage = result.message();
      validatedFields = result.fields();
    } else if ("RANGE".equals(owned.draft().getPriceType())) {
      RangePriceDraftValidator.Result result = rangePriceValidator.validate(
          owned.task(), owned.draft(), fields);
      valid = result.valid();
      validationMessage = result.message();
      validatedFields = result.fields();
    } else {
      throw new IllegalArgumentException("当前价格类型尚未开放校验：" + owned.draft().getPriceType());
    }
    String validationStatus = valid ? "PASSED" : "FAILED";
    QuotePriceDraft saved = draftRepository.updateValidation(
        draftId, request.expectedVersion(), validationStatus, validationMessage,
        owned.scope(), owned.principal().actor());
    draftRepository.replaceEditableFields(saved.getId(), validatedFields, owned.scope());
    int affected = gapMapper.updatePriceDraftValidationStatus(
        owned.gap().getId(), saved.getId(), valid ? "DRAFT_READY" : "OPEN",
        owned.scope().businessUnitType(), owned.scope().applicableOrgCode(),
        owned.principal().userId(), owned.principal().userName());
    if (affected != 1) {
      throw new CollaborationPersistenceException("同步价格草稿校验状态失败");
    }
    return response(saved, owned.scope());
  }

  @Transactional
  public TechnicalPriceDraftResponse changeReference(
      Long draftId, Integer expectedVersion, TechnicalPriceDraftCreateRequest request) {
    if (expectedVersion == null || request == null
        || !StringUtils.hasText(request.referenceSourceType())
        || request.referenceSourceId() == null) {
      throw new IllegalArgumentException("更换参考必须填写草稿版本、正式来源类型和记录ID");
    }
    DraftOwned owned = ownedDraft(draftId);
    requireEditable(owned.draft());
    FormalPriceReference reference = referenceGateway.findEffective(
        owned.task().getBusinessUnitType(), owned.task().getApplicableOrgCode(),
        owned.task().getAccountingMonth(), request.referenceSourceType(), request.referenceSourceId())
        .orElseThrow(() -> new IllegalArgumentException("新参考正式价格不存在、已失效或不在当前组织"));
    if (!Objects.equals(reference.priceType(), owned.draft().getPriceType())) {
      throw new IllegalArgumentException("更换参考必须与当前草稿价格类型一致");
    }
    List<QuotePriceDraftField> oldFields = draftRepository.findFields(draftId, owned.scope());
    String before = referenceSnapshot(owned.draft(), oldFields);
    QuotePriceDraft changed = copyMaster(owned.draft());
    applyReference(changed, reference);
    changed.setUpdatedBy(owned.principal().userId());
    changed.setUpdatedByName(owned.principal().userName());
    QuotePriceDraft saved = draftRepository.changeReference(changed, expectedVersion, owned.scope());
    List<QuotePriceDraftField> newFields = copiedFields(saved.getId(), reference);
    draftRepository.replaceEditableFields(saved.getId(), newFields, owned.scope());
    resetGapValidation(owned, saved);
    saveReferenceChange(owned, saved, before, referenceSnapshot(saved, newFields));
    return response(saved, owned.scope());
  }

  private Owned ownedTask(Long taskId) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = taskRepository.findMineById(
        requireId(taskId, "产品任务ID"), principal.userId(), currentBusinessUnit())
        .orElseThrow(TechnicalPriceDraftApplicationService::notFound);
    return new Owned(task, new CollaborationScope(
        task.getBusinessUnitType(), task.getApplicableOrgCode()), principal);
  }

  private GapOwned ownedGap(Long gapId, boolean lock) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    String businessUnit = currentBusinessUnit();
    QuoteCollaborationGap candidate = gapMapper.selectById(requireId(gapId, "缺价ID"));
    if (candidate == null) throw notFound();
    QuoteCollaborationProductTask task = taskRepository.findMineById(
        candidate.getProductTaskId(), principal.userId(), businessUnit)
        .orElseThrow(TechnicalPriceDraftApplicationService::notFound);
    CollaborationScope scope = new CollaborationScope(
        task.getBusinessUnitType(), task.getApplicableOrgCode());
    QuoteCollaborationGap gap = lock
        ? gapMapper.selectScopedForUpdateById(candidate.getId(),
            scope.businessUnitType(), scope.applicableOrgCode()) : candidate;
    if (gap == null || !"PRICE".equals(gap.getGapCategory())
        || "OBSOLETE".equals(gap.getGapStatus())) throw notFound();
    return new GapOwned(task, gap, scope, principal);
  }

  private DraftOwned ownedDraft(Long draftId) {
    Long id = requireId(draftId, "价格草稿ID");
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuotePriceDraft unscoped = draftRepositoryCandidate(id);
    GapOwned gap = ownedGap(unscoped.getGapId(), false);
    QuotePriceDraft draft = draftRepository.findById(id, gap.scope())
        .orElseThrow(TechnicalPriceDraftApplicationService::notFound);
    if (!Objects.equals(draft.getProductTaskId(), gap.task().getId())
        || !Objects.equals(gap.gap().getCurrentPriceDraftId(), draft.getId())) throw notFound();
    return new DraftOwned(gap.task(), gap.gap(), draft, gap.scope(), principal);
  }

  private CollaborationScope currentScopeForDraft(Long draftId, CollaborationPrincipal principal) {
    QuotePriceDraft unscoped = draftRepositoryCandidate(draftId);
    GapOwned gap = ownedGap(unscoped.getGapId(), false);
    return gap.scope();
  }

  private QuotePriceDraft draftRepositoryCandidate(Long id) {
    // 只用ID取得关联缺口，随后必须再次经过本人任务和业务组织范围查询。
    List<QuoteCollaborationGap> gaps = gapMapper.selectList(
        Wrappers.<QuoteCollaborationGap>lambdaQuery()
            .eq(QuoteCollaborationGap::getCurrentPriceDraftId, id).last("LIMIT 1"));
    if (gaps.isEmpty()) throw notFound();
    QuoteCollaborationGap gap = gaps.get(0);
    GapOwned owned = ownedGap(gap.getId(), false);
    return draftRepository.findById(id, owned.scope())
        .orElseThrow(TechnicalPriceDraftApplicationService::notFound);
  }

  private List<QuoteCollaborationGap> priceGaps(Owned owned) {
    return taskRepository.findGaps(owned.task().getId(), owned.scope()).stream()
        .filter(gap -> "PRICE".equals(gap.getGapCategory()))
        .filter(gap -> !"OBSOLETE".equals(gap.getGapStatus()))
        .sorted(Comparator.comparing(QuoteCollaborationGap::getId))
        .toList();
  }

  private QuotePriceDraft newDraft(
      GapOwned owned, String priceType, FormalPriceReference reference) {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setProductTaskId(owned.task().getId());
    draft.setGapId(owned.gap().getId());
    draft.setMaterialCode(owned.gap().getMaterialCode());
    draft.setMaterialName(owned.gap().getMaterialName());
    draft.setMaterialSpec(owned.gap().getMaterialSpec());
    draft.setMaterialModel(owned.gap().getMaterialModel());
    draft.setBusinessUnitType(owned.task().getBusinessUnitType());
    draft.setOrgCode(owned.task().getApplicableOrgCode());
    draft.setPriceType(priceType);
    draft.setSourceMode(reference == null ? "DIRECT" : "COPY");
    draft.setTargetSourceType(targetSource(priceType));
    draft.setDraftStatus("EDITING");
    draft.setValidationStatus("NOT_CHECKED");
    draft.setDraftVersion(1);
    draft.setCreatedBy(owned.principal().userId());
    draft.setCreatedByName(owned.principal().userName());
    draft.setUpdatedBy(owned.principal().userId());
    draft.setUpdatedByName(owned.principal().userName());
    if (reference != null) applyReference(draft, reference);
    // A copied record is only a content/template reference.  The target price belongs to the
    // current collaboration accounting month and must not silently inherit the reference month;
    // otherwise the published price route can miss the quotation that triggered this task.
    draft.setEffectiveFrom(YearMonth.parse(owned.task().getAccountingMonth()).atDay(1));
    return draft;
  }

  private static void applyReference(QuotePriceDraft draft, FormalPriceReference reference) {
    draft.setPriceType(reference.priceType());
    draft.setSourceMode("COPY");
    draft.setReferenceSourceType(reference.sourceType());
    draft.setReferenceSourceId(reference.sourceId());
    draft.setReferenceVersionText(reference.versionText());
    draft.setTargetSourceType(targetSource(reference.priceType()));
    draft.setSupplierCode(reference.supplierCode());
    draft.setSupplierName(reference.supplierName());
    draft.setUnit(reference.unit());
    draft.setTaxIncluded(reference.taxIncluded());
    draft.setTaxRate(decimal(reference.taxRate(), "税率"));
    draft.setEffectiveFrom(reference.effectiveFrom());
    draft.setEffectiveTo(reference.effectiveTo());
  }

  private List<QuotePriceDraftField> copiedFields(
      Long draftId, FormalPriceReference reference) {
    return reference.fields().stream().map(field -> field(draftId, field,
        field.value(), field.techInputRequired() ? null : field.value())).toList();
  }

  private List<QuotePriceDraftField> directFields(Long draftId, String priceType) {
    List<FormalPriceReference.Field> templates = switch (priceType) {
      case "FIXED_PURCHASE" -> List.of(
          template("COMMON", "MAIN", "PRICE", "固定单价", "DECIMAL", true, true, 10));
      case "SETTLE_FIXED" -> List.of(
          template("COMMON", "MAIN", "BASE_SETTLE_PRICE", "基础结算价", "DECIMAL", true, true, 10),
          template("COMMON", "MAIN", "MARKUP_RATIO", "加价比例", "DECIMAL", false, true, 20));
      case "LINKED" -> List.of(
          template("FORMULA", "MAIN", "FORMULA_EXPR", "联动公式", "TEXT", true, true, 10));
      case "RANGE" -> List.of(
          template("COMMON", "MAIN", "RANGE_BASIS", "区间依据", "TEXT", true, true, 10),
          template("COMMON", "MAIN", "FACTOR_CODE", "影响因素", "TEXT", false, true, 20),
          template("RANGE_ROW", "ROW-1", "RANGE_LOW", "区间下限", "DECIMAL", true, true, 10),
          template("RANGE_ROW", "ROW-1", "RANGE_HIGH", "区间上限", "DECIMAL", false, true, 20),
          template("RANGE_ROW", "ROW-1", "PRICE_EXCL_TAX", "不含税价", "DECIMAL", false, true, 30),
          template("RANGE_ROW", "ROW-1", "PRICE_INCL_TAX", "含税价", "DECIMAL", false, true, 40));
      default -> throw new IllegalArgumentException("不支持的价格类型：" + priceType);
    };
    return templates.stream().map(template -> field(draftId, template, null, null)).toList();
  }

  private FormalPriceReference.Field template(
      String section, String row, String code, String name, String type,
      boolean required, boolean techInput, int sort) {
    return new FormalPriceReference.Field(
        section, row, code, name, type, null, null, required, techInput, sort);
  }

  private QuotePriceDraftField field(
      Long draftId, FormalPriceReference.Field source, String reference, String target) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(draftId);
    field.setSectionCode(source.sectionCode());
    field.setRowKey(source.rowKey());
    field.setFieldCode(source.fieldCode());
    field.setFieldName(source.fieldName());
    field.setValueType(source.valueType());
    field.setReferenceValueJson(jsonValue(reference));
    field.setTargetValueJson(jsonValue(target));
    field.setUnit(source.unit());
    field.setRequiredFlag(source.required() ? 1 : 0);
    field.setTechInputRequired(source.techInputRequired() ? 1 : 0);
    field.setChangedFlag(!Objects.equals(reference, target) ? 1 : 0);
    field.setValidationStatus("NOT_CHECKED");
    field.setSortSeq(source.sortSeq());
    return field;
  }

  private List<QuotePriceDraftField> mergeFields(
      List<QuotePriceDraftField> current,
      List<TechnicalPriceDraftSaveRequest.FieldValue> requested,
      Long draftId,
      String priceType) {
    if ("RANGE".equals(priceType)) {
      return mergeRangeFields(current, requested, draftId);
    }
    Map<String, String> values = new HashMap<>();
    for (TechnicalPriceDraftSaveRequest.FieldValue value : requested) {
      if (value == null) throw new IllegalArgumentException("草稿字段不能包含空项");
      String key = fieldKey(value.sectionCode(), value.rowKey(), value.fieldCode());
      if (values.putIfAbsent(key, trim(value.value())) != null) {
        throw new IllegalArgumentException("草稿字段重复：" + key);
      }
    }
    Set<String> allowed = new HashSet<>();
    List<QuotePriceDraftField> result = new ArrayList<>();
    for (QuotePriceDraftField existing : current) {
      String key = fieldKey(existing.getSectionCode(), existing.getRowKey(), existing.getFieldCode());
      allowed.add(key);
      QuotePriceDraftField field = cloneField(existing, draftId);
      String target = values.containsKey(key) ? values.get(key) : jsonText(existing.getTargetValueJson());
      field.setTargetValueJson(jsonValue(target));
      field.setChangedFlag(!Objects.equals(
          jsonText(existing.getReferenceValueJson()), target) ? 1 : 0);
      field.setValidationStatus("NOT_CHECKED");
      field.setValidationMessage(null);
      result.add(field);
    }
    if (!allowed.containsAll(values.keySet())) {
      Set<String> unknown = new HashSet<>(values.keySet());
      unknown.removeAll(allowed);
      throw new IllegalArgumentException("存在不属于当前草稿的字段：" + unknown);
    }
    return result;
  }

  private List<QuotePriceDraftField> mergeRangeFields(
      List<QuotePriceDraftField> current,
      List<TechnicalPriceDraftSaveRequest.FieldValue> requested,
      Long draftId) {
    Map<String, QuotePriceDraftField> currentByKey = new HashMap<>();
    for (QuotePriceDraftField field : current) {
      currentByKey.put(fieldKey(field.getSectionCode(), field.getRowKey(), field.getFieldCode()), field);
    }
    Map<String, String> commonValues = new LinkedHashMap<>();
    Map<String, Map<String, String>> rowValues = new LinkedHashMap<>();
    Set<String> rangeCodes = Set.of("RANGE_LOW", "RANGE_HIGH", "PRICE_EXCL_TAX", "PRICE_INCL_TAX");
    for (TechnicalPriceDraftSaveRequest.FieldValue value : requested) {
      if (value == null) throw new IllegalArgumentException("草稿字段不能包含空项");
      String section = required(value.sectionCode(), "字段分区");
      String row = firstText(value.rowKey(), "MAIN");
      String code = required(value.fieldCode(), "字段编码");
      if ("RANGE_ROW".equals(section)) {
        if (!row.matches("[A-Za-z0-9_-]{1,64}")) {
          throw new IllegalArgumentException("区间行标识不合法：" + row);
        }
        if (!rangeCodes.contains(code)) {
          throw new IllegalArgumentException("区间行包含未知字段：" + code);
        }
        Map<String, String> values = rowValues.computeIfAbsent(row, ignored -> new LinkedHashMap<>());
        if (values.putIfAbsent(code, trim(value.value())) != null) {
          throw new IllegalArgumentException("区间字段重复：" + row + "/" + code);
        }
      } else if ("COMMON".equals(section) && "MAIN".equals(row)
          && Set.of("RANGE_BASIS", "FACTOR_CODE").contains(code)) {
        if (commonValues.putIfAbsent(code, trim(value.value())) != null) {
          throw new IllegalArgumentException("区间公共字段重复：" + code);
        }
      } else {
        throw new IllegalArgumentException("存在不属于当前区间价草稿的字段："
            + section + "/" + row + "/" + code);
      }
    }
    if (rowValues.isEmpty()) throw new IllegalArgumentException("区间价至少保留一段区间");

    List<QuotePriceDraftField> result = new ArrayList<>();
    result.add(rangeField(draftId, currentByKey, "COMMON", "MAIN", "RANGE_BASIS",
        "区间依据", "TEXT", null, true, 10,
        firstText(commonValues.get("RANGE_BASIS"), "QTY")));
    result.add(rangeField(draftId, currentByKey, "COMMON", "MAIN", "FACTOR_CODE",
        "影响因素", "TEXT", null, false, 20, commonValues.get("FACTOR_CODE")));
    int index = 0;
    for (Map.Entry<String, Map<String, String>> entry : rowValues.entrySet()) {
      String row = entry.getKey();
      Map<String, String> values = entry.getValue();
      int sort = ++index * 100;
      result.add(rangeField(draftId, currentByKey, "RANGE_ROW", row, "RANGE_LOW",
          "区间下限", "DECIMAL", null, true, sort + 10, values.get("RANGE_LOW")));
      result.add(rangeField(draftId, currentByKey, "RANGE_ROW", row, "RANGE_HIGH",
          "区间上限", "DECIMAL", null, false, sort + 20, values.get("RANGE_HIGH")));
      result.add(rangeField(draftId, currentByKey, "RANGE_ROW", row, "PRICE_EXCL_TAX",
          "不含税价", "DECIMAL", null, false, sort + 30, values.get("PRICE_EXCL_TAX")));
      result.add(rangeField(draftId, currentByKey, "RANGE_ROW", row, "PRICE_INCL_TAX",
          "含税价", "DECIMAL", null, false, sort + 40, values.get("PRICE_INCL_TAX")));
    }
    return result;
  }

  private QuotePriceDraftField rangeField(
      Long draftId, Map<String, QuotePriceDraftField> currentByKey,
      String section, String row, String code, String name, String valueType,
      String unit, boolean required, int sort, String target) {
    QuotePriceDraftField existing = currentByKey.get(fieldKey(section, row, code));
    QuotePriceDraftField field = existing == null ? new QuotePriceDraftField()
        : cloneField(existing, draftId);
    field.setPriceDraftId(draftId);
    field.setSectionCode(section);
    field.setRowKey(row);
    field.setFieldCode(code);
    field.setFieldName(name);
    field.setValueType(valueType);
    field.setUnit(unit);
    field.setRequiredFlag(required ? 1 : 0);
    field.setTechInputRequired(1);
    field.setSortSeq(sort);
    field.setTargetValueJson(jsonValue(target));
    String reference = existing == null ? null : jsonText(existing.getReferenceValueJson());
    field.setChangedFlag(!Objects.equals(reference, target) ? 1 : 0);
    field.setValidationStatus("NOT_CHECKED");
    field.setValidationMessage(null);
    return field;
  }

  private static QuotePriceDraftField cloneField(QuotePriceDraftField source, Long draftId) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(draftId);
    field.setSectionCode(source.getSectionCode());
    field.setRowKey(source.getRowKey());
    field.setFieldCode(source.getFieldCode());
    field.setFieldName(source.getFieldName());
    field.setValueType(source.getValueType());
    field.setReferenceValueJson(source.getReferenceValueJson());
    field.setUnit(source.getUnit());
    field.setRequiredFlag(source.getRequiredFlag());
    field.setTechInputRequired(source.getTechInputRequired());
    field.setSortSeq(source.getSortSeq());
    return field;
  }

  private QuotePriceDraftField cloneValidationField(QuotePriceDraftField source) {
    QuotePriceDraftField field = cloneField(source, source.getPriceDraftId());
    field.setTargetValueJson(source.getTargetValueJson());
    field.setChangedFlag(source.getChangedFlag());
    return field;
  }

  private TechnicalPriceGapWorkspaceResponse.Item workspaceItem(
      QuoteCollaborationGap gap, QuotePriceDraft draft) {
    return new TechnicalPriceGapWorkspaceResponse.Item(
        gap.getId(), gap.getMaterialCode(), gap.getMaterialName(), gap.getMaterialSpec(),
        gap.getMaterialModel(), gap.getMaterialRole(), gap.getBomPath(), gap.getBomQuantity(),
        gap.getBomUnit(), gap.getReasonMessage(), gap.getGapStatus(),
        draftStatusLabel(draft), draft == null ? null : draft.getId(),
        draft == null ? null : draft.getPriceType(),
        draft == null ? null : priceTypeLabel(draft.getPriceType()),
        draft == null ? null : draft.getSourceMode(),
        draft == null ? null : sourceModeLabel(draft.getSourceMode()),
        draft == null ? null : referenceLabel(draft),
        draft == null ? null : draft.getValidationStatus(),
        draft == null ? null : draft.getValidationMessage(),
        draft == null ? null : draft.getUpdatedAt());
  }

  private FormalPriceReferenceSearchResponse.Item referenceItem(FormalPriceReference row) {
    return new FormalPriceReferenceSearchResponse.Item(
        row.sourceType(), row.sourceId(), row.priceType(), priceTypeLabel(row.priceType()),
        row.materialCode(), row.materialName(), row.specModel(), row.orgCode(), row.supplierName(),
        row.unit(), row.priceSummary(), row.versionText(), row.effectiveFrom(), row.effectiveTo());
  }

  private TechnicalPriceDraftResponse response(
      QuotePriceDraft draft, CollaborationScope scope) {
    List<QuotePriceDraftField> fields = draftRepository.findFields(draft.getId(), scope);
    List<TechnicalPriceDraftResponse.Field> values = fields.stream()
        .map(field -> new TechnicalPriceDraftResponse.Field(
            field.getId(), field.getSectionCode(), field.getRowKey(), field.getFieldCode(),
            field.getFieldName(), field.getValueType(), jsonText(field.getReferenceValueJson()),
            jsonText(field.getTargetValueJson()), field.getUnit(), enabled(field.getRequiredFlag()),
            enabled(field.getTechInputRequired()), enabled(field.getChangedFlag()),
            field.getValidationStatus(), field.getValidationMessage(), value(field.getSortSeq())))
        .toList();
    List<TechnicalPriceDraftResponse.ReferenceChange> changes = changeLogMapper.selectList(
        Wrappers.<BusinessChangeLog>lambdaQuery()
            .eq(BusinessChangeLog::getBizDomain, "QUOTE_COLLABORATION")
            .eq(BusinessChangeLog::getBizType, "PRICE_DRAFT_REFERENCE")
            .eq(BusinessChangeLog::getBizId, draft.getId())
            .orderByDesc(BusinessChangeLog::getChangedAt)
            .orderByDesc(BusinessChangeLog::getId)).stream()
        .map(row -> new TechnicalPriceDraftResponse.ReferenceChange(
            row.getChangedAt(), row.getChangedByName(), referenceSummary(row.getBeforeValue()),
            referenceSummary(row.getAfterValue())))
        .toList();
    return new TechnicalPriceDraftResponse(
        draft.getId(), draft.getDraftNo(), draft.getDraftVersion(), draft.getGapId(),
        draft.getMaterialCode(), draft.getMaterialName(), draft.getMaterialSpec(),
        draft.getMaterialModel(), draft.getOrgCode(), draft.getPriceType(),
        priceTypeLabel(draft.getPriceType()), draft.getSourceMode(),
        sourceModeLabel(draft.getSourceMode()), draft.getReferenceSourceType(),
        draft.getReferenceSourceId(), referenceLabel(draft), draft.getReferenceVersionText(),
        draft.getSupplierCode(), draft.getSupplierName(), draft.getUnit(), draft.getTaxIncluded(),
        decimalText(draft.getTaxRate()), draft.getEffectiveFrom(), draft.getEffectiveTo(),
        draft.getDraftStatus(), draft.getValidationStatus(), draft.getValidationMessage(),
        draft.getUpdatedAt(), taxConversion(draft, fields), values, changes);
  }

  private TechnicalPriceDraftResponse.TaxConversion taxConversion(
      QuotePriceDraft draft, List<QuotePriceDraftField> fields) {
    if (!"PASSED".equals(draft.getValidationStatus()) || !isFixed(draft.getPriceType())
        || draft.getTaxIncluded() == null || draft.getTaxRate() == null) return null;
    String amountCode = "SETTLE_FIXED".equals(draft.getPriceType())
        ? "BASE_SETTLE_PRICE" : "PRICE";
    String value = fields.stream().filter(field -> amountCode.equals(field.getFieldCode()))
        .map(field -> jsonText(field.getTargetValueJson())).findFirst().orElse(null);
    BigDecimal amount = decimal(value, "价格");
    if (amount == null) return null;
    BigDecimal factor = BigDecimal.ONE.add(draft.getTaxRate());
    BigDecimal included = draft.getTaxIncluded() == 1 ? amount
        : amount.multiply(factor).setScale(6, java.math.RoundingMode.HALF_UP);
    BigDecimal excluded = draft.getTaxIncluded() == 0 ? amount
        : amount.divide(factor, 6, java.math.RoundingMode.HALF_UP);
    return new TechnicalPriceDraftResponse.TaxConversion(
        included.stripTrailingZeros().toPlainString(),
        excluded.stripTrailingZeros().toPlainString());
  }

  private void resetGapValidation(DraftOwned owned, QuotePriceDraft saved) {
    int affected = gapMapper.updatePriceDraftValidationStatus(
        owned.gap().getId(), saved.getId(), "OPEN",
        owned.scope().businessUnitType(), owned.scope().applicableOrgCode(),
        owned.principal().userId(), owned.principal().userName());
    if (affected != 1) throw new CollaborationPersistenceException("重置价格草稿校验状态失败");
  }

  private void saveReferenceChange(
      DraftOwned owned, QuotePriceDraft draft, String before, String after) {
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain("QUOTE_COLLABORATION");
    log.setBizType("PRICE_DRAFT_REFERENCE");
    log.setBizId(draft.getId());
    log.setBizDetailId(draft.getGapId());
    log.setTaskId(owned.task().getId());
    log.setFieldName("reference_snapshot");
    log.setFieldLabel("参考正式价格");
    log.setBeforeValue(before);
    log.setAfterValue(after);
    log.setChangeReason("技术更换参考记录");
    log.setChangedBy(owned.principal().userId());
    log.setChangedByName(owned.principal().userName());
    log.setChangedAt(LocalDateTime.now());
    log.setChangeSource("OA_COLLABORATION");
    if (changeLogMapper.insert(log) != 1) {
      throw new CollaborationPersistenceException("保存参考价格变更记录失败");
    }
  }

  private String referenceSnapshot(QuotePriceDraft draft, List<QuotePriceDraftField> fields) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("sourceType", draft.getReferenceSourceType());
    if (draft.getReferenceSourceId() != null) root.put("sourceId", draft.getReferenceSourceId());
    root.put("versionText", draft.getReferenceVersionText());
    ArrayNode values = root.putArray("fields");
    for (QuotePriceDraftField field : fields) {
      ObjectNode item = values.addObject();
      item.put("section", field.getSectionCode());
      item.put("row", field.getRowKey());
      item.put("code", field.getFieldCode());
      if (field.getReferenceValueJson() != null) {
        try {
          item.set("value", objectMapper.readTree(field.getReferenceValueJson()));
        } catch (JsonProcessingException exception) {
          item.put("value", field.getReferenceValueJson());
        }
      }
    }
    return root.toString();
  }

  private String fingerprint(QuotePriceDraft draft, List<QuotePriceDraftField> fields) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("priceType", draft.getPriceType());
    root.put("orgCode", draft.getOrgCode());
    root.put("materialCode", draft.getMaterialCode());
    root.put("supplierCode", draft.getSupplierCode());
    root.put("unit", draft.getUnit());
    if (draft.getTaxRate() != null) root.put("taxRate", draft.getTaxRate());
    ArrayNode values = root.putArray("fields");
    fields.stream().sorted(Comparator.comparing(QuotePriceDraftField::getSortSeq))
        .forEach(field -> {
          ObjectNode item = values.addObject();
          item.put("section", field.getSectionCode());
          item.put("row", field.getRowKey());
          item.put("code", field.getFieldCode());
          item.put("value", jsonText(field.getTargetValueJson()));
        });
    return idempotency.payloadHash(root.toString());
  }

  private QuotePriceDraft requireDraft(Long id, CollaborationScope scope) {
    return draftRepository.findById(id, scope)
        .orElseThrow(TechnicalPriceDraftApplicationService::notFound);
  }

  private static void requireEditable(QuotePriceDraft draft) {
    if (!EDITABLE.contains(draft.getDraftStatus())) {
      throw new IllegalStateException("当前草稿已提交或已发布，不能修改");
    }
  }

  private static boolean isFixed(String priceType) {
    return "FIXED_PURCHASE".equals(priceType) || "SETTLE_FIXED".equals(priceType);
  }

  private static QuotePriceDraft copyMaster(QuotePriceDraft source) {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setId(source.getId());
    draft.setPriceType(source.getPriceType());
    draft.setSourceMode(source.getSourceMode());
    draft.setReferenceSourceType(source.getReferenceSourceType());
    draft.setReferenceSourceId(source.getReferenceSourceId());
    draft.setReferenceVersionText(source.getReferenceVersionText());
    draft.setTargetSourceType(source.getTargetSourceType());
    draft.setOrgCode(source.getOrgCode());
    draft.setMaterialCode(source.getMaterialCode());
    return draft;
  }

  private static String targetSource(String type) {
    return switch (type) {
      case "FIXED_PURCHASE" -> "lp_price_fixed_item";
      case "LINKED" -> "lp_price_linked_item";
      case "RANGE" -> "lp_price_range_item";
      case "SETTLE_FIXED" -> "lp_price_fixed_item";
      default -> throw new IllegalArgumentException("不支持的价格类型：" + type);
    };
  }

  private static String normalizePriceType(String type, boolean optional) {
    if (!StringUtils.hasText(type)) return optional ? null : null;
    String value = type.trim().toUpperCase(Locale.ROOT);
    if (!PRICE_TYPES.contains(value)) throw new IllegalArgumentException("不支持的价格类型：" + type);
    return value;
  }

  private static String priceTypeLabel(String type) {
    return switch (type == null ? "" : type) {
      case "FIXED_PURCHASE" -> "固定采购价";
      case "LINKED" -> "联动价";
      case "RANGE" -> "区间价";
      case "SETTLE_FIXED" -> "结算固定价";
      default -> type;
    };
  }

  private static String sourceModeLabel(String mode) {
    return "COPY".equals(mode) ? "复制正式记录" : "直接填写";
  }

  private static String draftStatusLabel(QuotePriceDraft draft) {
    if (draft == null) return "待补价";
    return switch (draft.getValidationStatus() == null ? "" : draft.getValidationStatus()) {
      case "PASSED" -> "校验通过·待提交";
      case "FAILED" -> "校验未通过";
      default -> "草稿待校验";
    };
  }

  private static String referenceLabel(QuotePriceDraft draft) {
    if (!"COPY".equals(draft.getSourceMode())) return "技术直接填写";
    return draft.getReferenceSourceType() + " / " + draft.getReferenceSourceId()
        + (StringUtils.hasText(draft.getReferenceVersionText())
            ? " / " + draft.getReferenceVersionText() : "");
  }

  private String referenceSummary(String json) {
    try {
      JsonNode node = objectMapper.readTree(json);
      return node.path("sourceType").asText("-") + " / " + node.path("sourceId").asText("-")
          + (node.path("versionText").asText().isBlank() ? "" : " / " + node.path("versionText").asText());
    } catch (RuntimeException | JsonProcessingException exception) {
      return "历史参考快照";
    }
  }

  private String jsonValue(String value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("价格字段无法序列化", exception);
    }
  }

  private static String jsonText(String json) {
    if (json == null) return null;
    try {
      JsonNode node = new ObjectMapper().readTree(json);
      return node.isNull() ? null : node.asText();
    } catch (JsonProcessingException exception) {
      return json;
    }
  }

  private static String fieldKey(String section, String row, String code) {
    return required(section, "字段分区") + "/" + firstText(row, "MAIN") + "/"
        + required(code, "字段编码");
  }

  private static BigDecimal decimal(String value, String label) {
    if (!StringUtils.hasText(value)) return null;
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(label + "必须是数字", exception);
    }
  }

  private static String decimalText(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static String currentBusinessUnit() {
    return CollaborationScope.requireBusinessUnit(BusinessUnitContext.getCurrentBusinessUnitType());
  }

  private static String required(String value, String label) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + "不能为空");
    return value.trim();
  }

  private static String firstText(String... values) {
    for (String value : values) if (StringUtils.hasText(value)) return value.trim();
    return null;
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static Long requireId(Long value, String label) {
    if (value == null || value <= 0) throw new IllegalArgumentException(label + "必须为正数");
    return value;
  }

  private static int value(Integer value) { return value == null ? 0 : value; }
  private static boolean enabled(Integer value) { return value != null && value == 1; }

  private static CollaborationDomainException notFound() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_NOT_FOUND, "价格缺口或草稿不存在，或不属于当前登录人");
  }

  private record Owned(
      QuoteCollaborationProductTask task,
      CollaborationScope scope,
      CollaborationPrincipal principal) {}

  private record GapOwned(
      QuoteCollaborationProductTask task,
      QuoteCollaborationGap gap,
      CollaborationScope scope,
      CollaborationPrincipal principal) {}

  private record DraftOwned(
      QuoteCollaborationProductTask task,
      QuoteCollaborationGap gap,
      QuotePriceDraft draft,
      CollaborationScope scope,
      CollaborationPrincipal principal) {}
}
