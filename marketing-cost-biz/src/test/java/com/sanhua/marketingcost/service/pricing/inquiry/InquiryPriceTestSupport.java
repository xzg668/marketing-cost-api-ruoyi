package com.sanhua.marketingcost.service.pricing.inquiry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Production inquiry refresh for the existing independent MySQL fixtures. */
public final class InquiryPriceTestSupport {
  private InquiryPriceTestSupport() {}

  public static InquiryPriceRefreshService refresh(
      SqlSessionTemplate sql, PlatformTransactionManager manager, ObjectMapper json) {
    var jdbc = new JdbcTemplate(sql.getConfiguration().getEnvironment().getDataSource());
    var finance =
        new FinanceFactorPriceReader(
            sql.getMapper(FactorUploadBatchMapper.class),
            sql.getMapper(FactorMonthlyPriceMapper.class),
            sql.getMapper(FactorIdentityMapper.class),
            sql.getMapper(FactorRowRefMapper.class));
    return new InquiryPriceRefreshService(new InquiryPriceRepository(jdbc, json), finance, manager);
  }
}
