package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
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
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
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
  private static final String GAP_PUSH_PENDING = "PENDING";
  private static final int DB_MESSAGE_MAX_LENGTH = 1000;

  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final PricePrepareGapMapper gapMapper;
  private final PricePrepareBomItemLoader bomItemLoader;
  private final PricePrepareItemClassifier itemClassifier;
  private final NormalMaterialPricePrepareStrategy normalMaterialPricePrepareStrategy;
  private final PackageComponentPricePrepareStrategy packageComponentPricePrepareStrategy;
  private final MakePartPricePrepareStrategy makePartPricePrepareStrategy;

  public PricePrepareServiceImpl(
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper,
      PricePrepareBomItemLoader bomItemLoader,
      PricePrepareItemClassifier itemClassifier,
      NormalMaterialPricePrepareStrategy normalMaterialPricePrepareStrategy,
      PackageComponentPricePrepareStrategy packageComponentPricePrepareStrategy,
      MakePartPricePrepareStrategy makePartPricePrepareStrategy) {
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
    this.bomItemLoader = bomItemLoader;
    this.itemClassifier = itemClassifier;
    this.normalMaterialPricePrepareStrategy = normalMaterialPricePrepareStrategy;
    this.packageComponentPricePrepareStrategy = packageComponentPricePrepareStrategy;
    this.makePartPricePrepareStrategy = makePartPricePrepareStrategy;
  }

  @Override
  public PricePrepareGenerateResult generate(PricePrepareGenerateRequest request) {
    NormalizedGenerateRequest req = normalize(request);
    LocalDateTime now = LocalDateTime.now();

    PricePrepareBatch batch = initBatch(req, now);
    deleteCurrentItems(req.oaNo(), req.topProductCodes());
    deleteCurrentGaps(req.oaNo(), req.topProductCodes());

    List<BomCostingRow> bomRows;
    try {
      bomRows = req.topProductCodes().isEmpty()
          ? bomItemLoader.loadByOaNo(req.oaNo())
          : bomItemLoader.loadByOaNoAndTopProducts(req.oaNo(), req.topProductCodes());
    } catch (RuntimeException ex) {
      setBatchSummary(batch, 0, 0, 0, 0, STATUS_FAILED, "读取BOM结算明细失败：" + exceptionMessage(ex));
      return finishBatchAndResult(batch);
    }
    if (bomRows == null || bomRows.isEmpty()) {
      insertMissingBomGap(batch, req.topProductCodes());
      int missingBomGapCount = req.topProductCodes().isEmpty() ? 1 : req.topProductCodes().size();
      setBatchSummary(batch, 0, 0, 0, missingBomGapCount, STATUS_PARTIAL, "OA无BOM结算明细，已写入价格准备缺口");
      return finishBatchAndResult(batch);
    }

    List<PricePreparePlanItem> planItems;
    try {
      planItems = itemClassifier.classify(bomRows);
    } catch (RuntimeException ex) {
      setBatchSummary(batch, 0, 0, 0, 0, STATUS_FAILED, "价格准备分类失败：" + exceptionMessage(ex));
      return finishBatchAndResult(batch);
    }
    if (planItems == null) {
      setBatchSummary(batch, 0, 0, 0, 0, STATUS_FAILED, "价格准备分类失败：分类结果为空");
      return finishBatchAndResult(batch);
    }
    planItems = mergePlanItems(planItems);
    int successCount = 0;
    int gapCount = 0;
    for (PricePreparePlanItem planItem : planItems) {
      PricePrepareItem item = buildPrepareItem(batch, planItem);
      if (ITEM_STATUS_MISSING_MASTER.equals(planItem.getStatus())) {
        upsertItem(item);
        gapCount++;
        insertMissingMasterGap(batch, planItem);
      } else if (isReadyPackageComponent(planItem)) {
        PackageComponentPricePrepareResult packageResult =
            preparePackageComponent(batch, req, planItem);
        applyPackageResult(item, packageResult);
        upsertItem(item);
        if (ITEM_STATUS_READY.equals(packageResult.getStatus())) {
          successCount++;
        } else {
          gapCount += insertPackageComponentGaps(batch, planItem, packageResult);
        }
      } else if (isReadyMakePart(planItem)) {
        MakePartPricePrepareResult makePartResult =
            prepareMakePart(batch, req, planItem);
        applyMakePartResult(item, makePartResult);
        upsertItem(item);
        if (ITEM_STATUS_READY.equals(makePartResult.getStatus())) {
          successCount++;
        } else {
          gapCount += insertMakePartGaps(batch, planItem, makePartResult);
        }
      } else if (isReadyNormalItem(planItem)) {
        NormalMaterialPricePrepareResult normalResult =
            prepareNormalMaterial(batch, req, planItem);
        applyNormalResult(item, normalResult);
        upsertItem(item);
        if (ITEM_STATUS_READY.equals(normalResult.getStatus())) {
          successCount++;
        } else {
          gapCount++;
          insertNormalPriceGap(batch, planItem, normalResult);
        }
      } else {
        upsertItem(item);
        successCount++;
      }
    }
    String status = gapCount > 0 ? STATUS_PARTIAL : STATUS_SUCCESS;
    String message = gapCount > 0
        ? "已读取BOM结算明细并完成价格准备，存在待补充缺口"
        : "已读取BOM结算明细并完成价格准备";
    setBatchSummary(batch, planItems.size(), successCount, 0, gapCount, status, message);
    return finishBatchAndResult(batch);
  }

  private List<PricePreparePlanItem> mergePlanItems(List<PricePreparePlanItem> planItems) {
    Map<String, PricePreparePlanItem> merged = new LinkedHashMap<>();
    Map<String, BigDecimal> quantities = new LinkedHashMap<>();
    for (PricePreparePlanItem planItem : planItems) {
      if (planItem == null) {
        continue;
      }
      String key = currentItemKey(planItem);
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

  private PricePrepareBatch initBatch(NormalizedGenerateRequest req, LocalDateTime now) {
    PricePrepareBatch batch = findCurrentBatch(req.oaNo(), req.periodMonth());
    boolean exists = batch != null;
    if (!exists) {
      batch = new PricePrepareBatch();
      batch.setPrepareNo(newPrepareNo(now));
      batch.setOaNo(req.oaNo());
      batch.setPeriodMonth(req.periodMonth());
    } else if (!StringUtils.hasText(batch.getPrepareNo())) {
      batch.setPrepareNo(newPrepareNo(now));
    }
    batch.setBomPurpose(req.bomPurpose());
    batch.setSourceType(req.sourceType());
    batch.setStatus(STATUS_RUNNING);
    batch.setTotalCount(0);
    batch.setSuccessCount(0);
    batch.setWarningCount(0);
    batch.setGapCount(0);
    batch.setStartedAt(now);
    batch.setFinishedAt(null);
    // 价格准备必须在实时成本前显式执行；自制件只消费制造件价格生成结果，不回退旧人工价。
    batch.setMessage(dbMessage("准备读取BOM结算明细"));
    batch.setBusinessUnitType(req.businessUnitType());
    if (exists) {
      batchMapper.updateById(batch);
    } else {
      batchMapper.insert(batch);
    }
    return batch;
  }

  private PricePrepareBatch findCurrentBatch(String oaNo, String periodMonth) {
    return batchMapper.selectOne(
        Wrappers.<PricePrepareBatch>lambdaQuery()
            .eq(PricePrepareBatch::getOaNo, blankIfNull(oaNo))
            .eq(PricePrepareBatch::getPeriodMonth, blankIfNull(periodMonth))
            .orderByDesc(PricePrepareBatch::getId)
            .last("LIMIT 1"));
  }

  private String currentItemKey(PricePreparePlanItem planItem) {
    return blankIfNull(planItem.getTopProductCode()) + "|" + blankIfNull(planItem.getMaterialCode());
  }

  private PricePrepareItem buildPrepareItem(PricePrepareBatch batch, PricePreparePlanItem planItem) {
    PricePrepareItem item = new PricePrepareItem();
    item.setPrepareNo(batch.getPrepareNo());
    item.setOaNo(batch.getOaNo());
    item.setTopProductCode(planItem.getTopProductCode());
    item.setBomRowId(planItem.getBomRowId());
    item.setMaterialCode(planItem.getMaterialCode());
    item.setMaterialName(planItem.getMaterialName());
    item.setItemType(planItem.getItemType());
    item.setQuantity(quantity(planItem.getBomRow()));
    item.setStatus(planItem.getStatus());
    item.setMessage(planItem.getMessage());
    item.setBusinessUnitType(batch.getBusinessUnitType());
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
      PricePrepareBatch batch, NormalizedGenerateRequest req, PricePreparePlanItem planItem) {
    try {
      return packageComponentPricePrepareStrategy.prepare(
          batch.getPrepareNo(),
          req.oaNo(),
          req.periodMonth(),
          req.priceAsOfTime(),
          req.bomPurpose(),
          req.sourceType(),
          planItem);
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

  private NormalMaterialPricePrepareResult prepareNormalMaterial(
      PricePrepareBatch batch, NormalizedGenerateRequest req, PricePreparePlanItem planItem) {
    try {
      return normalMaterialPricePrepareStrategy.prepare(
          req.oaNo(), batch.getBusinessUnitType(), req.periodMonth(), planItem);
    } catch (RuntimeException ex) {
      return NormalMaterialPricePrepareResult.gap(
          ITEM_STATUS_FAILED,
          GAP_TYPE_MISSING_PRICE,
          "ERROR",
          "NormalMaterialPricePrepareStrategy",
          "普通料号价格准备异常：" + ex.getMessage());
    }
  }

  private MakePartPricePrepareResult prepareMakePart(
      PricePrepareBatch batch, NormalizedGenerateRequest req, PricePreparePlanItem planItem) {
    try {
      return makePartPricePrepareStrategy.prepare(
          req.oaNo(), batch.getBusinessUnitType(), req.periodMonth(), req.priceAsOfTime(), planItem);
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
    item.setResultRefType(normalResult.getResultRefType());
    item.setResultRefId(normalResult.getResultRefId());
    item.setMessage(normalResult.getMessage());
  }

  private void applyPackageResult(
      PricePrepareItem item, PackageComponentPricePrepareResult packageResult) {
    item.setStatus(packageResult.getStatus());
    item.setUnitPrice(packageResult.getUnitPrice());
    item.setAmount(packageResult.getAmount());
    item.setPriceSource(packageResult.getPriceSource());
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
    item.setResultRefType(makePartResult.getResultRefType());
    item.setResultRefId(makePartResult.getResultRefId());
    item.setMessage(makePartResult.getMessage());
  }

  private void insertMissingBomGap(PricePrepareBatch batch, List<String> topProductCodes) {
    if (topProductCodes != null && !topProductCodes.isEmpty()) {
      for (String topProductCode : topProductCodes) {
        insertMissingBomGap(batch, topProductCode);
      }
      return;
    }
    insertMissingBomGap(batch, (String) null);
  }

  private void insertMissingBomGap(PricePrepareBatch batch, String topProductCode) {
    PricePrepareGap gap = new PricePrepareGap();
    gap.setPrepareNo(batch.getPrepareNo());
    gap.setOaNo(batch.getOaNo());
    gap.setTopProductCode(blankIfNull(topProductCode));
    gap.setMaterialCode("");
    gap.setGapType(GAP_TYPE_MISSING_STRUCTURE);
    gap.setItemType(PricePrepareItemClassifierImpl.ITEM_TYPE_NORMAL);
    gap.setSourceTable("lp_bom_costing_row");
    gap.setMessage("OA无BOM结算明细，请先生成BOM结算明细");
    gap.setOaPushStatus(GAP_PUSH_PENDING);
    gap.setBusinessUnitType(batch.getBusinessUnitType());
    upsertGap(gap);
  }

  private void insertNormalPriceGap(
      PricePrepareBatch batch,
      PricePreparePlanItem planItem,
      NormalMaterialPricePrepareResult normalResult) {
    PricePrepareGap gap = new PricePrepareGap();
    gap.setPrepareNo(batch.getPrepareNo());
    gap.setOaNo(batch.getOaNo());
    gap.setTopProductCode(planItem.getTopProductCode());
    gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
    gap.setGapMaterialCode(planItem.getMaterialCode());
    gap.setGapType(normalResult.getGapType());
    gap.setItemType(planItem.getItemType());
    gap.setSourceTable(normalResult.getSourceTable());
    gap.setMessage(normalResult.getMessage());
    gap.setOaPushStatus(GAP_PUSH_PENDING);
    gap.setBusinessUnitType(batch.getBusinessUnitType());
    upsertGap(gap);
  }

  private int insertPackageComponentGaps(
      PricePrepareBatch batch,
      PricePreparePlanItem planItem,
      PackageComponentPricePrepareResult packageResult) {
    List<PackageComponentPricePrepareResult.Gap> gaps = packageResult.getGaps();
    if (gaps == null || gaps.isEmpty()) {
      PackageComponentPricePrepareResult.Gap fallback = new PackageComponentPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          planItem.getMaterialCode(),
          "PackageComponentPricePrepareStrategy",
          packageResult.getMessage());
      gaps = List.of(fallback);
    }
    for (PackageComponentPricePrepareResult.Gap packageGap : gaps) {
      PricePrepareGap gap = new PricePrepareGap();
      gap.setPrepareNo(batch.getPrepareNo());
      gap.setOaNo(batch.getOaNo());
      gap.setTopProductCode(planItem.getTopProductCode());
      gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
      gap.setGapMaterialCode(packageGap.getGapMaterialCode());
      gap.setGapType(packageGap.getGapType());
      gap.setItemType(planItem.getItemType());
      gap.setSourceTable(packageGap.getSourceTable());
      gap.setMessage(packageGap.getMessage());
      gap.setOaPushStatus(GAP_PUSH_PENDING);
      gap.setBusinessUnitType(batch.getBusinessUnitType());
      upsertGap(gap);
    }
    return gaps.size();
  }

  private int insertMakePartGaps(
      PricePrepareBatch batch,
      PricePreparePlanItem planItem,
      MakePartPricePrepareResult makePartResult) {
    List<MakePartPricePrepareResult.Gap> gaps = makePartResult.getGaps();
    if (gaps == null || gaps.isEmpty()) {
      MakePartPricePrepareResult.Gap fallback = new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          planItem.getMaterialCode(),
          "MakePartPricePrepareStrategy",
          makePartResult.getMessage());
      gaps = List.of(fallback);
    }
    for (MakePartPricePrepareResult.Gap makePartGap : gaps) {
      PricePrepareGap gap = new PricePrepareGap();
      gap.setPrepareNo(batch.getPrepareNo());
      gap.setOaNo(batch.getOaNo());
      gap.setTopProductCode(planItem.getTopProductCode());
      gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
      gap.setGapMaterialCode(makePartGap.getGapMaterialCode());
      gap.setGapType(makePartGap.getGapType());
      gap.setItemType(planItem.getItemType());
      gap.setSourceTable(makePartGap.getSourceTable());
      gap.setMessage(makePartGap.getMessage());
      gap.setOaPushStatus(GAP_PUSH_PENDING);
      gap.setBusinessUnitType(batch.getBusinessUnitType());
      upsertGap(gap);
    }
    return gaps.size();
  }

  private void insertMissingMasterGap(PricePrepareBatch batch, PricePreparePlanItem planItem) {
    PricePrepareGap gap = new PricePrepareGap();
    gap.setPrepareNo(batch.getPrepareNo());
    gap.setOaNo(batch.getOaNo());
    gap.setTopProductCode(planItem.getTopProductCode());
    gap.setMaterialCode(blankIfNull(planItem.getMaterialCode()));
    gap.setGapMaterialCode(planItem.getMaterialCode());
    gap.setGapType(GAP_TYPE_MISSING_MASTER);
    gap.setItemType(planItem.getItemType());
    gap.setSourceTable("lp_material_master_raw/lp_material_master");
    gap.setMessage(planItem.getMessage());
    gap.setOaPushStatus(GAP_PUSH_PENDING);
    gap.setBusinessUnitType(batch.getBusinessUnitType());
    upsertGap(gap);
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
    batch.setFinishedAt(LocalDateTime.now());
  }

  private void deleteCurrentGaps(String oaNo, List<String> topProductCodes) {
    var query =
        Wrappers.<PricePrepareGap>lambdaQuery()
            .eq(PricePrepareGap::getOaNo, blankIfNull(oaNo));
    if (topProductCodes != null && !topProductCodes.isEmpty()) {
      query.in(PricePrepareGap::getTopProductCode, topProductCodes);
    }
    gapMapper.delete(query);
  }

  private void deleteCurrentItems(String oaNo, List<String> topProductCodes) {
    var query =
        Wrappers.<PricePrepareItem>lambdaQuery()
            .eq(PricePrepareItem::getOaNo, blankIfNull(oaNo));
    if (topProductCodes != null && !topProductCodes.isEmpty()) {
      query.in(PricePrepareItem::getTopProductCode, topProductCodes);
    }
    itemMapper.delete(query);
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
    List<PricePrepareItem> existingRows =
        itemMapper.selectList(
            Wrappers.<PricePrepareItem>lambdaQuery()
                .eq(PricePrepareItem::getOaNo, blankIfNull(item.getOaNo()))
                .eq(PricePrepareItem::getTopProductCode, blankIfNull(item.getTopProductCode()))
                .eq(PricePrepareItem::getMaterialCode, blankIfNull(item.getMaterialCode()))
                .orderByDesc(PricePrepareItem::getId)
                .last("LIMIT 1"));
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
    List<PricePrepareGap> existingRows =
        gapMapper.selectList(
            Wrappers.<PricePrepareGap>lambdaQuery()
                .eq(PricePrepareGap::getOaNo, blankIfNull(gap.getOaNo()))
                .eq(PricePrepareGap::getTopProductCode, blankIfNull(gap.getTopProductCode()))
                .eq(PricePrepareGap::getMaterialCode, blankIfNull(gap.getMaterialCode()))
                .eq(PricePrepareGap::getGapMaterialCode, blankIfNull(gap.getGapMaterialCode()))
                .eq(PricePrepareGap::getGapType, blankIfNull(gap.getGapType()))
                .eq(PricePrepareGap::getItemType, blankIfNull(gap.getItemType()))
                .orderByDesc(PricePrepareGap::getId)
                .last("LIMIT 1"));
    return existingRows == null || existingRows.isEmpty() ? null : existingRows.get(0).getId();
  }

  private String exceptionMessage(RuntimeException ex) {
    return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
  }

  private void normalizeItemForDb(PricePrepareItem item) {
    if (item != null) {
      item.setMessage(dbMessage(item.getMessage()));
    }
  }

  private void normalizeGapForDb(PricePrepareGap gap) {
    if (gap != null) {
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

  private String blankIfNull(String value) {
    return value == null ? "" : value;
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }

  private NormalizedGenerateRequest normalize(PricePrepareGenerateRequest request) {
    if (request == null || !StringUtils.hasText(request.getOaNo())) {
      throw new IllegalArgumentException("oaNo is required");
    }
    String periodMonth = StringUtils.hasText(request.getPeriodMonth())
        ? request.getPeriodMonth().trim()
        : YearMonth.now().toString();
    String sourceType = StringUtils.hasText(request.getSourceType())
        ? request.getSourceType().trim()
        : DEFAULT_SOURCE_TYPE;
    String businessUnitType = firstText(
        request.getBusinessUnitType(),
        BusinessUnitContext.getCurrentBusinessUnitType());
    // BOM 目的按已冻结口径固定主制造，忽略前端或调用方传入值，避免准备结果和结算行口径漂移。
    return new NormalizedGenerateRequest(
        request.getOaNo().trim(),
        normalizeTopProductCodes(request.getTopProductCodes()),
        periodMonth,
        request.getPriceAsOfTime(),
        businessUnitType,
        DEFAULT_BOM_PURPOSE,
        sourceType);
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

  private PricePrepareGenerateResult toResult(PricePrepareBatch batch) {
    PricePrepareGenerateResult result = new PricePrepareGenerateResult();
    result.setPrepareNo(batch.getPrepareNo());
    result.setOaNo(batch.getOaNo());
    result.setPeriodMonth(batch.getPeriodMonth());
    result.setBomPurpose(batch.getBomPurpose());
    result.setSourceType(batch.getSourceType());
    result.setStatus(batch.getStatus());
    result.setTotalCount(valueOrZero(batch.getTotalCount()));
    result.setSuccessCount(valueOrZero(batch.getSuccessCount()));
    result.setWarningCount(valueOrZero(batch.getWarningCount()));
    result.setGapCount(valueOrZero(batch.getGapCount()));
    result.setMessage(batch.getMessage());
    return result;
  }

  private PricePrepareGenerateResult finishBatchAndResult(PricePrepareBatch batch) {
    batchMapper.updateById(batch);
    return toResult(batch);
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

  private record NormalizedGenerateRequest(
      String oaNo,
      List<String> topProductCodes,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String businessUnitType,
      String bomPurpose,
      String sourceType) {}
}
