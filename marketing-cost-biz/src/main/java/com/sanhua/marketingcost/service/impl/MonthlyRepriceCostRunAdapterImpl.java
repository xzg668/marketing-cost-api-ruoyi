package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.MonthlyRepriceCostRunAdapter;
import com.sanhua.marketingcost.service.MonthlyRepriceResultWriter;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceCostRunAdapterImpl implements MonthlyRepriceCostRunAdapter {

  private static final String PRICE_PREPARE_SUCCESS = "SUCCESS";
  private static final String PRICE_PREPARE_PARTIAL = "PARTIAL";
  private static final String PRICE_SOURCE_ERROR = "ERROR";
  private static final String PRICE_SOURCE_NO_ROUTE = "NO_ROUTE";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final PricePrepareService pricePrepareService;
  private final CostRunEngine costRunEngine;
  private final MonthlyRepriceResultWriter resultWriter;

  public MonthlyRepriceCostRunAdapterImpl(
      MonthlyRepriceBatchMapper batchMapper,
      PricePrepareService pricePrepareService,
      CostRunEngine costRunEngine,
      MonthlyRepriceResultWriter resultWriter) {
    this.batchMapper = batchMapper;
    this.pricePrepareService = pricePrepareService;
    this.costRunEngine = costRunEngine;
    this.resultWriter = resultWriter;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CostRunObjectResult execute(CostRunTask task, String workerId) {
    validate(task);
    if (!StringUtils.hasText(workerId)) {
      throw new IllegalArgumentException("workerId 不能为空");
    }
    MonthlyRepriceBatch batch = findBatch(task.getSourceNo());
    CostRunContext context =
        CostRunContext.monthlyReprice(
            firstText(task.getPricingMonth(), batch.getPricingMonth()),
            batch.getAdjustBatchId(),
            task.getSourceNo(),
            firstText(task.getBusinessUnitType(), batch.getBusinessUnitType()),
            resolvePriceAsOfTime(batch),
            firstText(batch.getBomSourcePolicy(), CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM),
            task.getOaNo(),
            task.getOaFormItemId(),
            task.getProductCode(),
            task.getPackageMethod(),
            task.getCustomerName(),
            task.getCalcObjectKey());
    ensurePricePrepared(task, batch, context);
    CostRunObjectResult result = costRunEngine.run(context);
    validateMonthlyResult(result);
    resultWriter.write(result);
    return result;
  }

  private void validate(CostRunTask task) {
    if (task == null) {
      throw new IllegalArgumentException("月度调价任务不能为空");
    }
    if (task.getId() == null) {
      throw new IllegalArgumentException("月度调价任务 ID 不能为空");
    }
    if (!StringUtils.hasText(task.getSourceNo())
        || !StringUtils.hasText(task.getOaNo())
        || !StringUtils.hasText(task.getProductCode())
        || !StringUtils.hasText(task.getCalcObjectKey())) {
      throw new IllegalArgumentException("月度调价任务缺少必要核算字段");
    }
  }

  private MonthlyRepriceBatch findBatch(String repriceNo) {
    MonthlyRepriceBatch batch = batchMapper.selectOne(
        Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
            .eq(MonthlyRepriceBatch::getRepriceNo, repriceNo)
            .last("LIMIT 1"));
    if (batch == null) {
      throw new IllegalStateException("月度调价批次不存在：" + repriceNo);
    }
    return batch;
  }

  private LocalDateTime resolvePriceAsOfTime(MonthlyRepriceBatch batch) {
    if (batch.getPriceAsOfTime() != null) {
      return batch.getPriceAsOfTime();
    }
    if (batch.getCreatedAt() != null) {
      return batch.getCreatedAt();
    }
    throw new IllegalStateException("月度调价批次缺少取价时点：" + batch.getRepriceNo());
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }

  private void ensurePricePrepared(
      CostRunTask task, MonthlyRepriceBatch batch, CostRunContext context) {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo(task.getOaNo());
    request.setTopProductCodes(List.of(task.getProductCode()));
    request.setPeriodMonth(context.getPricingMonth());
    request.setPriceAsOfTime(context.getPriceAsOfTime());
    request.setBusinessUnitType(context.getBusinessUnitType());
    request.setBomPurpose(context.getBomSourcePolicy());
    request.setSourceType("U9");
    PricePrepareGenerateResult prepareResult = pricePrepareService.generate(request);
    if (prepareResult == null) {
      throw new IllegalStateException("月度调价价格准备无返回结果：" + batch.getRepriceNo());
    }
    String status = prepareResult.getStatus();
    if (!PRICE_PREPARE_SUCCESS.equalsIgnoreCase(status)
        && !PRICE_PREPARE_PARTIAL.equalsIgnoreCase(status)) {
      throw new IllegalStateException("月度调价价格准备未完成：OA=" + task.getOaNo()
          + "，产品=" + task.getProductCode()
          + "，状态=" + prepareResult.getStatus()
          + "，缺口=" + prepareResult.getGapCount()
          + "，说明=" + prepareResult.getMessage());
    }
  }

  private void validateMonthlyResult(CostRunObjectResult result) {
    if (result == null || result.getResult() == null || result.getResult().getTotalCost() == null) {
      throw new IllegalStateException("月度调价成本核算未得到总成本，禁止写入成功结果");
    }
    List<String> missingParts = result.getPartItems() == null
        ? List.of()
        : result.getPartItems().stream()
            .filter(this::isMissingPrice)
            .map(this::partMissingMessage)
            .limit(10)
            .toList();
    if (!missingParts.isEmpty()) {
      throw new IllegalStateException("月度调价存在部品缺价，禁止写入成功结果："
          + String.join("；", missingParts));
    }
  }

  private boolean isMissingPrice(CostRunPartItemDto item) {
    if (item == null) {
      return false;
    }
    if (PRICE_SOURCE_ERROR.equalsIgnoreCase(item.getPriceSource())) {
      return true;
    }
    boolean missingResolvedPrice =
        StringUtils.hasText(item.getPartCode())
        && item.getPartQty() != null
        && item.getPartQty().signum() != 0
        && (item.getUnitPrice() == null || item.getAmount() == null);
    if (!missingResolvedPrice) {
      return false;
    }
    return isCriticalMonthlySource(item)
        || !PRICE_SOURCE_NO_ROUTE.equalsIgnoreCase(item.getPriceSource());
  }

  private boolean isCriticalMonthlySource(CostRunPartItemDto item) {
    String text = (firstText(item.getPriceType(), "") + " "
        + firstText(item.getPriceSource(), "") + " "
        + firstText(item.getRemark(), "")).toUpperCase();
    return text.contains("MAKE")
        || text.contains("LINKED")
        || text.contains("MONTHLY_ADJUST")
        || text.contains("PACKAGE")
        || text.contains("制造件")
        || text.contains("自制件")
        || text.contains("联动")
        || text.contains("包装");
  }

  private String partMissingMessage(CostRunPartItemDto item) {
    String code = StringUtils.hasText(item.getPartCode()) ? item.getPartCode().trim() : "-";
    String source = StringUtils.hasText(item.getPriceSource()) ? item.getPriceSource().trim() : "-";
    String remark = StringUtils.hasText(item.getRemark()) ? item.getRemark().trim() : "无价格";
    return code + "[" + source + "]" + remark;
  }
}
