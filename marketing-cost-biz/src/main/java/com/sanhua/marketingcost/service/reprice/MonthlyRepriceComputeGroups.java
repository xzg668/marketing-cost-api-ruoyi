package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.mapper.MonthlyRepriceReuseMapper;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Registers complete input groups in a short transaction; no calculation runs under its lock. */
@Service
public class MonthlyRepriceComputeGroups {
  private final MonthlyRepriceReuseMapper mapper;
  private final TransactionTemplate transaction;

  public MonthlyRepriceComputeGroups(MonthlyRepriceReuseMapper mapper, PlatformTransactionManager transactions) {
    this.mapper = mapper;
    transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(15);
  }

  public MonthlyRepriceReuseMapper.Group register(String repriceNo, MonthlyRepriceInputDocument input) {
    return transaction.execute(tx -> {
      // Serialize registration separately from customer completion. READ_COMMITTED lets
      // candidates see the previous registration without locking immutable group inputs.
      var batch = mapper.lockBatch(repriceNo);
      if (batch == null || !batch.businessUnitType().equals(input.scope().businessUnitType())
          || !batch.pricingMonth().equals(input.scope().month().toString()))
        throw new IllegalArgumentException("计算组范围与调价批次不一致");
      if (!Set.of("QUEUED", "RUNNING", "FAILED").contains(batch.status()))
        throw new IllegalStateException("当前批次不能登记计算组");
      for (var candidate : mapper.candidates(batch.id(), input.digest())) {
        if (input.matchesStored(candidate.inputDigest(), candidate.inputDocument())) {
          if (!candidate.productCode().equals(input.scope().productCode())
              || candidate.inputSchemaVersion() != MonthlyRepriceInputDocument.SCHEMA_VERSION
              || !candidate.algorithmVersion().equals(input.scope().algorithmVersion())
              || !candidate.feePolicyVersion().equals(input.scope().feePolicyVersion()))
            throw new IllegalStateException("已保存计算组范围或算法与输入文档不一致");
          return candidate;
        }
      }
      var row = new MonthlyRepriceReuseMapper.NewGroup(batch.id(), input.scope().productCode(),
          MonthlyRepriceInputDocument.SCHEMA_VERSION, input.digest(), input.canonical(),
          input.scope().algorithmVersion(), input.scope().feePolicyVersion());
      if (mapper.insertGroup(row) != 1 || row.id == null)
        throw new IllegalStateException("计算组登记失败");
      return mapper.readGroup(row.id);
    });
  }
}
