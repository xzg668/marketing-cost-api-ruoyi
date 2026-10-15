package com.sanhua.marketingcost.service.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.*;
import org.mybatis.spring.SqlSessionTemplate;

/** 原稿/财务集成测试共享真实有效价生成依赖，不用空钩子替代同步发布。 */
public final class FinanceEffectivePriceTestSupport {
  private FinanceEffectivePriceTestSupport() {}

  public static FinanceEffectivePriceService service(SqlSessionTemplate sql, ObjectMapper json) {
    var identities = sql.getMapper(FactorIdentityMapper.class);
    var batches = sql.getMapper(FactorUploadBatchMapper.class);
    var finance =
        new FinanceFactorPriceReader(
            batches,
            sql.getMapper(FactorMonthlyPriceMapper.class),
            identities,
            sql.getMapper(FactorRowRefMapper.class));
    var snapshots = new OriginalLinkedRuleSnapshot(json);
    var parser =
        new OriginalLinkedWorkbookParser(
            new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    return new FinanceEffectivePriceService(
        sql.getMapper(FinanceEffectivePriceMapper.class),
        sql.getMapper(PriceLinkedItemMapper.class),
        sql.getMapper(PriceRangeFactorRuleMapper.class),
        batches,
        finance,
        new OriginalRangeRuleSnapshot(json),
        new OriginalLinkedRuleCalculator(),
        new OriginalRangeRuleCalculator(),
        json,
        new OriginalLinkedPublicationSource(parser, snapshots, batches, identities));
  }
}
