package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostRunVersionServiceImpl implements QuoteCostRunVersionService {

  private static final int MAX_RETRY = 5;

  private final QuoteCostRunVersionMapper versionMapper;
  private final QuoteCostRunVersionNoGenerator noGenerator;

  public QuoteCostRunVersionServiceImpl(
      QuoteCostRunVersionMapper versionMapper, QuoteCostRunVersionNoGenerator noGenerator) {
    this.versionMapper = versionMapper;
    this.noGenerator = noGenerator;
  }

  @Override
  public QuoteCostRunVersion createTrial(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String pricingMonth,
      String resultPeriod,
      String pricePrepareNo,
      String priceTypeConfirmNo,
      String bomConfirmNo,
      String businessUnitType) {
    String oaNoValue = required("oaNo", oaNo);
    if (oaFormItemId == null) {
      throw new IllegalArgumentException("oaFormItemId 不能为空");
    }
    String productCodeValue = required("productCode", productCode);
    String pricingMonthValue = required("pricingMonth", pricingMonth);
    String resultPeriodValue = required("resultPeriod", resultPeriod);
    for (int i = 0; i < MAX_RETRY; i++) {
      QuoteCostRunVersion version = new QuoteCostRunVersion();
      version.setCostRunNo(noGenerator.nextCostRunNo());
      version.setOaNo(oaNoValue);
      version.setOaFormItemId(oaFormItemId);
      version.setProductCode(productCodeValue);
      version.setPricingMonth(pricingMonthValue);
      version.setResultPeriod(resultPeriodValue);
      version.setPricePrepareNo(trimToNull(pricePrepareNo));
      version.setPriceTypeConfirmNo(trimToNull(priceTypeConfirmNo));
      version.setBomConfirmNo(trimToNull(bomConfirmNo));
      version.setStatus("TRIAL");
      version.setPartItemCount(0);
      version.setCostItemCount(0);
      version.setTrialStartedAt(LocalDateTime.now());
      version.setBusinessUnitType(trimToNull(businessUnitType));
      try {
        versionMapper.insert(version);
        return version;
      } catch (DuplicateKeyException ex) {
        if (i == MAX_RETRY - 1) {
          throw ex;
        }
      }
    }
    throw new IllegalStateException("成本试算版本号生成失败");
  }

  @Override
  public void finishTrial(
      Long versionId,
      BigDecimal totalCost,
      int partItemCount,
      int costItemCount) {
    if (versionId == null) {
      return;
    }
    QuoteCostRunVersion update = new QuoteCostRunVersion();
    update.setId(versionId);
    update.setTotalCost(totalCost);
    update.setPartItemCount(partItemCount);
    update.setCostItemCount(costItemCount);
    update.setTrialFinishedAt(LocalDateTime.now());
    versionMapper.updateById(update);
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
