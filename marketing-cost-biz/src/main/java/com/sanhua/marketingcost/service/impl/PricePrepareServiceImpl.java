package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MakePartPricePrepareStrategy;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareBomItemLoader;
import com.sanhua.marketingcost.service.PricePrepareCurrentStateService;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.PricePrepareSettlementKeyGenerator;
import com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PricePrepareServiceImpl implements PricePrepareService {

  static final String DEFAULT_BOM_PURPOSE = "主制造";
  static final String DEFAULT_SOURCE_TYPE = "U9";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_PARTIAL = "PARTIAL";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String ITEM_STATUS_READY = "READY";
  private static final String ITEM_STATUS_MISSING_MASTER = "MISSING_MASTER";
  private static final String ITEM_STATUS_FAILED = "FAILED";
  private static final String GAP_TYPE_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  private static final String GAP_TYPE_MISSING_MASTER = "MISSING_MASTER";
  private static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";
  private static final String ACTION_MAINTAIN_STRUCTURE = "MAINTAIN_STRUCTURE";
  private static final String ACTION_MAINTAIN_PRICE = "MAINTAIN_PRICE";
  private static final String GAP_PUSH_PENDING = "PENDING";
  private static final String PRICE_AS_OF_SOURCE_CURRENT_TIME = "CURRENT_TIME";
  private static final String PRICE_AS_OF_SOURCE_REQUEST = "REQUEST";
  private static final int CURRENT_FLAG_HISTORY = 0;
  private static final int DB_MESSAGE_MAX_LENGTH = 1000;
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final PricePrepareGapMapper gapMapper;
  private final PricePrepareBomItemLoader bomItemLoader;
  private final PricePrepareItemClassifier itemClassifier;
  private final NormalMaterialPricePrepareStrategy normalMaterialPricePrepareStrategy;
  private final PackageComponentPricePrepareStrategy packageComponentPricePrepareStrategy;
  private final MakePartPricePrepareStrategy makePartPricePrepareStrategy;
  private final Clock clock;
  private final PricePrepareCurrentStateService currentStateService;
  private final MonthlyQuotationSessions monthlySessions;
  private final MonthlyAdoptionExecutor monthlyAdoption;
  private final MonthlyPriceStorage monthlyStorage;
  private final MonthlyPriceDocumentCodec monthlyCodec;
  private final com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository effectiveBoms;
  private final com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms;

  @Autowired
  public PricePrepareServiceImpl(
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper,
      PricePrepareBomItemLoader bomItemLoader,
      PricePrepareItemClassifier itemClassifier,
      NormalMaterialPricePrepareStrategy normalMaterialPricePrepareStrategy,
      PackageComponentPricePrepareStrategy packageComponentPricePrepareStrategy,
      MakePartPricePrepareStrategy makePartPricePrepareStrategy,
      PricePrepareCurrentStateService currentStateService,
      MonthlyQuotationSessions monthlySessions, MonthlyAdoptionExecutor monthlyAdoption,
      MonthlyPriceStorage monthlyStorage, MonthlyPriceDocumentCodec monthlyCodec,
      com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository effectiveBoms,
      com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms) {
    this(batchMapper, itemMapper, gapMapper, bomItemLoader, itemClassifier,
        normalMaterialPricePrepareStrategy, packageComponentPricePrepareStrategy, makePartPricePrepareStrategy,
        Clock.system(BUSINESS_ZONE), currentStateService, monthlySessions, monthlyAdoption,
        monthlyStorage, monthlyCodec, effectiveBoms, costingBoms);
  }

  PricePrepareServiceImpl(
      PricePrepareBatchMapper batchMapper, PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper, PricePrepareBomItemLoader bomItemLoader,
      PricePrepareItemClassifier itemClassifier,
      NormalMaterialPricePrepareStrategy normalMaterialPricePrepareStrategy,
      PackageComponentPricePrepareStrategy packageComponentPricePrepareStrategy,
      MakePartPricePrepareStrategy makePartPricePrepareStrategy, Clock clock,
      PricePrepareCurrentStateService currentStateService,
      MonthlyQuotationSessions monthlySessions, MonthlyAdoptionExecutor monthlyAdoption,
      MonthlyPriceStorage monthlyStorage, MonthlyPriceDocumentCodec monthlyCodec,
      com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository effectiveBoms,
      com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms) {
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
    this.bomItemLoader = bomItemLoader;
    this.itemClassifier = itemClassifier;
    this.normalMaterialPricePrepareStrategy = normalMaterialPricePrepareStrategy;
    this.packageComponentPricePrepareStrategy = packageComponentPricePrepareStrategy;
    this.makePartPricePrepareStrategy = makePartPricePrepareStrategy;
    this.clock = java.util.Objects.requireNonNull(clock);
    this.currentStateService = currentStateService;
    this.monthlySessions = java.util.Objects.requireNonNull(monthlySessions);
    this.monthlyAdoption = java.util.Objects.requireNonNull(monthlyAdoption);
    this.monthlyStorage = java.util.Objects.requireNonNull(monthlyStorage);
    this.monthlyCodec = java.util.Objects.requireNonNull(monthlyCodec);
    this.effectiveBoms = java.util.Objects.requireNonNull(effectiveBoms);
    this.costingBoms = java.util.Objects.requireNonNull(costingBoms);
  }

  @Override
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public PricePrepareCalculationResult calculate(PricePrepareGenerateRequest request) {
    return execute(request, false);
  }

  @Override
  @Transactional(
      rollbackFor = Exception.class,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  @RetryOnConcurrency
  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public PricePrepareGenerateResult generate(PricePrepareGenerateRequest request) {
    return generateNormalized(normalize(request, currentTime()));
  }

  @Override
  @Transactional(rollbackFor = Exception.class,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public PricePrepareGenerateResult generateForReprice(PricePrepareGenerateRequest request,
      com.sanhua.marketingcost.service.reprice.MonthlyRepriceCalculation input, String effectiveBuild) {
    var saved = input.input();
    if (!java.util.Objects.equals(request.getOaNo(), saved.form().getOaNo())
        || !java.util.Objects.equals(request.getOaFormItemId(), saved.item().getId())
        || !java.util.Objects.equals(request.getTopProductCode(), saved.context().productCode())
        || !java.util.Objects.equals(request.getPeriodMonth(), saved.context().costPeriodMonth())
        || !java.util.Objects.equals(request.getBusinessUnitType(), saved.form().getBusinessUnitType())
        || !java.util.Objects.equals(request.getPriceAsOfTime(), input.priceAsOfTime()))
      throw new IllegalArgumentException("调价准备必须使用保存的任务输入");
    var generated = generateNormalized(normalize(request, currentTime(), new RepriceScope(input, effectiveBuild)));
    if (generated.getGapCount() > 0) {
      // The enclosing calculation rolls these temporary gaps back. Preserve actionable details
      // in the returned failure before the worker records it on the durable task.
      var details = gapMapper.selectList(Wrappers.<PricePrepareGap>lambdaQuery()
          .eq(PricePrepareGap::getPrepareNo, generated.getPrepareNo())
          .eq(PricePrepareGap::getBusinessUnitType, request.getBusinessUnitType())
          .orderByAsc(PricePrepareGap::getId).last("LIMIT 10")).stream()
          .map(gap -> (StringUtils.hasText(gap.getGapMaterialCode()) ? gap.getGapMaterialCode() : gap.getMaterialCode())
              + "：" + gap.getMessage()).distinct().collect(java.util.stream.Collectors.joining("；"));
      if (!details.isBlank()) generated.setMessage(generated.getMessage() + "；" + details);
    }
    return generated;
  }

  private PricePrepareGenerateResult generateNormalized(NormalizedGenerateRequest req) {
    if (req.scenarioContext().scenarioType()
        == com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE)
      throw new IllegalArgumentException("财务价格准备必须从已保存的 OA 月价依据投影，不能重复采用月价");
    List<BomCostingRow> rows;
    try {
      rows = loadRows(req);
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (RuntimeException unavailableBom) {
      return execute(req, true).getSummary();
    }
    if (rows == null || rows.isEmpty()) return execute(req, true).getSummary();
    var scope = monthlySessions.open(req.oaNo(), req.businessUnitType(), req.periodMonth(),
        req.priceAsOfTime(), req.scenarioContext().forOrganization(rows.getFirst().getPriceOrgCode()));
    MonthlyAdoptionPlan adopted;
    try {
      adopted = monthlyAdoption.execute(scope.context(), remaining -> {
        var attempt = freshPrices(req);
        var preview = execute(attempt, false);
        if (!STATUS_SUCCESS.equals(preview.getSummary().getStatus())) throw new PreviewNotReady();
        var session = attempt.scenarioContext().monthlyPrices().get();
        return session == null ? new MonthlyAdoptionPlan(Map.of(), List.of(), Set.of()) : session.plan();
      }).plan();
    } catch (PreviewNotReady pending) {
      refreshSettlementScope(req);
      var failed = execute(freshPrices(req), true).getSummary();
      if (STATUS_SUCCESS.equals(failed.getStatus()))
        throw new org.springframework.dao.ConcurrencyFailureException("价格来源已补齐，需要重新执行整组月价采用");
      return failed;
    }
    refreshSettlementScope(req);
    var finalRequest = freshPrices(req);
    var result = execute(finalRequest, true);
    var session = finalRequest.scenarioContext().monthlyPrices().get();
    var finalPlan = session == null ? new MonthlyAdoptionPlan(Map.of(), List.of(), Set.of()) : session.plan();
    if (!STATUS_SUCCESS.equals(result.getSummary().getStatus()) || !adopted.equals(finalPlan))
      throw new org.springframework.dao.ConcurrencyFailureException("月价采用后计价范围变化，请重新准备当前报价");
    for (var item : result.getItems()) if (MonthlyPreparationPrice.KIND.equals(item.getResultRefType())) {
      var evidence = monthlyCodec.decode(item.getInputSnapshotJson(), MonthlyPreparationPrice.class);
      var selected = finalPlan.selections().get(evidence.request());
      if (selected == null || !selected.equals(evidence.selection()) || selected.monthlyPriceId() == null)
        throw new IllegalStateException("准备明细缺少本次采用的月价依据");
      if (req.reprice() == null)
        monthlyStorage.attachPreparation(req.businessUnitType(),item.getId(),selected.monthlyPriceId(),selected.fact().type());
      else monthlyStorage.attachRepricePreparation(req.businessUnitType(),req.reprice().input().repriceNo(),
          item.getId(),selected.monthlyPriceId(),selected.fact().type());
    }
    return result.getSummary();
  }

  private static NormalizedGenerateRequest freshPrices(NormalizedGenerateRequest req) {
    var scenario = req.scenarioContext();
    return new NormalizedGenerateRequest(req.oaNo(), req.oaFormItemId(), req.topProductCode(),
        req.topProductCodes(), req.periodMonth(), req.priceAsOfTime(), req.priceAsOfSource(),
        req.businessUnitType(), req.bomPurpose(), req.sourceType(), new PricePrepareScenarioContext(
            scenario.scenarioType(), scenario.scenarioGroupNo(), scenario.sourcePrepareNo(),
            scenario.variableOverrides(), scenario.supplierReviews()), req.reprice());
  }

  private static final class PreviewNotReady extends RuntimeException {}

  private PricePrepareCalculationResult execute(
      PricePrepareGenerateRequest request, boolean persist) {
    return execute(normalize(request, currentTime()), persist);
  }

  private PricePrepareCalculationResult execute(NormalizedGenerateRequest req, boolean persist) {
    LocalDateTime now = currentTime();
    Execution execution = new Execution(persist, req.reprice() == null);

    PricePrepareBatch batch = initBatch(req, now, persist);

    List<BomCostingRow> bomRows;
    try {
      bomRows = loadRows(req);
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (RuntimeException ex) {
      setBatchSummary(batch, 0, 0, 0, 0, STATUS_FAILED, "读取BOM结算明细失败：" + exceptionMessage(ex));
      return finishExecution(batch, execution);
    }
    if (bomRows == null || bomRows.isEmpty()) {
      insertMissingBomGap(batch, req.topProductCodes(), execution);
      int missingBomGapCount = req.topProductCodes().isEmpty() ? 1 : req.topProductCodes().size();
      setBatchSummary(batch, 0, 0, 0, missingBomGapCount, STATUS_PARTIAL, "OA无BOM结算明细，存在价格准备缺口");
      return finishExecution(batch, execution);
    }

    var preview = previewSettlementScope(req, bomRows);
    if (!persist) bomRows = preview.rows();

    List<PricePreparePlanItem> planItems;
    try {
      bindBomScope(req, bomRows, preview);
      // Preview carries the current household exclusions and rollup references. Its rows already
      // have persisted IDs when the complete settlement shape matches the saved tree.
      planItems = itemClassifier.classify(preview.rows());
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (MonthlyPriceProblem scopeChanged) {
      throw scopeChanged;
    } catch (RuntimeException ex) {
      setBatchSummary(batch, 0, 0, 0, 0, STATUS_FAILED, "价格准备分类失败：" + exceptionMessage(ex));
      return finishExecution(batch, execution);
    }
    if (planItems == null) {
      setBatchSummary(batch, 0, 0, 0, 0, STATUS_FAILED, "价格准备分类失败：分类结果为空");
      return finishExecution(batch, execution);
    }
    planItems = mergePlanItems(planItems, batch);
    var normalItems = planItems.stream().filter(this::isReadyNormalItem).toList();
    var normalResults =
        normalItems.isEmpty()
            ? Map.<PricePreparePlanItem, NormalMaterialPricePrepareResult>of()
            : normalMaterialPricePrepareStrategy.prepareBatch(
                req.oaNo(),
                batch.getBusinessUnitType(),
                req.periodMonth(),
                req.priceAsOfTime(),
                req.scenarioContext(),
                normalItems,
                persist);
    int successCount = 0;
    int warningCount = 0;
    int gapCount = 0;
    for (PricePreparePlanItem planItem : planItems) {
      PricePrepareItem item = buildPrepareItem(batch, planItem);
      if (ITEM_STATUS_MISSING_MASTER.equals(planItem.getStatus())) {
        saveItem(item, execution);
        gapCount++;
        insertMissingMasterGap(batch, planItem, execution);
      } else if (isReadyPackageComponent(planItem)) {
        PackageComponentPricePrepareResult packageResult =
            preparePackageComponent(batch, req, planItem, persist);
        applyPackageResult(item, packageResult);
        saveItem(item, execution);
        if (ITEM_STATUS_READY.equals(packageResult.getStatus())) {
          successCount++;
        } else {
          gapCount += insertPackageComponentGaps(batch, planItem, packageResult, execution);
        }
      } else if (isReadyMakePart(planItem)) {
        MakePartPricePrepareResult makePartResult = prepareMakePart(batch, req, planItem, persist);
        applyMakePartResult(item, makePartResult);
        saveItem(item, execution);
        if (ITEM_STATUS_READY.equals(makePartResult.getStatus())) {
          successCount++;
        } else {
          gapCount += insertMakePartGaps(batch, planItem, makePartResult, execution);
        }
      } else if (isReadyNormalItem(planItem)) {
        NormalMaterialPricePrepareResult normalResult = normalResults.get(planItem);
        if (normalResult == null)
          throw new IllegalStateException("普通料号批量准备缺少结果：" + planItem.getMaterialCode());
        applyNormalResult(item, normalResult);
        saveItem(item, execution);
        if (ITEM_STATUS_READY.equals(normalResult.getStatus())) {
          successCount++;
          if (normalResult.isCarriedForward()) {
            warningCount++;
          }
        } else {
          gapCount++;
          insertNormalPriceGap(batch, planItem, normalResult, execution);
        }
      } else {
        saveItem(item, execution);
        successCount++;
      }
    }
    captureMonthlyInputs(batch, req, planItems, execution);
    batch.setSupplierPriceReviews(req.scenarioContext().supplierReviews().reviews());
    String status = gapCount > 0 ? STATUS_PARTIAL : STATUS_SUCCESS;
    String message = gapCount > 0 ? "已读取BOM结算明细并完成价格准备，存在待补充缺口" : "已读取BOM结算明细并完成价格准备";
    setBatchSummary(batch, planItems.size(), successCount, warningCount, gapCount, status, message);
    return finishExecution(batch, execution);
  }

  /** Every adoption attempt plans against the original tree, never a previously cut settlement tree. */
  private com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.Preview previewSettlementScope(
      NormalizedGenerateRequest req, List<BomCostingRow> stored) {
    String build = effectiveBuild(req, stored);
    var preview = preview(req, build);
    // Matching persisted rows may supply their original subtree references. A changed scope must
    // carry its own preview evidence; borrowing an old row would reintroduce suppressed materials.
    if (settlementShape(preview.rows()).equals(settlementShape(stored))) {
      var byPath = new LinkedHashMap<String, BomCostingRow>();
      stored.forEach(row -> byPath.put(row.getPath(), row));
      for (var row : preview.rows()) {
        var old = byPath.get(row.getPath());
        if (old != null) row.setId(old.getId());
      }
    }
    return preview;
  }

  private void refreshSettlementScope(NormalizedGenerateRequest req) {
    var stored = loadRows(req);
    String build = effectiveBuild(req, stored);
    if (!settlementShape(stored).equals(settlementShape(
        preview(req, build).rows()))) {
      if (req.reprice() == null) costingBoms.buildFromEffectiveBom(req.oaFormItemId(), build);
      else costingBoms.buildForReprice(req.reprice().input(), build);
    }
  }

  private List<BomCostingRow> loadRows(NormalizedGenerateRequest req) {
    return req.reprice() == null
        ? bomItemLoader.loadByQuoteItem(req.oaNo(),req.oaFormItemId(),req.topProductCode(),req.periodMonth())
        : bomItemLoader.loadForCalculation(req.oaNo(),req.oaFormItemId(),req.topProductCode(),
            req.periodMonth(),req.reprice().input().repriceNo());
  }

  private com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.Preview preview(
      NormalizedGenerateRequest req, String build) {
    return req.reprice() == null ? costingBoms.previewFromEffectiveBom(req.oaFormItemId(),build)
        : costingBoms.previewForReprice(req.reprice().input(),build);
  }

  private String effectiveBuild(NormalizedGenerateRequest req, List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty())
      throw invalidBomScope("当前报价结算明细已变化，请重新生成报价物料");
    if (rows.stream().anyMatch(row -> row == null || !StringUtils.hasText(row.getBuildBatchId())))
      throw invalidBomScope("报价结算明细缺少原始有效 BOM 构建身份，请重新生成报价物料");
    var batches = rows.stream().map(BomCostingRow::getBuildBatchId).distinct().toList();
    if (batches.size() != 1)
      throw invalidBomScope("报价结算明细混用多个原始有效 BOM 构建，请重新生成报价物料");
    var original = effectiveBoms.findNodesByBuildBatchId(batches.getFirst());
    if (original == null || original.isEmpty())
      throw invalidBomScope("缺少本产品原始有效 BOM，不能准备月价，请重新生成报价物料");
    if (original.stream().anyMatch(node -> !req.topProductCode().equals(node.getTopProductCode())
        || !req.periodMonth().equals(node.getCostPeriodMonth())
        || !batches.getFirst().equals(node.getBuildBatchId())))
      throw invalidBomScope("原始有效 BOM 与当前报价产品或月份不一致");
    if (req.reprice() != null && !req.reprice().effectiveBuild().equals(batches.getFirst()))
      throw invalidBomScope("调价结算行与保存的有效BOM构建不一致");
    return batches.getFirst();
  }

  private MonthlyPriceProblem invalidBomScope(String message) {
    return new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_PLAN, message);
  }

  private record SettlementShape(String path, String code, String organization, String unit,
      String specification, BigDecimal quantity, String type, Integer subtreeRequired) {}

  private static Set<SettlementShape> settlementShape(List<BomCostingRow> rows) {
    return rows.stream().map(row -> new SettlementShape(row.getPath(), row.getMaterialCode(),
        row.getPriceOrgCode(), row.getUnit(), row.getMaterialSpec(),
        row.getQtyPerTop() == null ? null : row.getQtyPerTop().stripTrailingZeros(),
        row.getSettlementRowType(), row.getSubtreeCostRequired())).collect(java.util.stream.Collectors.toSet());
  }

  private void bindBomScope(NormalizedGenerateRequest req, List<BomCostingRow> rows,
      com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.Preview preview) {
    if (!settlementShape(rows).equals(settlementShape(preview.rows())))
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
          "当前结算树与月价计价范围不一致，需要从原始有效树重建");
    String org = rows.getFirst().getPriceOrgCode();
    monthlySessions.open(req.oaNo(), req.businessUnitType(), req.periodMonth(), req.priceAsOfTime(),
        req.scenarioContext().forOrganization(org)).bindScope(preview.pricingScope());
  }

  private void captureMonthlyInputs(PricePrepareBatch batch, NormalizedGenerateRequest req,
      List<PricePreparePlanItem> plans, Execution execution) {
    var session = req.scenarioContext().monthlyPrices().get();
    if (session == null) return;
    var roots = new LinkedHashMap<String, String>();
    plans.forEach(plan -> roots.put(currentItemKey(batch, plan), plan.getBomRow().getPath()));
    var adoptedInputs = session.plan();
    var evidence = new MonthlyPreparationEvidence(monthlyCodec);
    for (var row : execution.items) {
      if (!ITEM_STATUS_READY.equals(row.getStatus()) || row.getQuantity() == null || row.getQuantity().signum() == 0) continue;
      var inputs = MonthlyPreparationInputs.capture(adoptedInputs, roots.get(row.getSettlementKey()));
      if (inputs.uses().isEmpty()) throw new IllegalStateException("已计价准备缺少实际月价输入：" + row.getMaterialCode());
      row.setInputSnapshotJson(evidence.attach(row, inputs));
      if (execution.persist) {
        var patch = new PricePrepareItem(); patch.setId(row.getId()); patch.setInputSnapshotJson(row.getInputSnapshotJson());
        if (itemMapper.updateById(patch) != 1) throw new IllegalStateException("准备月价依据保存失败");
      }
    }
  }

  private List<PricePreparePlanItem> mergePlanItems(
      List<PricePreparePlanItem> planItems, PricePrepareBatch batch) {
    Map<String, PricePreparePlanItem> merged = new LinkedHashMap<>();
    Map<String, BigDecimal> quantities = new LinkedHashMap<>();
    for (PricePreparePlanItem planItem : planItems) {
      if (planItem == null) {
        continue;
      }
      String key = currentItemKey(batch, planItem);
      merged.putIfAbsent(key, planItem);
      BigDecimal quantity = quantity(planItem.getBomRow());
      if (quantity != null) {
        quantities.merge(key, quantity, BigDecimal::add);
      }
    }
    for (Map.Entry<String, PricePreparePlanItem> entry : merged.entrySet()) {
      BigDecimal quantity = quantities.get(entry.getKey());
      if (quantity != null && entry.getValue().getBomRow() != null) {
        entry.getValue().getBomRow().setQtyPerTop(quantity);
      }
    }
    return List.copyOf(merged.values());
  }

  private PricePrepareBatch initBatch(
      NormalizedGenerateRequest req, LocalDateTime now, boolean persist) {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setPrepareNo(newPrepareNo(now));
    batch.setOaNo(req.oaNo());
    batch.setPeriodMonth(req.periodMonth());
    batch.setOaFormItemId(req.oaFormItemId());
    batch.setTopProductCode(req.topProductCode());
    batch.setBomPurpose(req.bomPurpose());
    batch.setSourceType(req.sourceType());
    batch.setScenarioType(req.scenarioContext().scenarioType().name());
    batch.setScenarioGroupNo(req.scenarioContext().scenarioGroupNo());
    batch.setSourcePrepareNo(req.scenarioContext().sourcePrepareNo());
    batch.setStatus(STATUS_RUNNING);
    batch.setTotalCount(0);
    batch.setSuccessCount(0);
    batch.setWarningCount(0);
    batch.setGapCount(0);
    batch.setPriceAsOfTime(req.priceAsOfTime());
    batch.setPriceAsOfSource(req.priceAsOfSource());
    batch.setStartedAt(now);
    batch.setFinishedAt(null);
    // 价格准备必须在实时成本前显式执行；自制件只消费制造件价格生成结果，不回退旧人工价。
    batch.setMessage(dbMessage("准备读取BOM结算明细"));
    batch.setBusinessUnitType(req.businessUnitType());
    if (persist) {
      batchMapper.insert(batch);
    }
    return batch;
  }

  private String currentItemKey(PricePrepareBatch batch, PricePreparePlanItem planItem) {
    return PricePrepareSettlementKeyGenerator.settlementKey(
        batch == null ? null : batch.getOaFormItemId(),
        batch == null ? null : batch.getTopProductCode(),
        planItem);
  }

  private PricePrepareItem buildPrepareItem(
      PricePrepareBatch batch, PricePreparePlanItem planItem) {
    PricePrepareItem item = new PricePrepareItem();
    item.setPrepareNo(batch.getPrepareNo());
    item.setPeriodMonth(batch.getPeriodMonth());
    item.setPriceOrgCode(planItem.getBomRow() == null ? null : planItem.getBomRow().getPriceOrgCode());
    item.setOaNo(batch.getOaNo());
    item.setOaFormItemId(batch.getOaFormItemId());
    item.setTopProductCode(planItem.getTopProductCode());
    item.setBomRowId(planItem.getBomRowId());
    item.setMaterialCode(planItem.getMaterialCode());
    item.setMaterialName(planItem.getMaterialName());
    item.setItemType(planItem.getItemType());
    item.setQuantity(quantity(planItem.getBomRow()));
    item.setStatus(planItem.getStatus());
    item.setMessage(planItem.getMessage());
    item.setBusinessUnitType(batch.getBusinessUnitType());
    // 财务批次是由 source_prepare_no 显式引用的对比快照，不参与既有 OA“当前价格”查询。
    // 新批次先作为候选写入；完整成功后由 currentStateService 原子发布。
    item.setCurrentFlag(CURRENT_FLAG_HISTORY);
    item.setSettlementKey(currentItemKey(batch, planItem));
    return item;
  }

  private boolean isReadyNormalItem(PricePreparePlanItem planItem) {
    return planItem != null
        && PricePrepareItemClassifierImpl.ITEM_TYPE_NORMAL.equals(planItem.getItemType())
        && ITEM_STATUS_READY.equals(planItem.getStatus());
  }

  private boolean isReadyPackageComponent(PricePreparePlanItem planItem) {
    return planItem != null
        && PricePrepareItemClassifierImpl.ITEM_TYPE_PACKAGE_COMPONENT.equals(planItem.getItemType())
        && ITEM_STATUS_READY.equals(planItem.getStatus());
  }

  private boolean isReadyMakePart(PricePreparePlanItem planItem) {
    return planItem != null
        && PricePrepareItemClassifierImpl.ITEM_TYPE_MAKE_PART.equals(planItem.getItemType())
        && ITEM_STATUS_READY.equals(planItem.getStatus());
  }

  private PackageComponentPricePrepareResult preparePackageComponent(
      PricePrepareBatch batch,
      NormalizedGenerateRequest req,
      PricePreparePlanItem planItem,
      boolean persist) {
    try {
      if (!persist) {
        return packageComponentPricePrepareStrategy.calculate(
            batch.getPrepareNo(),
            req.oaNo(),
            req.periodMonth(),
            req.priceAsOfTime(),
            req.bomPurpose(),
            req.sourceType(),
            planItem,
            req.scenarioContext().forOrganization(planItem.getBomRow().getPriceOrgCode()));
      }
      return packageComponentPricePrepareStrategy.prepare(
          batch.getPrepareNo(),
          req.oaNo(),
          req.periodMonth(),
          req.priceAsOfTime(),
          req.bomPurpose(),
          req.sourceType(),
          planItem,
          req.scenarioContext().forOrganization(planItem.getBomRow().getPriceOrgCode()));
    } catch (MonthlyPriceProblem retryableMonthlyPrice) {
      throw retryableMonthlyPrice;
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (RuntimeException ex) {
      PackageComponentPricePrepareResult.Gap gap =
          new PackageComponentPricePrepareResult.Gap(
              GAP_TYPE_MISSING_PRICE,
              planItem.getMaterialCode(),
              "PackageComponentPricePrepareStrategy",
              "包装组件价格准备异常：" + ex.getMessage());
      return PackageComponentPricePrepareResult.notReady(
          ITEM_STATUS_FAILED, "包装组件价格准备异常：" + ex.getMessage(), List.of(gap));
    }
  }

  private MakePartPricePrepareResult prepareMakePart(
      PricePrepareBatch batch,
      NormalizedGenerateRequest req,
      PricePreparePlanItem planItem,
      boolean persist) {
    try {
      if (!persist) {
        return makePartPricePrepareStrategy.calculate(
            req.oaNo(),
            batch.getBusinessUnitType(),
            req.periodMonth(),
            req.priceAsOfTime(),
            req.scenarioContext().forOrganization(planItem.getBomRow().getPriceOrgCode()),
            planItem);
      }
      return makePartPricePrepareStrategy.prepare(
          req.oaNo(),
          batch.getBusinessUnitType(),
          req.periodMonth(),
          req.priceAsOfTime(),
          req.scenarioContext().forOrganization(planItem.getBomRow().getPriceOrgCode()),
          planItem);
    } catch (MonthlyPriceProblem retryableMonthlyPrice) {
      throw retryableMonthlyPrice;
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (RuntimeException ex) {
      MakePartPricePrepareResult.Gap gap =
          new MakePartPricePrepareResult.Gap(
              GAP_TYPE_MISSING_PRICE,
              planItem.getMaterialCode(),
              "MakePartPricePrepareStrategy",
              "自制件价格准备异常：" + ex.getMessage());
      return MakePartPricePrepareResult.notReady(
          ITEM_STATUS_FAILED, "自制件价格准备异常：" + ex.getMessage(), List.of(gap));
    }
  }

  private void applyNormalResult(
      PricePrepareItem item, NormalMaterialPricePrepareResult normalResult) {
    item.setStatus(normalResult.getStatus());
    item.setUnitPrice(normalResult.getUnitPrice());
    item.setAmount(normalResult.getAmount());
    item.setPriceSource(normalResult.getPriceSource());
    item.setPriceType(normalResult.getPriceType());
    item.setResultRefType(normalResult.getResultRefType());
    item.setResultRefId(normalResult.getResultRefId());
    item.setSourcePriceRecordId(normalResult.getSourcePriceRecordId());
    item.setSourcePriceBatchNo(normalResult.getSourcePriceBatchNo());
    item.setInputSnapshotJson(normalResult.getInputSnapshotJson());
    item.setSupplierName(normalResult.getSupplierName());
    item.setSupplierCode(normalResult.getSupplierCode());
    item.setSupplyRatio(normalResult.getSupplyRatio());
    item.setSupplyRatioRecordId(normalResult.getSupplyRatioRecordId());
    item.setSourceEffectiveFrom(normalResult.getSourceEffectiveFrom());
    item.setSourceEffectiveTo(normalResult.getSourceEffectiveTo());
    item.setCarriedForward(normalResult.isCarriedForward() ? 1 : 0);
    item.setWarningMessage(normalResult.getWarningMessage());
    item.setMessage(normalResult.getMessage());
  }

  private void applyPackageResult(
      PricePrepareItem item, PackageComponentPricePrepareResult packageResult) {
    item.setStatus(packageResult.getStatus());
    item.setUnitPrice(packageResult.getUnitPrice());
    item.setAmount(packageResult.getAmount());
    item.setPriceSource(packageResult.getPriceSource());
    item.setInputSnapshotJson(packageResult.getInputSnapshotJson());
    item.setResultRefType(packageResult.getResultRefType());
    item.setResultRefId(packageResult.getResultRefId());
    item.setMessage(packageResult.getMessage());
  }

  private void applyMakePartResult(
      PricePrepareItem item, MakePartPricePrepareResult makePartResult) {
    item.setStatus(makePartResult.getStatus());
    item.setUnitPrice(makePartResult.getUnitPrice());
    item.setAmount(makePartResult.getAmount());
    item.setPriceSource(makePartResult.getPriceSource());
    item.setInputSnapshotJson(makePartResult.getInputSnapshotJson());
    item.setResultRefType(makePartResult.getResultRefType());
    item.setResultRefId(makePartResult.getResultRefId());
    item.setMessage(makePartResult.getMessage());
  }

  private void insertMissingBomGap(
      PricePrepareBatch batch, List<String> topProductCodes, Execution execution) {
    if (topProductCodes != null && !topProductCodes.isEmpty()) {
      for (String topProductCode : topProductCodes) {
        insertMissingBomGap(batch, topProductCode, execution);
      }
      return;
    }
    insertMissingBomGap(batch, (String) null, execution);
  }

  private void insertMissingBomGap(
      PricePrepareBatch batch, String topProductCode, Execution execution) {
    PricePrepareGap gap = new PricePrepareGap();
    applyBatchScope(gap, batch);
    gap.setTopProductCode(blankIfNull(topProductCode));
    gap.setMaterialCode("");
    gap.setGapType(GAP_TYPE_MISSING_STRUCTURE);
    gap.setItemType(PricePrepareItemClassifierImpl.ITEM_TYPE_NORMAL);
    gap.setSourceTable("lp_bom_costing_row");
    gap.setMessage("OA无BOM结算明细，请先生成BOM结算明细");
    gap.setOaPushStatus(GAP_PUSH_PENDING);
    gap.setBusinessUnitType(batch.getBusinessUnitType());
    gap.setActionType(ACTION_MAINTAIN_STRUCTURE);
    gap.setActionTarget(blankIfNull(topProductCode));
    saveGap(gap, execution);
  }

  private void insertNormalPriceGap(
      PricePrepareBatch batch,
      PricePreparePlanItem planItem,
      NormalMaterialPricePrepareResult normalResult,
      Execution execution) {
    PricePrepareGap gap = new PricePrepareGap();
    applyBatchScope(gap, batch);
    gap.setTopProductCode(planItem.getTopProductCode());
    gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
    gap.setGapMaterialCode(planItem.getMaterialCode());
    gap.setMaterialName(planItem.getMaterialName());
    gap.setGapType(normalResult.getGapType());
    gap.setReasonCode(normalResult.getReasonCode());
    gap.setPriceType(normalResult.getPriceType());
    gap.setItemType(planItem.getItemType());
    gap.setSourceTable(normalResult.getSourceTable());
    gap.setMessage(normalResult.getMessage());
    gap.setOaPushStatus(GAP_PUSH_PENDING);
    gap.setBusinessUnitType(batch.getBusinessUnitType());
    gap.setActionType(actionTypeForGap(normalResult.getGapType()));
    gap.setActionTarget(blankIfNull(planItem.getMaterialCode()));
    saveGap(gap, execution);
  }

  private int insertPackageComponentGaps(
      PricePrepareBatch batch,
      PricePreparePlanItem planItem,
      PackageComponentPricePrepareResult packageResult,
      Execution execution) {
    List<PackageComponentPricePrepareResult.Gap> gaps = packageResult.getGaps();
    if (gaps == null || gaps.isEmpty()) {
      PackageComponentPricePrepareResult.Gap fallback =
          new PackageComponentPricePrepareResult.Gap(
              GAP_TYPE_MISSING_PRICE,
              planItem.getMaterialCode(),
              "PackageComponentPricePrepareStrategy",
              packageResult.getMessage());
      gaps = List.of(fallback);
    }
    for (PackageComponentPricePrepareResult.Gap packageGap : gaps) {
      PricePrepareGap gap = new PricePrepareGap();
      applyBatchScope(gap, batch);
      gap.setTopProductCode(planItem.getTopProductCode());
      gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
      gap.setGapMaterialCode(packageGap.getGapMaterialCode());
      gap.setMaterialName(
          firstText(
              packageGap.getMaterialName(),
              sameText(packageGap.getGapMaterialCode(), planItem.getMaterialCode())
                  ? planItem.getMaterialName()
                  : null));
      gap.setGapType(packageGap.getGapType());
      gap.setReasonCode(packageGap.getReasonCode());
      gap.setPriceType(packageGap.getPriceType());
      gap.setItemType(planItem.getItemType());
      gap.setSourceTable(packageGap.getSourceTable());
      gap.setMessage(packageGap.getMessage());
      gap.setOaPushStatus(GAP_PUSH_PENDING);
      gap.setBusinessUnitType(batch.getBusinessUnitType());
      gap.setActionType(actionTypeForGap(packageGap.getGapType()));
      gap.setActionTarget(firstText(packageGap.getGapMaterialCode(), planItem.getMaterialCode()));
      saveGap(gap, execution);
    }
    return gaps.size();
  }

  private int insertMakePartGaps(
      PricePrepareBatch batch,
      PricePreparePlanItem planItem,
      MakePartPricePrepareResult makePartResult,
      Execution execution) {
    List<MakePartPricePrepareResult.Gap> gaps = makePartResult.getGaps();
    if (gaps == null || gaps.isEmpty()) {
      MakePartPricePrepareResult.Gap fallback =
          new MakePartPricePrepareResult.Gap(
              GAP_TYPE_MISSING_STRUCTURE,
              planItem.getMaterialCode(),
              "MakePartPricePrepareStrategy",
              makePartResult.getMessage());
      gaps = List.of(fallback);
    }
    for (MakePartPricePrepareResult.Gap makePartGap : gaps) {
      PricePrepareGap gap = new PricePrepareGap();
      applyBatchScope(gap, batch);
      gap.setTopProductCode(planItem.getTopProductCode());
      gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
      gap.setGapMaterialCode(makePartGap.getGapMaterialCode());
      gap.setMaterialName(
          firstText(
              makePartGap.getMaterialName(),
              sameText(makePartGap.getGapMaterialCode(), planItem.getMaterialCode())
                  ? planItem.getMaterialName()
                  : null));
      gap.setGapType(makePartGap.getGapType());
      gap.setReasonCode(makePartGap.getReasonCode());
      gap.setItemType(planItem.getItemType());
      gap.setSourceTable(makePartGap.getSourceTable());
      gap.setMessage(makePartGap.getMessage());
      gap.setOaPushStatus(GAP_PUSH_PENDING);
      gap.setBusinessUnitType(batch.getBusinessUnitType());
      gap.setActionType(actionTypeForGap(makePartGap.getGapType()));
      gap.setActionTarget(firstText(makePartGap.getGapMaterialCode(), planItem.getMaterialCode()));
      saveGap(gap, execution);
    }
    return gaps.size();
  }

  private void insertMissingMasterGap(
      PricePrepareBatch batch, PricePreparePlanItem planItem, Execution execution) {
    PricePrepareGap gap = new PricePrepareGap();
    applyBatchScope(gap, batch);
    gap.setTopProductCode(planItem.getTopProductCode());
    gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
    gap.setGapMaterialCode(planItem.getMaterialCode());
    gap.setMaterialName(planItem.getMaterialName());
    gap.setGapType(GAP_TYPE_MISSING_MASTER);
    gap.setItemType(planItem.getItemType());
    gap.setSourceTable("lp_material_master_raw/lp_material_master");
    gap.setMessage(planItem.getMessage());
    gap.setOaPushStatus(GAP_PUSH_PENDING);
    gap.setBusinessUnitType(batch.getBusinessUnitType());
    gap.setActionType(ACTION_MAINTAIN_STRUCTURE);
    gap.setActionTarget(blankIfNull(planItem.getMaterialCode()));
    saveGap(gap, execution);
  }

  private void setBatchSummary(
      PricePrepareBatch batch,
      int totalCount,
      int successCount,
      int warningCount,
      int gapCount,
      String status,
      String message) {
    batch.setTotalCount(totalCount);
    batch.setSuccessCount(successCount);
    batch.setWarningCount(warningCount);
    batch.setGapCount(gapCount);
    batch.setStatus(status);
    batch.setMessage(dbMessage(message));
    batch.setFinishedAt(currentTime());
  }

  private void saveItem(PricePrepareItem item, Execution execution) {
    execution.items.add(item);
    if (execution.persist) {
      upsertItem(item);
    }
  }

  private void saveGap(PricePrepareGap gap, Execution execution) {
    execution.gaps.add(gap);
    if (execution.persist) {
      upsertGap(gap);
    }
  }

  private void upsertItem(PricePrepareItem item) {
    normalizeItemForDb(item);
    Long existingId = findExistingItemId(item);
    if (existingId == null) {
      itemMapper.insert(item);
      return;
    }
    item.setId(existingId);
    itemMapper.updateById(item);
  }

  private Long findExistingItemId(PricePrepareItem item) {
    var query =
        Wrappers.<PricePrepareItem>lambdaQuery()
            .eq(PricePrepareItem::getPrepareNo, blankIfNull(item.getPrepareNo()));
    if (StringUtils.hasText(item.getSettlementKey())) {
      query.eq(PricePrepareItem::getSettlementKey, item.getSettlementKey());
    } else {
      // 历史价格准备明细允许 settlement_key 为空，仍沿用旧自然范围兼容查询。
      query
          .eq(PricePrepareItem::getOaNo, blankIfNull(item.getOaNo()))
          .eq(PricePrepareItem::getPeriodMonth, blankIfNull(item.getPeriodMonth()))
          .eq(PricePrepareItem::getTopProductCode, blankIfNull(item.getTopProductCode()))
          .eq(PricePrepareItem::getMaterialCode, blankIfNull(item.getMaterialCode()));
    }
    if (item.getOaFormItemId() != null) {
      query.eq(PricePrepareItem::getOaFormItemId, item.getOaFormItemId());
    }
    List<PricePrepareItem> existingRows =
        itemMapper.selectList(query.orderByDesc(PricePrepareItem::getId).last("LIMIT 1"));
    return existingRows == null || existingRows.isEmpty() ? null : existingRows.get(0).getId();
  }

  private void upsertGap(PricePrepareGap gap) {
    normalizeGapForDb(gap);
    Long existingId = findExistingGapId(gap);
    if (existingId == null) {
      gapMapper.insert(gap);
      return;
    }
    gap.setId(existingId);
    gapMapper.updateById(gap);
  }

  private Long findExistingGapId(PricePrepareGap gap) {
    var query =
        Wrappers.<PricePrepareGap>lambdaQuery()
            .eq(PricePrepareGap::getPrepareNo, blankIfNull(gap.getPrepareNo()))
            .eq(PricePrepareGap::getOaNo, blankIfNull(gap.getOaNo()))
            .eq(PricePrepareGap::getPeriodMonth, blankIfNull(gap.getPeriodMonth()))
            .eq(PricePrepareGap::getTopProductCode, blankIfNull(gap.getTopProductCode()))
            .eq(PricePrepareGap::getMaterialCode, blankIfNull(gap.getMaterialCode()))
            .eq(PricePrepareGap::getGapMaterialCode, blankIfNull(gap.getGapMaterialCode()))
            .eq(PricePrepareGap::getGapType, blankIfNull(gap.getGapType()))
            .eq(PricePrepareGap::getItemType, blankIfNull(gap.getItemType()));
    if (gap.getOaFormItemId() != null) {
      query.eq(PricePrepareGap::getOaFormItemId, gap.getOaFormItemId());
    }
    List<PricePrepareGap> existingRows =
        gapMapper.selectList(query.orderByDesc(PricePrepareGap::getId).last("LIMIT 1"));
    return existingRows == null || existingRows.isEmpty() ? null : existingRows.get(0).getId();
  }

  private String exceptionMessage(RuntimeException ex) {
    return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
  }

  private void normalizeItemForDb(PricePrepareItem item) {
    if (item != null) {
      item.setPeriodMonth(blankIfNull(item.getPeriodMonth()));
      item.setMessage(dbMessage(item.getMessage()));
    }
  }

  private void normalizeGapForDb(PricePrepareGap gap) {
    if (gap != null) {
      gap.setPeriodMonth(blankIfNull(gap.getPeriodMonth()));
      gap.setMessage(dbMessage(gap.getMessage()));
    }
  }

  private String dbMessage(String message) {
    if (message == null || message.length() <= DB_MESSAGE_MAX_LENGTH) {
      return message;
    }
    String suffix = "...(已截断)";
    return message.substring(0, DB_MESSAGE_MAX_LENGTH - suffix.length()) + suffix;
  }

  private BigDecimal quantity(BomCostingRow row) {
    return row == null ? null : row.getQtyPerTop();
  }

  private void applyBatchScope(PricePrepareGap gap, PricePrepareBatch batch) {
    gap.setPrepareNo(batch.getPrepareNo());
    gap.setPeriodMonth(batch.getPeriodMonth());
    gap.setOaNo(batch.getOaNo());
    gap.setOaFormItemId(batch.getOaFormItemId());
    // 缺口也先作为候选写入，失败收尾时才替换当前缺口。
    gap.setCurrentFlag(CURRENT_FLAG_HISTORY);
  }

  private String actionTypeForGap(String gapType) {
    return GAP_TYPE_MISSING_PRICE.equals(gapType)
        ? ACTION_MAINTAIN_PRICE
        : ACTION_MAINTAIN_STRUCTURE;
  }

  private String blankIfNull(String value) {
    return value == null ? "" : value;
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }

  private boolean sameText(String left, String right) {
    String a = firstText(left, null);
    String b = firstText(right, null);
    return a != null && a.equals(b);
  }

  private NormalizedGenerateRequest normalize(
      PricePrepareGenerateRequest request, LocalDateTime batchStartedAt) {
    return normalize(request, batchStartedAt, null);
  }

  private NormalizedGenerateRequest normalize(PricePrepareGenerateRequest request,
      LocalDateTime batchStartedAt, RepriceScope reprice) {
    if (request == null || !StringUtils.hasText(request.getOaNo())) {
      throw new IllegalArgumentException("oaNo is required");
    }
    String periodMonth =
        reprice == null ? CostPricingPeriodUtils.requireCurrentPricingMonth(request.getPeriodMonth())
            : CostPricingPeriodUtils.normalizePricingMonth(request.getPeriodMonth());
    String sourceType =
        StringUtils.hasText(request.getSourceType())
            ? request.getSourceType().trim()
            : DEFAULT_SOURCE_TYPE;
    String businessUnitType =
        firstText(request.getBusinessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType());
    String topProductCode = firstText(request.getTopProductCode(), onlyTopProductCode(request));
    List<String> topProductCodes =
        StringUtils.hasText(topProductCode)
            ? List.of(topProductCode)
            : normalizeTopProductCodes(request.getTopProductCodes());
    Long oaFormItemId = request.getOaFormItemId();
    if (oaFormItemId == null || oaFormItemId <= 0)
      throw new IllegalArgumentException("oaFormItemId is required; 请从报价产品工作台准备价格");
    if (!StringUtils.hasText(topProductCode))
      throw new IllegalArgumentException("topProductCode is required when oaFormItemId is provided");
    var requestedProducts = normalizeTopProductCodes(request.getTopProductCodes());
    if (requestedProducts.size() > 1 || !requestedProducts.isEmpty() && !requestedProducts.getFirst().equals(topProductCode))
      throw new IllegalArgumentException("价格准备必须限定单一报价产品，不能混用 topProductCodes");
    LocalDateTime requestedPriceAsOfTime = request.getPriceAsOfTime();
    LocalDateTime priceAsOfTime =
        requestedPriceAsOfTime == null
            ? batchStartedAt
            : normalizePriceAsOfTime(requestedPriceAsOfTime);
    String priceAsOfSource =
        requestedPriceAsOfTime == null
            ? PRICE_AS_OF_SOURCE_CURRENT_TIME
            : PRICE_AS_OF_SOURCE_REQUEST;
    PricePrepareScenarioContext scenarioContext =
        new PricePrepareScenarioContext(
            request.getScenarioType(),
            request.getScenarioGroupNo(),
            request.getSourcePrepareNo(),
            request.getVariableOverrides(),
            new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(oaFormItemId));
    // BOM 目的按已冻结口径固定主制造，忽略前端或调用方传入值，避免准备结果和结算行口径漂移。
    return new NormalizedGenerateRequest(
        request.getOaNo().trim(),
        oaFormItemId,
        topProductCode,
        topProductCodes,
        periodMonth,
        priceAsOfTime,
        priceAsOfSource,
        businessUnitType,
        DEFAULT_BOM_PURPOSE,
        sourceType,
        scenarioContext, reprice);
  }

  private List<String> normalizeTopProductCodes(List<String> topProductCodes) {
    if (topProductCodes == null || topProductCodes.isEmpty()) {
      return List.of();
    }
    Set<String> codes = new LinkedHashSet<>();
    for (String code : topProductCodes) {
      if (StringUtils.hasText(code)) {
        codes.add(code.trim());
      }
    }
    return List.copyOf(codes);
  }

  private String onlyTopProductCode(PricePrepareGenerateRequest request) {
    List<String> topProductCodes = normalizeTopProductCodes(request.getTopProductCodes());
    return topProductCodes.size() == 1 ? topProductCodes.get(0) : null;
  }

  private PricePrepareGenerateResult toResult(PricePrepareBatch batch) {
    PricePrepareGenerateResult result = new PricePrepareGenerateResult();
    result.setPrepareNo(batch.getPrepareNo());
    result.setOaNo(batch.getOaNo());
    result.setOaFormItemId(batch.getOaFormItemId());
    result.setTopProductCode(batch.getTopProductCode());
    result.setPeriodMonth(batch.getPeriodMonth());
    result.setBomPurpose(batch.getBomPurpose());
    result.setSourceType(batch.getSourceType());
    result.setScenarioType(batch.getScenarioType());
    result.setScenarioGroupNo(batch.getScenarioGroupNo());
    result.setSourcePrepareNo(batch.getSourcePrepareNo());
    result.setStatus(batch.getStatus());
    result.setTotalCount(valueOrZero(batch.getTotalCount()));
    result.setSuccessCount(valueOrZero(batch.getSuccessCount()));
    result.setWarningCount(valueOrZero(batch.getWarningCount()));
    result.setGapCount(valueOrZero(batch.getGapCount()));
    result.setPriceAsOfTime(batch.getPriceAsOfTime());
    result.setPriceAsOfSource(batch.getPriceAsOfSource());
    result.setMessage(batch.getMessage());
    return result;
  }

  private PricePrepareCalculationResult finishExecution(
      PricePrepareBatch batch, Execution execution) {
    if (execution.persist) {
      batchMapper.updateById(batch);
      if (execution.publishOrdinary) currentStateService.finalizeBatch(batch);
    }
    PricePrepareCalculationResult calculation = new PricePrepareCalculationResult();
    calculation.setSupplierPriceReviews(
        batch.getSupplierPriceReviews() == null ? List.of() : batch.getSupplierPriceReviews());
    calculation.setSummary(toResult(batch));
    calculation.setItems(List.copyOf(execution.items));
    calculation.setGaps(List.copyOf(execution.gaps));
    return calculation;
  }

  private String newPrepareNo(LocalDateTime now) {
    return "PPR-"
        + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private LocalDateTime currentTime() {
    return normalizePriceAsOfTime(LocalDateTime.now(clock));
  }

  private LocalDateTime normalizePriceAsOfTime(LocalDateTime value) {
    // 相关价格快照表均为 MySQL DATETIME（秒精度）。若保留纳秒，写入时会被数据库
    // 四舍五入，而同一批次后续仍用原纳秒值查询，导致刚写入的数据查不到甚至重复键。
    return value == null ? null : value.truncatedTo(ChronoUnit.SECONDS);
  }

  private record NormalizedGenerateRequest(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      List<String> topProductCodes,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String priceAsOfSource,
      String businessUnitType,
      String bomPurpose,
      String sourceType,
      PricePrepareScenarioContext scenarioContext, RepriceScope reprice) {}

  private record RepriceScope(com.sanhua.marketingcost.service.reprice.MonthlyRepriceCalculation input,
      String effectiveBuild) {}

  private static final class Execution {
    private final boolean persist;
    private final boolean publishOrdinary;
    private final List<PricePrepareItem> items = new ArrayList<>();
    private final List<PricePrepareGap> gaps = new ArrayList<>();

    private Execution(boolean persist, boolean publishOrdinary) {
      this.persist = persist;
      this.publishOrdinary = publishOrdinary;
    }
  }
}
