package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.util.CostPricingPeriodUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionRow;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummary;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuotePriceTypeRecognitionServiceImpl implements QuotePriceTypeRecognitionService {

  static final String OBJECT_NORMAL = "NORMAL";
  static final String OBJECT_MAKE_PARENT = "MAKE_PARENT";
  static final String OBJECT_MAKE_RAW = "MAKE_RAW";
  static final String OBJECT_MAKE_SCRAP = "MAKE_SCRAP";
  static final String OBJECT_MAKE_NO_SCRAP = "MAKE_NO_SCRAP";
  static final String OBJECT_PACKAGE_PARENT = "PACKAGE_PARENT";
  static final String OBJECT_PACKAGE_CHILD = "PACKAGE_CHILD";
  static final String STATUS_RECOGNIZED = "RECOGNIZED";
  static final String STATUS_NO_SOURCE = "NO_SOURCE";
  static final String STATUS_CHILD_GAP = "CHILD_GAP";
  private static final String ROW_TYPE_SPECIAL_ROLLUP_PARENT = "SPECIAL_ROLLUP_PARENT";

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final BomCostingRowSubRefMapper bomCostingRowSubRefMapper;
  private final MaterialMasterMapper materialMasterMapper;
  private final MonthlyQuotationSessions monthlySessions;
  private final MaterialMasterRawMapper rawMaterials;
  private final MonthlyPriceDocumentCodec monthlyCodec;
  private final PricePrepareItemClassifier itemClassifier;
  private final PackageComponentSnapshotService packageSnapshotService;
  private final MakePartPriceGenerationService makePartPriceGenerationService;
  private final MakePartScrapMappingService makePartScrapMappingService;
  private final com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms;

  public QuotePriceTypeRecognitionServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomCostingRowMapper bomCostingRowMapper,
      BomCostingRowSubRefMapper bomCostingRowSubRefMapper,
      MaterialMasterMapper materialMasterMapper,
      MonthlyQuotationSessions monthlySessions,
      MaterialMasterRawMapper rawMaterials,
      MonthlyPriceDocumentCodec monthlyCodec,
      PricePrepareItemClassifier itemClassifier,
      PackageComponentSnapshotService packageSnapshotService,
      MakePartPriceGenerationService makePartPriceGenerationService,
      MakePartScrapMappingService makePartScrapMappingService,
      com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomCostingRowSubRefMapper = bomCostingRowSubRefMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.monthlySessions = monthlySessions;
    this.rawMaterials = rawMaterials;
    this.monthlyCodec = monthlyCodec;
    this.itemClassifier = itemClassifier;
    this.packageSnapshotService = packageSnapshotService;
    this.makePartPriceGenerationService = makePartPriceGenerationService;
    this.makePartScrapMappingService = makePartScrapMappingService;
    this.costingBoms = costingBoms;
  }

  @Override
  @Transactional(readOnly = true)
  public QuotePriceTypeRecognitionResponse getRecognition(
      String oaNo, Long oaFormItemId, String periodMonth) {
    return buildRecognition(oaNo, oaFormItemId, periodMonth);
  }

  private QuotePriceTypeRecognitionResponse buildRecognition(
      String oaNo, Long oaFormItemId, String periodMonth) {
    Scope scope = requireScope(oaNo, oaFormItemId, periodMonth);
    List<BomCostingRow> rows = loadRows(scope);
    var packageInputs = new java.util.IdentityHashMap<QuotePriceTypeRecognitionRow, PackagePriceBasis>();
    var currentRows = new java.util.IdentityHashMap<QuotePriceTypeRecognitionRow, BomCostingRow>();
    List<QuotePriceTypeRecognitionRow> resultRows = buildRows(scope, rows, packageInputs, currentRows);
    fillPriceTypes(scope, resultRows, packageInputs, currentRows);
    fillMissingMaterialNames(resultRows);

    QuotePriceTypeRecognitionResponse response = new QuotePriceTypeRecognitionResponse();
    response.setOaNo(scope.oaNo());
    response.setOaFormItemId(scope.oaFormItemId());
    response.setProductCode(scope.productCode());
    response.setPeriodMonth(scope.periodMonth());
    response.setBomBuildBatchId(scope.bomReferenceNo());
    response.setRows(resultRows);
    response.setSummary(summary(rows.size(), resultRows));
    return response;
  }

  private Scope requireScope(String oaNo, Long oaFormItemId, String requestedPeriodMonth) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    String productCode =
        requireText(
            QuoteProductIdentityUtils.resolveCostingCode(item),
            "当前产品行料号、型号或图号");
    QuoteBomStatus bomStatus = latestBomStatus(form.getOaNo(), item.getId());
    String periodMonth =
        firstText(requestedPeriodMonth, resolvePeriodMonth(form, bomStatus));
    List<BomCostingRow> bomRows =
        bomCostingRowMapper.selectQuoteCostingSnapshot(
            form.getOaNo(), item.getId(), productCode, periodMonth);
    if (bomRows == null || bomRows.isEmpty()) {
      throw new QuoteIngestException("请先生成报价物料");
    }
    String bomReferenceNo = latestBuildBatchId(bomRows);
    return new Scope(
        form,
        item,
        form.getOaNo(),
        item.getId(),
        productCode,
        periodMonth,
        bomReferenceNo,
        List.copyOf(bomRows),
        firstText(item.getBusinessUnitType(), form.getBusinessUnitType()),
        bomStatus != null && "ELECTRONIC_DRAWING_BOM".equals(bomStatus.getBomSource())
            ? "E_DRAWING" : "U9");
  }

  private OaForm requireForm(String oaNo) {
    String normalized = requireText(oaNo, "报价单号");
    OaForm form =
        oaFormMapper.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, normalized));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + normalized);
    }
    return form;
  }

  private OaFormItem requireItem(OaForm form, Long oaFormItemId) {
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + oaFormItemId);
    }
    return item;
  }

  private QuoteBomStatus latestBomStatus(String oaNo, Long oaFormItemId) {
    return quoteBomStatusMapper.selectOne(
        Wrappers.<QuoteBomStatus>lambdaQuery()
            .eq(QuoteBomStatus::getOaNo, oaNo)
            .eq(QuoteBomStatus::getOaFormItemId, oaFormItemId)
            .orderByDesc(QuoteBomStatus::getCheckedAt)
            .orderByDesc(QuoteBomStatus::getId)
            .last("LIMIT 1"));
  }

  private String resolvePeriodMonth(OaForm form, QuoteBomStatus status) {
    String period =
        firstText(
            status == null ? null : status.getCostPeriodMonth(),
            trimToNull(form.getAccountingPeriodMonth()));
    if (period != null) {
      return period;
    }
    if (form.getApplyDate() != null) {
      return YearMonth.from(form.getApplyDate()).toString();
    }
    return CostPricingPeriodUtils.currentPricingMonth();
  }

  private List<BomCostingRow> loadRows(Scope scope) {
    // Recognition must use the same current monthly scope as preparation, including a
    // household parent published after the persisted settlement tree was generated.
    var rows = costingBoms.previewFromEffectiveBom(scope.oaFormItemId(), scope.bomReferenceNo()).rows();
    for (var row : rows) {
      scope.bomRows().stream().filter(old -> java.util.Objects.equals(old.getPath(), row.getPath())
          && java.util.Objects.equals(old.getMaterialCode(), row.getMaterialCode())
          && java.util.Objects.equals(old.getSettlementRowType(), row.getSettlementRowType()))
          .findFirst().ifPresent(old -> row.setId(old.getId()));
    }
    return rows;
  }

  private String latestBuildBatchId(List<BomCostingRow> rows) {
    List<String> buildBatchIds = rows.stream()
        .map(BomCostingRow::getBuildBatchId)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .toList();
    if (buildBatchIds.isEmpty()) {
      throw new QuoteIngestException("报价物料缺少构建编号，请重新生成报价物料");
    }
    boolean hasMissingBuildBatch =
        rows.stream().anyMatch(row -> !StringUtils.hasText(row.getBuildBatchId()));
    if (buildBatchIds.size() != 1 || hasMissingBuildBatch) {
      throw new QuoteIngestException("当前报价物料包含多个构建版本，请重新生成报价物料");
    }
    return buildBatchIds.getFirst();
  }

  private List<QuotePriceTypeRecognitionRow> buildRows(Scope scope, List<BomCostingRow> rows,
      Map<QuotePriceTypeRecognitionRow, PackagePriceBasis> packageInputs,
      Map<QuotePriceTypeRecognitionRow, BomCostingRow> currentRows) {
    List<PricePreparePlanItem> plans = itemClassifier.classify(rows);
    if (plans == null || plans.isEmpty()) {
      plans = defaultPlans(rows);
    }
    Map<Long, List<BomCostingRowSubRef>> rollupChildrenByRowId =
        loadSpecialRollupChildren(plans);
    List<MakePartPriceCalcRow> makePartStructureRows = List.of();
    if (plans.stream()
        .anyMatch(
            plan ->
                PricePrepareItemClassifierImpl.ITEM_TYPE_MAKE_PART.equals(plan.getItemType())
                    && !isSpecialRollupParent(plan))) {
      makePartStructureRows =
          makePartPriceGenerationService.previewStructureByOa(
              scope.oaNo(), scope.businessUnitType(), scope.periodMonth());
    }
    List<QuotePriceTypeRecognitionRow> result = new ArrayList<>();
    for (PricePreparePlanItem plan : plans) {
      String type = trimToNull(plan.getItemType());
      if (PricePrepareItemClassifierImpl.ITEM_TYPE_PACKAGE_COMPONENT.equals(type)) {
        result.add(packageParentRow(scope, plan, packageInputs));
      } else if (PricePrepareItemClassifierImpl.ITEM_TYPE_MAKE_PART.equals(type)) {
        result.add(
            makeParentRow(
                scope,
                plan,
                makePartStructureRows,
                plan.getBomRow().getCurrentSubRefs() != null ? plan.getBomRow().getCurrentSubRefs()
                    : rollupChildrenByRowId.getOrDefault(plan.getBomRowId(), List.of())));
      } else {
        result.add(
            priceableRow(
                scope,
                plan.getBomRow(),
                OBJECT_NORMAL,
                plan.getMaterialCode(),
                plan.getMaterialName(),
                null,
                plan.getBomRowId()));
      }
      flatten(List.of(result.getLast())).forEach(dto -> currentRows.put(dto, plan.getBomRow()));
    }
    return result;
  }

  /**
   * 价格类型树可能来自旧 BOM、制造件或包装件快照，这些快照允许品名为空。
   * 展示时按具体料号批量回退到正式物料主档，不能错误沿用父 BOM 行的品名。
   */
  private void fillMissingMaterialNames(List<QuotePriceTypeRecognitionRow> rows) {
    List<QuotePriceTypeRecognitionRow> flat = flatten(rows);
    Set<String> missingCodes = new LinkedHashSet<>();
    for (QuotePriceTypeRecognitionRow row : flat) {
      String materialCode = row == null ? null : trimToNull(row.getMaterialCode());
      if (materialCode != null && !StringUtils.hasText(row.getMaterialName())) {
        missingCodes.add(materialCode);
      }
    }
    if (missingCodes.isEmpty()) {
      return;
    }
    List<MaterialMaster> masters =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .in(MaterialMaster::getMaterialCode, missingCodes));
    Map<String, String> namesByCode = new LinkedHashMap<>();
    for (MaterialMaster master : masters == null ? List.<MaterialMaster>of() : masters) {
      String materialCode = master == null ? null : trimToNull(master.getMaterialCode());
      String materialName = master == null ? null : trimToNull(master.getMaterialName());
      if (materialCode != null && materialName != null) {
        namesByCode.putIfAbsent(materialCode, materialName);
      }
    }
    for (QuotePriceTypeRecognitionRow row : flat) {
      if (row != null && !StringUtils.hasText(row.getMaterialName())) {
        row.setMaterialName(namesByCode.get(trimToNull(row.getMaterialCode())));
      }
    }
  }

  private Map<Long, List<BomCostingRowSubRef>> loadSpecialRollupChildren(
      List<PricePreparePlanItem> plans) {
    List<Long> rowIds =
        plans == null
            ? List.of()
            : plans.stream()
                .filter(this::isSpecialRollupParent)
                .filter(plan -> plan.getBomRow().getCurrentSubRefs() == null)
                .map(PricePreparePlanItem::getBomRowId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    if (rowIds.isEmpty()) {
      return Map.of();
    }
    List<BomCostingRowSubRef> refs =
        bomCostingRowSubRefMapper.selectSpecialRollupChildren(rowIds);
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<BomCostingRowSubRef>> result = new LinkedHashMap<>();
    for (BomCostingRowSubRef ref : refs) {
      if (ref != null && ref.getCostingRowId() != null) {
        result.computeIfAbsent(ref.getCostingRowId(), ignored -> new ArrayList<>()).add(ref);
      }
    }
    return result;
  }

  private boolean isSpecialRollupParent(PricePreparePlanItem plan) {
    BomCostingRow row = plan == null ? null : plan.getBomRow();
    return row != null
        && ROW_TYPE_SPECIAL_ROLLUP_PARENT.equals(trimToNull(row.getSettlementRowType()));
  }

  private List<PricePreparePlanItem> defaultPlans(List<BomCostingRow> rows) {
    List<PricePreparePlanItem> plans = new ArrayList<>();
    for (BomCostingRow row : rows == null ? List.<BomCostingRow>of() : rows) {
      PricePreparePlanItem plan = new PricePreparePlanItem();
      plan.setBomRow(row);
      plan.setBomRowId(row.getId());
      plan.setMaterialCode(row.getMaterialCode());
      plan.setMaterialName(row.getMaterialName());
      plan.setItemType(PricePrepareItemClassifierImpl.ITEM_TYPE_NORMAL);
      plans.add(plan);
    }
    return plans;
  }

  private QuotePriceTypeRecognitionRow makeParentRow(
      Scope scope,
      PricePreparePlanItem plan,
      List<MakePartPriceCalcRow> structureRows,
      List<BomCostingRowSubRef> rollupChildren) {
    if (isSpecialRollupParent(plan)) {
      return specialRollupParentRow(scope, plan, rollupChildren);
    }
    BomCostingRow row = plan.getBomRow();
    QuotePriceTypeRecognitionRow parent = parentRow(row, OBJECT_MAKE_PARENT, plan.getMaterialCode(), plan.getMaterialName());
    List<MakePartPriceCalcRow> calcRows =
        (structureRows == null ? List.<MakePartPriceCalcRow>of() : structureRows).stream()
            .filter(
                calcRow ->
                    plan.getMaterialCode() != null
                        && plan.getMaterialCode().equals(calcRow.getParentMaterialNo()))
            .toList();
    if (calcRows.isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_GAP);
      parent.setMessage("缺制造件价格生成结果，无法展开原材料/废料");
      return parent;
    }
    Map<String, QuotePriceTypeRecognitionRow> children = new LinkedHashMap<>();
    for (MakePartPriceCalcRow calcRow : calcRows) {
      String rawCode = trimToNull(calcRow.getChildMaterialNo());
      if (rawCode != null) {
        children.putIfAbsent(
            OBJECT_MAKE_RAW + ":" + rawCode,
            priceableRow(
                scope,
                row,
                OBJECT_MAKE_RAW,
                rawCode,
                firstText(calcRow.getChildMaterialName(), calcRow.getChildMaterialSpec()),
                plan.getMaterialCode(),
                plan.getBomRowId(),
                calcRow.getQtyPerParent()));
      }
      String scrapCode = trimToNull(calcRow.getScrapCode());
      if (Boolean.TRUE.equals(calcRow.getNoScrapConfirmed())) {
        children.putIfAbsent(
            OBJECT_MAKE_NO_SCRAP + ":" + plan.getMaterialCode(),
            noScrapRow(scope, row, calcRow, plan.getMaterialCode(), plan.getBomRowId()));
      } else if (scrapCode != null) {
        children.putIfAbsent(
            OBJECT_MAKE_SCRAP + ":" + scrapCode,
            priceableRow(
                scope,
                row,
                OBJECT_MAKE_SCRAP,
                scrapCode,
                calcRow.getScrapName(),
                plan.getMaterialCode(),
                plan.getBomRowId(),
                null));
      }
    }
    parent.getChildren().addAll(children.values());
    aggregateParent(parent);
    return parent;
  }

  /**
   * 上卷父件只展开命中规则并冻结在 sub_ref 的原材料，不再把该父件的全部 U9
   * 直接子件重复展开。未命中的兄弟子件会继续作为独立 BOM 结算行进入价格类型确认。
   */
  private QuotePriceTypeRecognitionRow specialRollupParentRow(
      Scope scope,
      PricePreparePlanItem plan,
      List<BomCostingRowSubRef> rollupChildren) {
    BomCostingRow row = plan.getBomRow();
    QuotePriceTypeRecognitionRow parent =
        parentRow(row, OBJECT_MAKE_PARENT, plan.getMaterialCode(), plan.getMaterialName());
    Map<String, RollupRawMaterial> rawMaterials = new LinkedHashMap<>();
    for (BomCostingRowSubRef ref :
        rollupChildren == null ? List.<BomCostingRowSubRef>of() : rollupChildren) {
      String rawCode = trimToNull(ref == null ? null : ref.getSubMaterialCode());
      if (rawCode == null) {
        continue;
      }
      rawMaterials
          .computeIfAbsent(rawCode, RollupRawMaterial::new)
          .accept(ref);
    }
    if (rawMaterials.isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_GAP);
      parent.setMessage("缺上卷命中子件快照，无法展开原材料/废料");
      return parent;
    }

    Map<String, QuotePriceTypeRecognitionRow> children = new LinkedHashMap<>();
    for (RollupRawMaterial raw : rawMaterials.values()) {
      children.put(
          OBJECT_MAKE_RAW + ":" + raw.materialCode,
          priceableRow(
              scope,
              row,
              OBJECT_MAKE_RAW,
              raw.materialCode,
              raw.materialName,
              plan.getMaterialCode(),
              plan.getBomRowId(),
              raw.quantity(row.getQtyPerTop())));
      List<MaterialScrapRef> mappings =
          makePartScrapMappingService.listMappings(raw.materialCode, scope.businessUnitType());
      for (MaterialScrapRef mapping :
          mappings == null ? List.<MaterialScrapRef>of() : mappings) {
        String scrapCode = trimToNull(mapping == null ? null : mapping.getScrapCode());
        if (scrapCode == null) {
          continue;
        }
        children.putIfAbsent(
            OBJECT_MAKE_SCRAP + ":" + scrapCode,
            priceableRow(
                scope,
                row,
                OBJECT_MAKE_SCRAP,
                scrapCode,
                mapping.getScrapName(),
                plan.getMaterialCode(),
                plan.getBomRowId(),
                null));
      }
    }
    parent.getChildren().addAll(children.values());
    aggregateParent(parent);
    return parent;
  }

  private QuotePriceTypeRecognitionRow noScrapRow(
      Scope scope,
      BomCostingRow row,
      MakePartPriceCalcRow calcRow,
      String parentMaterialCode,
      Long bomRowId) {
    QuotePriceTypeRecognitionRow dto = new QuotePriceTypeRecognitionRow();
    dto.setRowKey(OBJECT_MAKE_NO_SCRAP + ":" + parentMaterialCode + ":" + (bomRowId == null ? row == null ? "" : row.getPath() : bomRowId));
    dto.setLevel(row == null ? 0 : row.getLevel());
    dto.setObjectType(OBJECT_MAKE_NO_SCRAP);
    dto.setMaterialCode(firstText(calcRow == null ? null : calcRow.getChildMaterialNo(), "-"));
    dto.setMaterialName(
        firstText(calcRow == null ? null : calcRow.getChildMaterialName(), "原材料")
            + " - 已确认无废料，废料抵扣按0处理");
    dto.setParentMaterialCode(parentMaterialCode);
    dto.setSourceBomRowId(bomRowId);
    dto.setSourceText(OBJECT_MAKE_NO_SCRAP);
    dto.setQuantity(null);
    dto.setPriceType("按0处理");
    dto.setPriceTypeSource(OBJECT_MAKE_NO_SCRAP);
    dto.setTypeStatus(STATUS_RECOGNIZED);
    dto.setMessage(firstText(calcRow == null ? null : calcRow.getRemark(), "人工确认无废料，废料抵扣按0处理"));
    return dto;
  }

  private record PackagePriceBasis(BigDecimal quantity, String specification) {}

  private QuotePriceTypeRecognitionRow packageParentRow(Scope scope, PricePreparePlanItem plan,
      Map<QuotePriceTypeRecognitionRow, PackagePriceBasis> packageInputs) {
    BomCostingRow row = plan.getBomRow();
    QuotePriceTypeRecognitionRow parent =
        parentRow(row, OBJECT_PACKAGE_PARENT, plan.getMaterialCode(), plan.getMaterialName());
    PackageSnapshotRequest request = new PackageSnapshotRequest();
    request.setPackageMaterialCode(plan.getMaterialCode());
    request.setPriceOrgCode(requiredPriceOrgCode(row));
    request.setPeriodMonth(scope.periodMonth());
    request.setOaNo(scope.oaNo());
    request.setTopProductCode(scope.productCode());
    request.setBomPurpose(row == null ? null : row.getBomPurpose());
    request.setSourceType(scope.bomSourceType());
    request.setAsOfDate(row == null ? null : row.getAsOfDate());
    if (!row.getHouseholdChildPaths().isEmpty()) {
      request.setSourceParentPath(row.getCurrentSourcePath());
      request.setSourceParentHierarchyId(row.getRawHierarchyNodeId());
    }
    PackageSnapshotResult snapshot = packageSnapshotService.previewSnapshot(request);
    if (snapshot == null || snapshot.getDetails() == null || snapshot.getDetails().isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_GAP);
      parent.setMessage("包装组件结构缺失，无法展开包装子件");
      return parent;
    }
    for (PackageComponentSnapshotDetail detail : snapshot.getDetails()) {
      // Household occurrences are already independent preview rows. Their children
      // must not introduce ordinary gaps or duplicate price recognition here.
      if (detail.getSourcePath() != null && row.getHouseholdChildPaths().containsKey(detail.getSourcePath())) continue;
      var child = priceableRow(scope, row, OBJECT_PACKAGE_CHILD, detail.getChildMaterialCode(),
          detail.getChildMaterialName(), plan.getMaterialCode(), plan.getBomRowId(), detail.getQtyPerParent());
      parent.getChildren().add(child);
      packageInputs.put(child, new PackagePriceBasis(detail.getQtyPerParent(), detail.getChildMaterialSpec()));
    }
    aggregateParent(parent);
    return parent;
  }

  private String requiredPriceOrgCode(BomCostingRow row) {
    String priceOrgCode = trimToNull(row == null ? null : row.getPriceOrgCode());
    if (priceOrgCode == null) {
      throw new QuoteIngestException("包装组件结构快照缺少上游 priceOrgCode");
    }
    return priceOrgCode;
  }

  private QuotePriceTypeRecognitionRow parentRow(
      BomCostingRow row, String objectType, String materialCode, String materialName) {
    QuotePriceTypeRecognitionRow parent = new QuotePriceTypeRecognitionRow();
    parent.setRowKey(objectType + ":" + materialCode + ":" + (row == null ? "" : row.getId() == null ? row.getPath() : row.getId()));
    parent.setLevel(row == null ? 0 : row.getLevel());
    parent.setObjectType(objectType);
    parent.setMaterialCode(materialCode);
    parent.setPriceOrgCode(row == null ? null : row.getPriceOrgCode());
    parent.setMaterialName(firstText(materialName, row == null ? null : row.getMaterialName()));
    parent.setParentMaterialCode(row == null ? null : row.getParentCode());
    parent.setSourceBomRowId(row == null ? null : row.getId());
    parent.setSourceText(objectType);
    parent.setQuantity(row == null ? null : row.getQtyPerParent());
    return parent;
  }

  private QuotePriceTypeRecognitionRow priceableRow(
      Scope scope,
      BomCostingRow row,
      String objectType,
      String materialCode,
      String materialName,
      String parentMaterialCode,
      Long bomRowId) {
    return priceableRow(scope, row, objectType, materialCode, materialName, parentMaterialCode, bomRowId, null);
  }

  private QuotePriceTypeRecognitionRow priceableRow(
      Scope scope,
      BomCostingRow row,
      String objectType,
      String materialCode,
      String materialName,
      String parentMaterialCode,
      Long bomRowId,
      java.math.BigDecimal quantityOverride) {
    QuotePriceTypeRecognitionRow dto = new QuotePriceTypeRecognitionRow();
    dto.setRowKey(objectType + ":" + materialCode + ":" + (bomRowId == null ? row == null ? "" : row.getPath() : bomRowId));
    dto.setLevel(row == null ? 0 : row.getLevel());
    dto.setObjectType(objectType);
    dto.setMaterialCode(materialCode);
    dto.setPriceOrgCode(row == null ? null : row.getPriceOrgCode());
    String sameMaterialRowName =
        row != null
                && java.util.Objects.equals(
                    trimToNull(materialCode), trimToNull(row.getMaterialCode()))
            ? row.getMaterialName()
            : null;
    dto.setMaterialName(firstText(materialName, sameMaterialRowName));
    dto.setParentMaterialCode(firstText(parentMaterialCode, row == null ? null : row.getParentCode()));
    dto.setSourceBomRowId(bomRowId);
    dto.setSourceText(objectType);
    dto.setQuantity(quantityOverride == null ? (row == null ? null : row.getQtyPerParent()) : quantityOverride);
    if (row == null || !StringUtils.hasText(row.getPriceOrgCode())) {
      dto.setTypeStatus("SCOPE_MISSING");
      dto.setMessage("缺少取价组织");
      return dto;
    }
    return dto;
  }

  private void fillPriceTypes(Scope scope, List<QuotePriceTypeRecognitionRow> roots,
      Map<QuotePriceTypeRecognitionRow, PackagePriceBasis> packageInputs,
      Map<QuotePriceTypeRecognitionRow, BomCostingRow> originals) {
    var rows = flatten(roots);
    var priced = rows.stream().filter(row -> Set.of(OBJECT_NORMAL, OBJECT_MAKE_RAW,
        OBJECT_MAKE_SCRAP, OBJECT_PACKAGE_CHILD).contains(row.getObjectType()))
        .filter(row -> !"SCOPE_MISSING".equals(row.getTypeStatus())).toList();
    if (priced.isEmpty()) return;
    var masters = pricingMasters(priced, originals);
    var session = monthlySessions.open(scope.oaNo(), scope.businessUnitType(), scope.periodMonth(),
        java.time.LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE),
        PricePrepareScenarioContext.oaLocked(scope.oaFormItemId(), priced.getFirst().getPriceOrgCode()));
    var inputs = new LinkedHashMap<QuotePriceTypeRecognitionRow, QuotationPriceSession.Input>();
    for (var row : priced) {
      var original = originals.get(row);
      boolean ordinary = OBJECT_NORMAL.equals(row.getObjectType());
      boolean scrap = OBJECT_MAKE_SCRAP.equals(row.getObjectType()) || ordinary && original != null
          && ("BYPRODUCT_EXTRA".equals(original.getSettlementRowType()) || "副产品".equals(original.getShapeAttr()));
      var master = masters.get(row.getPriceOrgCode() + "|" + row.getMaterialCode());
      var packaging = packageInputs.get(row);
      BigDecimal quantity = ordinary ? original == null ? null : original.getQtyPerTop()
          : packaging == null ? BigDecimal.ONE : packaging.quantity();
      if (quantity != null && quantity.signum() == 0) {
        row.setTypeStatus(STATUS_RECOGNIZED); row.setPriceType("零用量");
        row.setPriceTypeSource("ZERO_USAGE"); row.setMessage("零用量不取价、不采用月价");
        continue;
      }
      String unit = scrap ? "KG" : ordinary && original != null && StringUtils.hasText(original.getUnit())
          ? original.getUnit() : master == null ? null : master.getUnit();
      String specification = ordinary && original != null ? original.getMaterialSpec()
          : packaging != null && StringUtils.hasText(packaging.specification()) ? packaging.specification()
          : master == null ? null : master.getMaterialSpec();
      if (!StringUtils.hasText(row.getMaterialCode()) || !StringUtils.hasText(unit) || quantity == null) {
        row.setTypeStatus(STATUS_NO_SOURCE);
        row.setMessage("缺少明确的物料计价单位或用量，请核实当前报价物料");
        continue;
      }
      inputs.put(row, new QuotationPriceSession.Input(row.getMaterialCode(), row.getPriceOrgCode(),
          unit, specification, scrap ? TaxBasis.EXCLUDED : null, null, session.conditions(row.getPriceOrgCode()),
          quantity.abs(), scrap ? QuotationPriceSession.Purpose.SCRAP : QuotationPriceSession.Purpose.MATERIAL));
    }
    var read = session.read(inputs.values());
    inputs.forEach((row, input) -> {
      var value = read.values().get(input);
      if (value == null) {
        var problem = java.util.Objects.requireNonNull(read.problems().get(input), "月价识别缺少读取结果");
        row.setTypeStatus(STATUS_NO_SOURCE); row.setMessage(problem.message());
        return;
      }
      var selection = value.selection();
      var source = monthlyCodec.decode(monthlyCodec.wrap(selection.fact().evidence().json()),
          com.fasterxml.jackson.databind.JsonNode.class);
      row.setPriceType(monthlyPriceType(source.path("sourceKind").asText()));
      row.setPriceTypeSource(selection.monthlyPriceId() == null
          ? "TECH_FIXED".equals(source.path("sourceKind").asText()) ? "TECHNICAL_SUPPLEMENT" : "FORMAL_PRICE_SOURCE"
          : "MONTHLY_PRICE");
      row.setTypeStatus(STATUS_RECOGNIZED);
    });
    for (int i = rows.size() - 1; i >= 0; i--)
      if (!rows.get(i).getChildren().isEmpty()) aggregateParent(rows.get(i));
  }

  private Map<String, MaterialMasterRaw> pricingMasters(List<QuotePriceTypeRecognitionRow> rows,
      Map<QuotePriceTypeRecognitionRow, BomCostingRow> originals) {
    var requested = new java.util.TreeMap<String, Set<String>>();
    for (var row : rows) {
      var original = originals.get(row);
      boolean needsMaster = !OBJECT_NORMAL.equals(row.getObjectType())
          || original == null || !StringUtils.hasText(original.getUnit());
      if (needsMaster && StringUtils.hasText(row.getMaterialCode()))
        requested.computeIfAbsent(row.getPriceOrgCode(), ignored -> new LinkedHashSet<>()).add(row.getMaterialCode());
    }
    var result = new LinkedHashMap<String, MaterialMasterRaw>();
    requested.forEach((org, codes) -> {
      for (var master : rawMaterials.selectByLatestBatchAndCodes(codes, null,
          MaterialOrganization.fromPriceOrgCode(org).getCode())) {
        String key = org + "|" + master.getMaterialCode();
        if (result.putIfAbsent(key, master) != null)
          throw new QuoteIngestException("物料计价单位不唯一：" + master.getMaterialCode());
      }
    });
    return result;
  }

  private String monthlyPriceType(String sourceKind) {
    return switch (sourceKind) {
      case "PURCHASE_FIXED", "TECH_FIXED" -> "固定价";
      case "ODS_ORDER" -> "订单价";
      case "ODS_TIER" -> "阶梯价";
      case "LINKED", "LINKED_LEGACY" -> "联动价";
      case "RANGE_QTY", "RANGE_FACTOR", "RANGE_LEGACY_FACTOR" -> "区间价";
      case "SETTLE_FIXED" -> "结算固定价";
      case "SETTLE_LINKED" -> "结算联动价";
      case "SCRAP_PRICE" -> "废料价格";
      case "HOUSEHOLD_INQUIRY" -> "家用部件询价";
      default -> throw new IllegalStateException("无法识别保存的月价来源：" + sourceKind);
    };
  }

  private void aggregateParent(QuotePriceTypeRecognitionRow parent) {
    if (parent.getChildren().isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_GAP);
      parent.setMessage("未展开到可维护取价对象");
      return;
    }
    boolean missing =
        parent.getChildren().stream()
            .anyMatch(child -> !STATUS_RECOGNIZED.equals(child.getTypeStatus()));
    parent.setTypeStatus(missing ? STATUS_CHILD_GAP : STATUS_RECOGNIZED);
    parent.setMessage(missing ? "子项取价来源或范围待检查" : "子项候选来源已识别");
  }

  private QuotePriceTypeRecognitionSummary summary(
      int bomRowCount, List<QuotePriceTypeRecognitionRow> rows) {
    List<QuotePriceTypeRecognitionRow> flat = flatten(rows);
    QuotePriceTypeRecognitionSummary summary = new QuotePriceTypeRecognitionSummary();
    summary.setBomRowCount(bomRowCount);
    summary.setNormalCount(countObject(flat, OBJECT_NORMAL));
    summary.setMakePartCount(countObject(flat, OBJECT_MAKE_PARENT));
    summary.setPackageComponentCount(countObject(flat, OBJECT_PACKAGE_PARENT));
    int priceable = 0;
    int configured = 0;
    int missing = 0;
    for (QuotePriceTypeRecognitionRow row : flat) {
      if (!isPriceable(row)) {
        continue;
      }
      priceable++;
      if (STATUS_RECOGNIZED.equals(row.getTypeStatus())) {
        configured++;
      } else {
        missing++;
      }
    }
    summary.setPriceableCount(priceable);
    summary.setCandidateSourceCount(configured);
    summary.setMissingSourceCount(missing);
    return summary;
  }

  private int countObject(List<QuotePriceTypeRecognitionRow> flat, String objectType) {
    int count = 0;
    for (QuotePriceTypeRecognitionRow row : flat) {
      if (objectType.equals(row.getObjectType())) {
        count++;
      }
    }
    return count;
  }

  private List<QuotePriceTypeRecognitionRow> flatten(List<QuotePriceTypeRecognitionRow> rows) {
    List<QuotePriceTypeRecognitionRow> result = new ArrayList<>();
    for (QuotePriceTypeRecognitionRow row : rows == null ? List.<QuotePriceTypeRecognitionRow>of() : rows) {
      result.add(row);
      result.addAll(flatten(row.getChildren()));
    }
    return result;
  }

  private boolean isPriceable(QuotePriceTypeRecognitionRow row) {
    return row != null
        && !isParentObject(row.getObjectType())
        && !isDisplayOnlyObject(row.getObjectType());
  }

  private boolean isParentObject(String objectType) {
    return OBJECT_MAKE_PARENT.equals(objectType) || OBJECT_PACKAGE_PARENT.equals(objectType);
  }

  private boolean isDisplayOnlyObject(String objectType) {
    return OBJECT_MAKE_NO_SCRAP.equals(objectType);
  }

  private String requireText(String value, String fieldName) {
    String text = trimToNull(value);
    if (text == null) {
      throw new QuoteIngestException(fieldName + "不能为空");
    }
    return text;
  }

  private String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static final class RollupRawMaterial {
    private final String materialCode;
    private String materialName;
    private BigDecimal firstQtyPerParent;
    private BigDecimal totalQtyPerTop;

    private RollupRawMaterial(String materialCode) {
      this.materialCode = materialCode;
    }

    private void accept(BomCostingRowSubRef ref) {
      if (ref == null) {
        return;
      }
      if (!StringUtils.hasText(materialName) && StringUtils.hasText(ref.getSubMaterialName())) {
        materialName = ref.getSubMaterialName().trim();
      }
      if (firstQtyPerParent == null && ref.getSubQtyPerParent() != null) {
        firstQtyPerParent = ref.getSubQtyPerParent();
      }
      if (ref.getSubQtyPerTop() != null) {
        totalQtyPerTop =
            totalQtyPerTop == null
                ? ref.getSubQtyPerTop()
                : totalQtyPerTop.add(ref.getSubQtyPerTop());
      }
    }

    private BigDecimal quantity(BigDecimal parentQtyPerTop) {
      return RollupQuantityNormalizer.perParent(
          totalQtyPerTop, parentQtyPerTop, firstQtyPerParent);
    }
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String bomReferenceNo,
      List<BomCostingRow> bomRows,
      String businessUnitType,
      String bomSourceType) {}
}
