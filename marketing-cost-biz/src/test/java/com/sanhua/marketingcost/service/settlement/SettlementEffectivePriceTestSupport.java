package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独立 MySQL 发布回归使用真实结算生成器；表由各测试的迁移清单创建。 */
public final class SettlementEffectivePriceTestSupport {
  private SettlementEffectivePriceTestSupport() {}
  public static SettlementEffectivePriceService service(SqlSessionTemplate sql, ObjectMapper json) {
    var jdbc = new JdbcTemplate(sql.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
    var finance = new FinanceFactorPriceReader(sql.getMapper(FactorUploadBatchMapper.class),
        sql.getMapper(FactorMonthlyPriceMapper.class), sql.getMapper(FactorIdentityMapper.class), sql.getMapper(FactorRowRefMapper.class));
    return new SettlementEffectivePriceService(new SettlementPriceRepository(jdbc), new SettlementFinanceBaseReader(finance),
        new SettlementEffectivePriceRepository(jdbc), new SettlementPriceCalculator(), new SettlementPriceEvidence(json),
        new SettlementRuleRevisionFactory(json));
  }
}
