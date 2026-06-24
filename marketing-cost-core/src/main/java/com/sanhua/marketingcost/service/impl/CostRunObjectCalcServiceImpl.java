package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunObjectCalcService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunPreparedPartItemProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunObjectCalcServiceImpl implements CostRunObjectCalcService {

  private static final String COST_CODE_TOTAL = "TOTAL";

  private final CostRunResultMapper costRunResultMapper;
  private final CostRunPartItemService costRunPartItemService;
  private final CostRunCostItemService costRunCostItemService;
  private final List<CostRunPreparedPartItemProvider> preparedPartItemProviders;

  public CostRunObjectCalcServiceImpl(
      CostRunResultMapper costRunResultMapper,
      CostRunPartItemService costRunPartItemService,
      CostRunCostItemService costRunCostItemService,
      List<CostRunPreparedPartItemProvider> preparedPartItemProviders) {
    this.costRunResultMapper = costRunResultMapper;
    this.costRunPartItemService = costRunPartItemService;
    this.costRunCostItemService = costRunCostItemService;
    this.preparedPartItemProviders =
        preparedPartItemProviders == null ? List.of() : List.copyOf(preparedPartItemProviders);
  }

  @Override
  public CostRunObjectResult calculate(CostRunContext context) {
    String oaNo = context.getOaNo().trim();
    String productCode = context.getProductCode().trim();
    java.util.function.IntConsumer progress =
        context.getProgress() == null ? p -> {} : context.getProgress();
    boolean monthlyReprice = CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene());
    List<CostRunPartItemDto> rawPartItems = loadPartItems(context, oaNo, progress);
    List<CostRunPartItemDto> partItems = filterByProduct(rawPartItems, productCode);
    progress.accept(60);
    List<CostRunCostItemDto> costItems =
        costRunCostItemService.listByMaterialCodes(
            oaNo,
            productCode,
            java.util.Set.of(productCode),
            context,
            partItems,
            false,
            p -> progress.accept(60 + p * 40 / 100));
    progress.accept(100);
    // 月度调价不能把历史 lp_cost_run_result 当计算输入；普通报价才读取它补充结果展示元数据。
    CostRunResult sourceResult = monthlyReprice ? null : findSourceResult(oaNo, productCode);
    CostRunResultDto resultDto = buildResultFromContext(context, costItems, sourceResult);
    return CostRunObjectResult.of(
        context,
        sourceResult == null ? null : sourceResult.getId(),
        resultDto,
        partItems,
        costItems);
  }

  private List<CostRunPartItemDto> loadPartItems(
      CostRunContext context, String oaNo, java.util.function.IntConsumer progress) {
    if (requiresPreparedPartItems(context)) {
      for (CostRunPreparedPartItemProvider provider : preparedPartItemProviders) {
        if (provider != null && provider.supports(context)) {
          List<CostRunPartItemDto> preparedItems = provider.listPreparedPartItems(context);
          if (preparedItems == null || preparedItems.isEmpty()) {
            throw new IllegalStateException(
                "价格源快照 " + context.getPricePrepareNo() + " 缺少部品明细，不能发起报价成本核算");
          }
          progress.accept(60);
          return preparedItems;
        }
      }
      throw new IllegalStateException(
          "报价成本核算缺少价格源快照读取器，prepareNo=" + context.getPricePrepareNo());
    }
    // 统一引擎必须重新拿 BOM/价格/费率等原料计算，不能用历史结果表复制出“新结果”。
    // 报价工作台已确认价格源时会走上面的快照读取；无快照的旧调用和月度调价继续走实时计算。
    return costRunPartItemService.listByOaNo(
        oaNo,
        resolveQuoteDate(context),
        context,
        false,
        p -> progress.accept(p * 60 / 100));
  }

  private boolean requiresPreparedPartItems(CostRunContext context) {
    return context != null
        && CostRunContext.SCENE_QUOTE.equals(context.getScene())
        && StringUtils.hasText(context.getPricePrepareNo());
  }

  private CostRunResult findSourceResult(String oaNo, String productCode) {
    return costRunResultMapper.selectOne(
        Wrappers.lambdaQuery(CostRunResult.class)
            .eq(CostRunResult::getOaNo, oaNo)
            .eq(CostRunResult::getProductCode, productCode)
            .last("LIMIT 1"));
  }

  private List<CostRunPartItemDto> filterByProduct(
      List<CostRunPartItemDto> partItems, String productCode) {
    if (partItems == null || partItems.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunPartItemDto> filtered = new ArrayList<>();
    for (CostRunPartItemDto item : partItems) {
      if (item != null && productCode.equals(trim(item.getProductCode()))) {
        filtered.add(item);
      }
    }
    return filtered;
  }

  private CostRunResultDto buildResultFromContext(
      CostRunContext context, List<CostRunCostItemDto> costItems, CostRunResult sourceResult) {
    CostRunResultDto dto = new CostRunResultDto();
    dto.setOaNo(context.getOaNo());
    dto.setProductCode(context.getProductCode());
    dto.setCustomerName(context.getCustomerName());
    dto.setPeriod(context.getPricingMonth());
    dto.setTotalCost(amountOf(costItems, COST_CODE_TOTAL));
    if (sourceResult != null) {
      dto.setProductName(sourceResult.getProductName());
      dto.setProductModel(sourceResult.getProductModel());
      dto.setBusinessUnit(sourceResult.getBusinessUnit());
      dto.setDepartment(sourceResult.getDepartment());
      dto.setCurrency(sourceResult.getCurrency());
      dto.setUnit(sourceResult.getUnit());
      dto.setProductAttr(sourceResult.getProductAttr());
    }
    dto.setCalcStatus(CostRunContext.SCENE_QUOTE.equals(context.getScene()) ? "已核算" : "SUCCESS");
    return dto;
  }

  private BigDecimal amountOf(List<CostRunCostItemDto> costItems, String costCode) {
    for (CostRunCostItemDto item : costItems) {
      if (item != null && costCode.equals(trim(item.getCostCode()))) {
        return item.getAmount();
      }
    }
    return null;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private LocalDate resolveQuoteDate(CostRunContext context) {
    if (context.getPriceAsOfTime() != null) {
      return context.getPriceAsOfTime().toLocalDate();
    }
    if (CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      if (StringUtils.hasText(context.getPricingMonth())) {
        return LocalDate.parse(context.getPricingMonth().trim() + "-01");
      }
    }
    return LocalDate.now();
  }
}
