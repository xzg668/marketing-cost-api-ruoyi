package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** BOM 分支改变后的报价产品下游状态失效编排。 */
@Service
public class QuoteBomAlternativeWorkflowInvalidationServiceImpl
    implements QuoteBomAlternativeWorkflowInvalidationService {

  static final String STATUS_STALE = "STALE";
  static final String MESSAGE = "BOM标准/替代分支已变化，请重新确认价格并核算成本";

  private final QuotePriceTypeConfirmationInvalidationService
      priceTypeInvalidationService;
  private final PricePrepareBatchMapper pricePrepareBatchMapper;
  private final QuoteCostRunVersionInvalidationService
      costRunInvalidationService;

  public QuoteBomAlternativeWorkflowInvalidationServiceImpl(
      QuotePriceTypeConfirmationInvalidationService
          priceTypeInvalidationService,
      PricePrepareBatchMapper pricePrepareBatchMapper,
      QuoteCostRunVersionInvalidationService
          costRunInvalidationService) {
    this.priceTypeInvalidationService = priceTypeInvalidationService;
    this.pricePrepareBatchMapper = pricePrepareBatchMapper;
    this.costRunInvalidationService = costRunInvalidationService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeWorkflowInvalidationResult invalidate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth) {
    String normalizedOaNo = required("oaNo", oaNo);
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("oaFormItemId不能为空");
    }
    String normalizedProduct = required("productCode", productCode);
    String normalizedMonth = required("periodMonth", periodMonth);
    int priceTypeCount =
        priceTypeInvalidationService.invalidateScopeAfterBomChange(
            normalizedOaNo,
            oaFormItemId,
            normalizedProduct,
            normalizedMonth);
    int pricePrepareCount =
        pricePrepareBatchMapper.update(
            null,
            Wrappers.<PricePrepareBatch>lambdaUpdate()
                .set(PricePrepareBatch::getStatus, STATUS_STALE)
                .set(PricePrepareBatch::getMessage, MESSAGE)
                .set(PricePrepareBatch::getFinishedAt, LocalDateTime.now())
                .eq(PricePrepareBatch::getOaNo, normalizedOaNo)
                .eq(PricePrepareBatch::getOaFormItemId, oaFormItemId)
                .eq(PricePrepareBatch::getTopProductCode, normalizedProduct)
                .eq(PricePrepareBatch::getPeriodMonth, normalizedMonth)
                .ne(PricePrepareBatch::getStatus, STATUS_STALE));
    int costRunCount =
        costRunInvalidationService.invalidateProductAfterBomChange(
            normalizedOaNo,
            oaFormItemId,
            normalizedProduct,
            normalizedMonth);
    return new QuoteBomAlternativeWorkflowInvalidationResult(
        priceTypeCount, pricePrepareCount, costRunCount);
  }

  private static String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }
}
