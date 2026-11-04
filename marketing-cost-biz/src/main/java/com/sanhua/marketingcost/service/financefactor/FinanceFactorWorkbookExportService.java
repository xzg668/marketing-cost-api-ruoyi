package com.sanhua.marketingcost.service.financefactor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 导出所选月份最新完整发布的所有方案/条件；不采用月价、不复制或更新数据库记录。 */
@Service
public class FinanceFactorWorkbookExportService {
  private final FactorUploadBatchMapper batches;
  private final FactorMonthlyPriceMapper prices;
  private final FactorIdentityMapper identities;
  private final FactorRowRefMapper sources;

  public FinanceFactorWorkbookExportService(FactorUploadBatchMapper batches, FactorMonthlyPriceMapper prices,
      FactorIdentityMapper identities, FactorRowRefMapper sources) {
    this.batches = batches; this.prices = prices; this.identities = identities; this.sources = sources;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ExportFile selectedMonth(FinanceFactorImportContext scope) {
    YearMonth month = YearMonth.parse(scope.priceMonth());
    if (month.getYear() < 1) throw new IllegalArgumentException("请选择有效月份");
    String selectedMonth = month.toString();
    var batch = batches.selectOne(Wrappers.lambdaQuery(FactorUploadBatch.class)
        .eq(FactorUploadBatch::getBusinessUnitType, scope.businessUnitType())
        .eq(FactorUploadBatch::getPriceOrgCode, scope.priceOrgCode())
        .eq(FactorUploadBatch::getPriceMonth, selectedMonth)
        .eq(FactorUploadBatch::getImportType, "FINANCE_BASE")
        .eq(FactorUploadBatch::getStatus, "PUBLISHED")
        .isNotNull(FactorUploadBatch::getPublicationRevision).isNotNull(FactorUploadBatch::getPublishedAt)
        .orderByDesc(FactorUploadBatch::getPublicationRevision).last("LIMIT 1"));
    String organization = "210".equals(scope.priceOrgCode()) ? "商用" : "板换";
    if (batch == null) throw new IllegalArgumentException(selectedMonth + "没有已发布的" + organization + "财务基价，无法导出所选月份数据");
    var published = prices.selectList(Wrappers.lambdaQuery(FactorMonthlyPrice.class)
        .eq(FactorMonthlyPrice::getSourceUploadBatchId, batch.getId())
        .eq(FactorMonthlyPrice::getStatus, "PUBLISHED").orderByAsc(FactorMonthlyPrice::getId));
    if (published.isEmpty()) throw new IllegalStateException("所选月份财务发布缺少有效基价");
    var identityById = identities.selectBatchIds(published.stream().map(FactorMonthlyPrice::getFactorIdentityId).distinct().toList())
        .stream().collect(Collectors.toMap(FactorIdentity::getId, i -> i));
    var sourceByPrice = sources.selectList(Wrappers.lambdaQuery(FactorRowRef.class)
        .eq(FactorRowRef::getFactorUploadBatchId, batch.getId()).orderByAsc(FactorRowRef::getId))
        .stream().collect(Collectors.groupingBy(FactorRowRef::getFactorMonthlyPriceId));
    var values = new ArrayList<Value>();
    for (var price : published) {
      var identity = identityById.get(price.getFactorIdentityId());
      var references = sourceByPrice.getOrDefault(price.getId(), List.of());
      if (identity == null || !scope.businessUnitType().equals(identity.getBusinessUnitType())
          || identity.getCanonicalFactorKey() == null || references.isEmpty() || price.getPrice() == null
          || price.getUnit() == null || price.getTaxIncluded() == null
          || !scope.priceOrgCode().equals(price.getPriceOrgCode()) || !selectedMonth.equals(price.getPriceMonth())
          || !batch.getPublicationRevision().equals(price.getRevisionNo()))
        throw new IllegalStateException("所选月份财务发布的身份、价格或来源不完整，不能导出");
      var source = references.getFirst();
      values.add(new Value(identity.getCanonicalFactorKey(), source.getFactorName(), source.getShortName(),
          source.getPriceSource(), price.getSchemeCode(), Condition.valueOf(price.getConditionCode()),
          price.getPrice(), price.getUnit(), price.getTaxIncluded() == 1,
          source.getSourceRowNumber(), source.getSourceColumnNumber(), source.getSourceCellRef(),
          source.getSourceValueText(), source.getOriginalPrice(), source.getSourceFormulaExpr(),
          source.getSourceNumberFormat(), source.getSourceUnit(), source.getConversionBasis()));
    }
    byte[] bytes = FinanceFactorEditWorkbook.write(scope.businessUnitType(), scope.priceOrgCode(), selectedMonth,
        batch.getPublicationRevision(), batch.getDefaultSchemeCode(), values);
    return new ExportFile(organization + "财务基价_" + selectedMonth + ".xlsx",
        selectedMonth, batch.getPublicationRevision(), bytes);
  }

  public record ExportFile(String fileName, String sourceMonth, int revision, byte[] bytes) {}
}
