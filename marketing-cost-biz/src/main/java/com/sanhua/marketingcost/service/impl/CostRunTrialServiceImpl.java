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
  private static final long PROGRESS_CLEANUP_DELAY_MINUTES = 5;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final CostRunPartItemService costRunPartItemService;
  private final CostRunCostItemService costRunCostItemService;
  private final CostRunResultService costRunResultService;
  private final CostRunProgressStore progressStore;
  private final TransactionTemplate transactionTemplate;
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
      TransactionTemplate transactionTemplate) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.costRunPartItemService = costRunPartItemService;
    this.costRunCostItemService = costRunCostItemService;
    this.costRunResultService = costRunResultService;
    this.progressStore = progressStore;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  @Async("costRunExecutor")
  public CompletableFuture<CostRunTrialResponse> run(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CompletableFuture.completedFuture(new CostRunTrialResponse());
    }
    String oaNoValue = oaNo.trim();
    // 并发防重：同一 OA 单号正在试算中则拒绝
    if (!progressStore.start(oaNoValue)) {
      CostRunTrialResponse busy = new CostRunTrialResponse();
      return CompletableFuture.completedFuture(busy);
    }
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

    int totalSteps = Math.max(1, 1 + productCodes.size());
    int doneSteps = 0;
    List<CostRunPartItemDto> partItems =
        productCodes.isEmpty()
            ? Collections.emptyList()
            : costRunPartItemService.listByOaNo(oaNoValue);
    doneSteps += 1;
    progressStore.update(oaNoValue, calcPercent(doneSteps, totalSteps));

    int costItemCount = 0;
    for (String productCode : productCodes) {
      Set<String> materialCodes =
          materialCodesByProduct.getOrDefault(productCode, Collections.emptySet());
      List<CostRunCostItemDto> costItems =
          costRunCostItemService.listByMaterialCodes(oaNoValue, productCode, materialCodes);
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
      doneSteps += 1;
      progressStore.update(oaNoValue, calcPercent(doneSteps, totalSteps));
    }

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

  private int calcPercent(int doneSteps, int totalSteps) {
    if (totalSteps <= 0) {
      return 100;
    }
    double ratio = (double) doneSteps / (double) totalSteps;
    return Math.max(0, Math.min(100, (int) Math.round(ratio * 100)));
  }

  /** 延迟清理进度记录，给前端足够时间轮询到最终状态。 */
  private void scheduleCleanup(String oaNo) {
    cleanupScheduler.schedule(
        () -> progressStore.remove(oaNo),
        PROGRESS_CLEANUP_DELAY_MINUTES,
        TimeUnit.MINUTES);
  }
}
