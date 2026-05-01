package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.CostRunTrialService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class CostRunTrialServiceImpl implements CostRunTrialService {
  private static final Logger log = LoggerFactory.getLogger(CostRunTrialServiceImpl.class);
  private static final long PROGRESS_CLEANUP_DELAY_MINUTES = 5;
  /** T15：主档同步完成时的进度节点（同步失败卡这里 + 红字） */
  private static final int PROGRESS_AFTER_SYNC = 5;
  /** T23：联动价 refresh 完成时的进度节点 */
  private static final int PROGRESS_AFTER_LINKED_REFRESH = 10;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final CostRunPartItemService costRunPartItemService;
  private final CostRunCostItemService costRunCostItemService;
  private final CostRunResultService costRunResultService;
  private final CostRunProgressStore progressStore;
  private final TransactionTemplate transactionTemplate;
  /** T15：主档同步入口，doRun 第一步调用 */
  private final MaterialMasterSyncService materialMasterSyncService;
  /** T23：联动价 refresh 入口，doRun 第二步调用，确保 calc_item 用当前 OA 表头锁价实算 */
  private final PriceLinkedCalcService priceLinkedCalcService;
  private final ScheduledExecutorService cleanupScheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "progress-cleanup");
        t.setDaemon(true);
        return t;
      });

  @jakarta.annotation.PreDestroy
  void shutdownScheduler() {
    cleanupScheduler.shutdownNow();
  }

  public CostRunTrialServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      CostRunPartItemService costRunPartItemService,
      CostRunCostItemService costRunCostItemService,
      CostRunResultService costRunResultService,
      CostRunProgressStore progressStore,
      TransactionTemplate transactionTemplate,
      MaterialMasterSyncService materialMasterSyncService,
      PriceLinkedCalcService priceLinkedCalcService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.costRunPartItemService = costRunPartItemService;
    this.costRunCostItemService = costRunCostItemService;
    this.costRunResultService = costRunResultService;
    this.progressStore = progressStore;
    this.transactionTemplate = transactionTemplate;
    this.materialMasterSyncService = materialMasterSyncService;
    this.priceLinkedCalcService = priceLinkedCalcService;
  }

  @Override
  @Async("costRunExecutor")
  public CompletableFuture<CostRunTrialResponse> run(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CompletableFuture.completedFuture(new CostRunTrialResponse());
    }
    String oaNoValue = oaNo.trim();
    // T17：worker 接到 task → QUEUED 改 RUNNING（防重已在 controller.enqueue 做过）
    progressStore.markRunning(oaNoValue);
    try {
      CostRunTrialResponse response = transactionTemplate.execute(status -> doRun(oaNoValue));
      progressStore.complete(oaNoValue);
      scheduleCleanup(oaNoValue);
      return CompletableFuture.completedFuture(response);
    } catch (RuntimeException ex) {
      progressStore.fail(oaNoValue, ex.getMessage());
      scheduleCleanup(oaNoValue);
      return CompletableFuture.failedFuture(ex);
    }
  }

  private CostRunTrialResponse doRun(String oaNoValue) {
    // T15：第一步同步主档（独立子事务），失败抛出由 run() 的 catch 落到 progressStore.fail
    try {
      MaterialMasterSyncService.SyncResult syncResult =
          materialMasterSyncService.syncByOaNo(oaNoValue);
      log.info(
          "T15 sync done: oa={} codes={} stagingHits={} affected={}",
          oaNoValue, syncResult.distinctCodes(), syncResult.stagingHits(), syncResult.affectedRows());
      progressStore.update(oaNoValue, PROGRESS_AFTER_SYNC);
    } catch (RuntimeException syncEx) {
      log.error("T15 主档同步失败: oa={}", oaNoValue, syncEx);
      throw new RuntimeException("主档同步失败: " + syncEx.getMessage(), syncEx);
    }

    // T23：第二步刷新联动价 calc_item，让 trial 用当前 OA 表头锁价 + 最新基价的实算结果，
    //      避免取到历史 calc 快照（之前 OA 表头铜价改了但 calc_item 还是老值的 bug）。
    //      跟 admin "联动价计算 -> 刷新" 按钮调的同一个 API，幂等。
    try {
      int refreshedRows = priceLinkedCalcService.refresh(oaNoValue);
      log.info("T23 联动价 refresh done: oa={} refreshed={}", oaNoValue, refreshedRows);
      progressStore.update(oaNoValue, PROGRESS_AFTER_LINKED_REFRESH);
    } catch (RuntimeException refreshEx) {
      log.error("T23 联动价 refresh 失败: oa={}", oaNoValue, refreshEx);
      throw new RuntimeException("联动价刷新失败: " + refreshEx.getMessage(), refreshEx);
    }

    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNoValue).last("LIMIT 1"));
    if (form == null) {
      throw new RuntimeException("OA单号不存在");
    }

    List<OaFormItem> formItems =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class).eq(OaFormItem::getOaFormId, form.getId()));
    if (formItems.isEmpty()) {
      throw new RuntimeException("OA单号未关联产品明细");
    }

    Set<String> productCodes = new LinkedHashSet<>();
    Map<String, OaFormItem> productItems = new LinkedHashMap<>();
    Map<String, Set<String>> materialCodesByProduct = new LinkedHashMap<>();
    for (OaFormItem item : formItems) {
      if (StringUtils.hasText(item.getMaterialNo())) {
        String code = item.getMaterialNo().trim();
        productCodes.add(code);
        productItems.putIfAbsent(code, item);
        materialCodesByProduct
            .computeIfAbsent(code, (key) -> new LinkedHashSet<>())
            .add(code);
      }
    }

    // T16/T23：进度切片
    //   [0-5]    主档同步
    //   [5-10]   联动价 refresh（T23）
    //   [10-60]  部品取价（50% 跨度，子进度按部品 i/N 累加）
    //   [60-95]  N 个产品的费用核算（35% 跨度，每产品 35/N）
    //   [95-100] saveOrUpdate + OA 状态更新
    final int PROGRESS_PARTS_END = 60;
    final int PROGRESS_COSTS_END = 95;
    int productCount = Math.max(1, productCodes.size());

    List<CostRunPartItemDto> partItems =
        productCodes.isEmpty()
            ? Collections.emptyList()
            : costRunPartItemService.listByOaNo(
                oaNoValue,
                p -> progressStore.update(
                    oaNoValue,
                    PROGRESS_AFTER_LINKED_REFRESH
                        + p * (PROGRESS_PARTS_END - PROGRESS_AFTER_LINKED_REFRESH) / 100));
    progressStore.update(oaNoValue, PROGRESS_PARTS_END);

    int costItemCount = 0;
    int productIndex = 0;
    for (String productCode : productCodes) {
      final int idx = productIndex; // for lambda
      int productStart =
          PROGRESS_PARTS_END + idx * (PROGRESS_COSTS_END - PROGRESS_PARTS_END) / productCount;
      int productEnd =
          PROGRESS_PARTS_END + (idx + 1) * (PROGRESS_COSTS_END - PROGRESS_PARTS_END) / productCount;
      Set<String> materialCodes =
          materialCodesByProduct.getOrDefault(productCode, Collections.emptySet());
      List<CostRunCostItemDto> costItems =
          costRunCostItemService.listByMaterialCodes(
              oaNoValue, productCode, materialCodes,
              p -> progressStore.update(
                  oaNoValue, productStart + p * (productEnd - productStart) / 100));
      costItemCount += costItems.size();
      java.math.BigDecimal totalCost = null;
      for (CostRunCostItemDto item : costItems) {
        if ("TOTAL".equals(item.getCostCode())) {
          totalCost = item.getAmount();
          break;
        }
      }
      costRunResultService.updateTotalCost(oaNoValue, productCode, totalCost);
      OaFormItem item = productItems.get(productCode);
      if (item != null) {
        costRunResultService.saveOrUpdate(form, item);
      }
      productIndex++;
    }
    progressStore.update(oaNoValue, PROGRESS_COSTS_END);

    oaFormMapper.update(
        null,
        Wrappers.lambdaUpdate(OaForm.class)
            .set(OaForm::getCalcStatus, "已核算")
            .eq(OaForm::getId, form.getId()));
    return new CostRunTrialResponse(productCodes.size(), partItems.size(), costItemCount);
  }

  @Override
  public CostRunProgressResponse progress(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return progressStore.get(null);
    }
    return progressStore.get(oaNo.trim());
  }

  /** 延迟清理进度记录，给前端足够时间轮询到最终状态。 */
  private void scheduleCleanup(String oaNo) {
    cleanupScheduler.schedule(
        () -> progressStore.remove(oaNo),
        PROGRESS_CLEANUP_DELAY_MINUTES,
        TimeUnit.MINUTES);
  }
}
