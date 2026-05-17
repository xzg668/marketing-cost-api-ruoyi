package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustmentResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceChangeLogDto;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorMonthlyPriceAdjustmentService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FactorMonthlyPriceAdjustmentServiceImpl
    implements FactorMonthlyPriceAdjustmentService {

  private static final String CHANGE_TYPE_MANUAL_ADJUST = "MANUAL_ADJUST";
  private static final String CHANGE_TYPE_NO_CHANGE = "NO_CHANGE";

  private final FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private final FactorMonthlyPriceChangeLogMapper changeLogMapper;

  public FactorMonthlyPriceAdjustmentServiceImpl(
      FactorMonthlyPriceMapper factorMonthlyPriceMapper,
      FactorMonthlyPriceChangeLogMapper changeLogMapper) {
    this.factorMonthlyPriceMapper = factorMonthlyPriceMapper;
    this.changeLogMapper = changeLogMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorMonthlyPriceAdjustmentResponse adjust(
      Long factorMonthlyPriceId, FactorMonthlyPriceAdjustRequest request, String operator) {
    if (factorMonthlyPriceId == null) {
      throw new IllegalArgumentException("factorMonthlyPriceId 必填");
    }
    if (request == null || request.getNewPrice() == null) {
      throw new IllegalArgumentException("newPrice 必填");
    }

    FactorMonthlyPrice monthlyPrice = factorMonthlyPriceMapper.selectById(factorMonthlyPriceId);
    if (monthlyPrice == null) {
      throw new IllegalArgumentException("影响因素月度价格不存在：" + factorMonthlyPriceId);
    }

    BigDecimal oldPrice = normalizePrice(monthlyPrice.getPrice());
    BigDecimal newPrice = normalizePrice(request.getNewPrice());
    String normalizedOperator = normalizeOperator(operator);
    LocalDateTime now = LocalDateTime.now();
    String changeType = samePrice(oldPrice, newPrice)
        ? CHANGE_TYPE_NO_CHANGE
        : CHANGE_TYPE_MANUAL_ADJUST;

    if (!samePrice(oldPrice, newPrice)) {
      monthlyPrice.setPrice(newPrice);
      monthlyPrice.setUpdatedBy(normalizedOperator);
      monthlyPrice.setUpdatedAt(now);
      factorMonthlyPriceMapper.updateById(monthlyPrice);
    }

    // 月度调价只记录价格变更，不改 lp_price_variable_binding，绑定关系仍指向同一个影响因素身份。
    FactorMonthlyPriceChangeLog log = new FactorMonthlyPriceChangeLog();
    log.setFactorMonthlyPriceId(monthlyPrice.getId());
    log.setFactorIdentityId(monthlyPrice.getFactorIdentityId());
    log.setPriceMonth(monthlyPrice.getPriceMonth());
    log.setOldPrice(oldPrice);
    log.setNewPrice(newPrice);
    log.setChangeType(changeType);
    log.setSourceUploadBatchId(null);
    log.setChangedBy(normalizedOperator);
    log.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark().trim() : null);
    log.setCreatedAt(now);
    changeLogMapper.insert(log);

    FactorMonthlyPriceAdjustmentResponse response = new FactorMonthlyPriceAdjustmentResponse();
    response.setFactorMonthlyPriceId(monthlyPrice.getId());
    response.setFactorIdentityId(monthlyPrice.getFactorIdentityId());
    response.setPriceMonth(monthlyPrice.getPriceMonth());
    response.setOldPrice(oldPrice);
    response.setNewPrice(newPrice);
    response.setChangeType(changeType);
    response.setChangedBy(normalizedOperator);
    response.setRemark(log.getRemark());
    response.setChangedAt(now);
    return response;
  }

  @Override
  public List<FactorMonthlyPriceChangeLogDto> listChangeLogs(Long factorMonthlyPriceId) {
    if (factorMonthlyPriceId == null) {
      return List.of();
    }
    return changeLogMapper.selectList(
            Wrappers.lambdaQuery(FactorMonthlyPriceChangeLog.class)
                .eq(FactorMonthlyPriceChangeLog::getFactorMonthlyPriceId, factorMonthlyPriceId)
                .orderByDesc(FactorMonthlyPriceChangeLog::getCreatedAt)
                .orderByDesc(FactorMonthlyPriceChangeLog::getId))
        .stream()
        .map(FactorMonthlyPriceChangeLogDto::fromEntity)
        .toList();
  }

  private BigDecimal normalizePrice(BigDecimal price) {
    return price == null ? null : price.stripTrailingZeros();
  }

  private boolean samePrice(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) {
      return left == right;
    }
    return left.compareTo(right) == 0;
  }

  private String normalizeOperator(String operator) {
    return StringUtils.hasText(operator) ? operator.trim() : "system";
  }
}
