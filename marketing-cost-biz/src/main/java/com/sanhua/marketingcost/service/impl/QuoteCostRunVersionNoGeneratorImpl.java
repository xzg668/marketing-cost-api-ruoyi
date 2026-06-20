package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class QuoteCostRunVersionNoGeneratorImpl implements QuoteCostRunVersionNoGenerator {

  private static final DateTimeFormatter RUN_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final DateTimeFormatter VERSION_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd");

  private final QuoteCostRunVersionMapper versionMapper;

  public QuoteCostRunVersionNoGeneratorImpl(QuoteCostRunVersionMapper versionMapper) {
    this.versionMapper = versionMapper;
  }

  @Override
  public String nextCostRunNo() {
    String prefix = "TRIAL-" + LocalDateTime.now().format(RUN_TIME_FORMAT) + "-";
    Long count =
        versionMapper.selectCount(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .likeRight(QuoteCostRunVersion::getCostRunNo, prefix));
    long next = count == null ? 1L : count + 1L;
    return prefix + String.format("%04d", next);
  }

  @Override
  public String nextVersionNo(Long oaFormItemId, String productCode) {
    String prefix = "COST-" + LocalDate.now().format(VERSION_DATE_FORMAT) + "-";
    Long dailyCount =
        versionMapper.selectCount(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .likeRight(QuoteCostRunVersion::getVersionNo, prefix));
    long dailySeq = dailyCount == null ? 1L : dailyCount + 1L;

    var wrapper = Wrappers.lambdaQuery(QuoteCostRunVersion.class)
        .eq(oaFormItemId != null, QuoteCostRunVersion::getOaFormItemId, oaFormItemId)
        .eq(StringUtils.hasText(productCode), QuoteCostRunVersion::getProductCode, trim(productCode))
        .isNotNull(QuoteCostRunVersion::getVersionNo);
    Long itemVersionCount = versionMapper.selectCount(wrapper);
    long itemVersion = itemVersionCount == null ? 1L : itemVersionCount + 1L;
    return prefix + String.format("%04d", dailySeq) + "-V" + itemVersion;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
