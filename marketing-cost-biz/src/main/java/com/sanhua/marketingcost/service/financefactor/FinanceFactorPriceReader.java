package com.sanhua.marketingcost.service.financefactor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.Source;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 从一个已发布财务修订读取完整方案，读快照期间不混入其他月份或新发布。 */
@Service
public class FinanceFactorPriceReader {
  /** 尚未导入是可等待状态；已发布但缺因素或方案仍属于数据错误。 */
  public static final class MissingPublicationException extends IllegalArgumentException {
    public MissingPublicationException(String message) {
      super(message);
    }
  }

  private final FactorUploadBatchMapper batches;
  private final FactorMonthlyPriceMapper prices;
  private final FactorIdentityMapper identities;
  private final FactorRowRefMapper sources;

  public FinanceFactorPriceReader(
      FactorUploadBatchMapper batches,
      FactorMonthlyPriceMapper prices,
      FactorIdentityMapper identities,
      FactorRowRefMapper sources) {
    this.batches = batches;
    this.prices = prices;
    this.identities = identities;
    this.sources = sources;
  }

  public FactorPriceSnapshot read(
      FinanceFactorImportContext context, Condition condition, Integer revision) {
    return readPublication(context, condition, revision, false);
  }

  /** 核算选取不晚于请求月的完整财务发布；方案缺因素时不拼接历史残缺值。 */
  // 尚无发布是导入允许保存为待基价的状态，不能污染调用方的共享事务。
  @Transactional(
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      noRollbackFor = MissingPublicationException.class)
  public com.sanhua.marketingcost.service.pricing.PriceFactorContext readEffective(
      FinanceFactorImportContext context, Condition condition, String materialCode) {
    var snapshot = effectiveSnapshot(context, condition);
    return new com.sanhua.marketingcost.service.pricing.PriceFactorContext(
        materialCode,
        new com.sanhua.marketingcost.service.pricing.PriceMonthSelection(
            context.priceMonth(), snapshot.scope().priceMonth()),
        snapshot,
        null);
  }

  /** 默认正常成本：商用通用方案，板换采用已确认的非招标条件。 */
  @Transactional(
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      noRollbackFor = MissingPublicationException.class)
  public com.sanhua.marketingcost.service.pricing.PriceFactorContext readDefault(
      FinanceFactorImportContext context, String materialCode) {
    Condition condition =
        "220".equals(context.priceOrgCode()) ? Condition.NON_TENDER : Condition.GENERAL;
    return readEffective(context, condition, materialCode);
  }

  /** 缓存仅属于本次数据库事务，P0/P1及所有料号共用；下次请求一定重新选发布。 */
  private FactorPriceSnapshot effectiveSnapshot(
      FinanceFactorImportContext context, Condition condition) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return readPublication(context, condition, null, true);
    }
    SnapshotSet snapshots = (SnapshotSet) TransactionSynchronizationManager.getResource(this);
    if (snapshots == null) {
      snapshots = new SnapshotSet();
      var transactionSnapshots = snapshots;
      TransactionSynchronizationManager.bindResource(this, transactionSnapshots);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void suspend() {
              TransactionSynchronizationManager.unbindResource(FinanceFactorPriceReader.this);
            }

            @Override
            public void resume() {
              TransactionSynchronizationManager.bindResource(
                  FinanceFactorPriceReader.this, transactionSnapshots);
            }

            @Override
            public void afterCompletion(int status) {
              TransactionSynchronizationManager.unbindResourceIfPossible(
                  FinanceFactorPriceReader.this);
            }
          });
    }
    var key =
        new SnapshotKey(
            context.businessUnitType(),
            context.priceOrgCode(),
            context.priceMonth(),
            context.defaultSchemeCode(),
            condition);
    return snapshots.values.computeIfAbsent(
        key, ignored -> readPublication(context, condition, null, true));
  }

  private record SnapshotKey(
      String businessUnit, String organization, String month, String scheme, Condition condition) {}

  private static final class SnapshotSet {
    private final Map<SnapshotKey, FactorPriceSnapshot> values = new LinkedHashMap<>();
  }

  public java.util.SortedSet<String> publishedMonths(String businessUnit, String organization) {
    return batches.selectList(Wrappers.lambdaQuery(FactorUploadBatch.class)
        .select(FactorUploadBatch::getPriceMonth)
        .eq(FactorUploadBatch::getBusinessUnitType, businessUnit)
        .eq(FactorUploadBatch::getPriceOrgCode, organization)
        .eq(FactorUploadBatch::getImportType, "FINANCE_BASE")
        .eq(FactorUploadBatch::getStatus, "PUBLISHED")
        .isNotNull(FactorUploadBatch::getPublicationRevision)
        .isNotNull(FactorUploadBatch::getPublishedAt)).stream().map(FactorUploadBatch::getPriceMonth)
        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
  }

  private FactorPriceSnapshot readPublication(
      FinanceFactorImportContext context,
      Condition condition,
      Integer revision,
      boolean allowHistory) {
    if (revision != null && revision <= 0) throw new IllegalArgumentException("财务修订号必须大于0");
    var query =
        Wrappers.lambdaQuery(FactorUploadBatch.class)
            .eq(FactorUploadBatch::getBusinessUnitType, context.businessUnitType())
            .eq(FactorUploadBatch::getPriceOrgCode, context.priceOrgCode())
            .eq(!allowHistory, FactorUploadBatch::getPriceMonth, context.priceMonth())
            .le(allowHistory, FactorUploadBatch::getPriceMonth, context.priceMonth())
            .eq(FactorUploadBatch::getImportType, "FINANCE_BASE")
            .eq(FactorUploadBatch::getStatus, "PUBLISHED")
            .isNotNull(FactorUploadBatch::getPublicationRevision)
            .isNotNull(FactorUploadBatch::getPublishedAt);
    if (revision != null) query.eq(FactorUploadBatch::getPublicationRevision, revision);
    FactorUploadBatch publication =
        batches.selectOne(
            query
                .orderByDesc(FactorUploadBatch::getPriceMonth)
                .orderByDesc(FactorUploadBatch::getPublicationRevision)
                .last("LIMIT 1"));
    if (publication == null) {
      throw new MissingPublicationException(
          "未发布该范围的财务基价：" + context.priceOrgCode() + "/" + context.priceMonth());
    }
    String scheme =
        context.defaultSchemeCode() == null
            ? publication.getDefaultSchemeCode()
            : context.defaultSchemeCode();
    FactorPriceScope scope =
        new FactorPriceScope(
            context.businessUnitType(),
            context.priceOrgCode(),
            publication.getPriceMonth(),
            scheme,
            condition);
    var selected = prices.findPublishedScheme(scope, publication.getPublicationRevision());
    if (selected.isEmpty())
      throw new IllegalArgumentException("该财务发布不含所选方案/条件：" + scheme + "/" + condition);
    var identityById =
        identities
            .selectBatchIds(selected.stream().map(p -> p.getFactorIdentityId()).distinct().toList())
            .stream()
            .collect(Collectors.toMap(i -> i.getId(), i -> i));
    var allSources =
        sources.selectList(
            Wrappers.lambdaQuery(FactorRowRef.class)
                .eq(FactorRowRef::getFactorUploadBatchId, publication.getId())
                .orderByAsc(FactorRowRef::getId));
    var schemeCodes = allSources.stream().map(FactorRowRef::getSchemeCode).distinct().toList();
    Map<Long, List<FactorRowRef>> sourceByPrice =
        allSources.stream()
            .filter(
                source ->
                    scheme.equals(source.getSchemeCode())
                        && condition.name().equals(source.getConditionCode()))
            .collect(Collectors.groupingBy(FactorRowRef::getFactorMonthlyPriceId));
    Map<String, FactorValue> values = new LinkedHashMap<>();
    for (var price : selected) {
      var identity = identityById.get(price.getFactorIdentityId());
      var sourceRows = sourceByPrice.getOrDefault(price.getId(), List.of());
      if (identity == null
          || identity.getCanonicalFactorKey() == null
          || price.getPrice() == null
          || price.getUnit() == null
          || price.getTaxIncluded() == null
          || sourceRows.isEmpty()
          || !publication.getPublicationRevision().equals(price.getRevisionNo())) {
        throw new IllegalStateException("已发布财务因素的身份、值、版本或来源不完整：" + price.getId());
      }
      var value =
          new FactorValue(
              identity.getId(),
              price.getId(),
              identity.getFactorName(),
              identity.getShortName(),
              price.getPrice(),
              price.getUnit(),
              price.getTaxIncluded() == 1,
              sourceRows.stream().map(FinanceFactorPriceReader::source).toList());
      if (values.putIfAbsent(identity.getCanonicalFactorKey(), value) != null) {
        throw new IllegalStateException("已发布财务方案存在重复因素：" + identity.getCanonicalFactorKey());
      }
    }
    return new FactorPriceSnapshot(
        scope,
        publication.getId(),
        publication.getPublicationRevision(),
        publication.getFileName(),
        publication.getFileSha256(),
        publication.getContentHash(),
        values,
        schemeCodes,
        publication.getUploadedBy(),
        publication.getPublishedAt(),
        publication.getPublicationNote());
  }

  private static Source source(FactorRowRef row) {
    return new Source(
        row.getId(),
        row.getSourceSheetName(),
        row.getSourceCellRef(),
        row.getSourceValueText(),
        row.getSourceUnit(),
        row.getSourceFormulaExpr(),
        row.getSourceNumberFormat(),
        row.getConversionBasis());
  }
}
