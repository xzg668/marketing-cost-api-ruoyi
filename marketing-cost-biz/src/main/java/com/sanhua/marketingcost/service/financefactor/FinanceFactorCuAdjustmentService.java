package com.sanhua.marketingcost.service.financefactor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.FinanceFactorImportPreview;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Scheme;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 财务Cu维护仍走完整发布事务，只修改所选方案/条件的Cu，不改历史行或Pcu。 */
@Service
public class FinanceFactorCuAdjustmentService {
  private final FactorMonthlyPriceMapper prices;
  private final FactorUploadBatchMapper batches;
  private final FactorIdentityMapper identities;
  private final FactorRowRefMapper sources;
  private final FinanceFactorPublicationService publications;

  public FinanceFactorCuAdjustmentService(
      FactorMonthlyPriceMapper prices,
      FactorUploadBatchMapper batches,
      FactorIdentityMapper identities,
      FactorRowRefMapper sources,
      FinanceFactorPublicationService publications) {
    this.prices = prices;
    this.batches = batches;
    this.identities = identities;
    this.sources = sources;
    this.publications = publications;
  }

  public FinanceFactorImportPreview preview(
      Long monthlyPriceId, BigDecimal perTon, String reason, String bu) {
    Draft draft = draft(monthlyPriceId, perTon, reason, bu);
    var current = publications.latest(draft.context());
    if (current == null || !current.getId().equals(draft.sourceBatchId())) {
      throw new IllegalArgumentException("所选Cu价格已被新财务修订替代，请刷新后调整");
    }
    return publications.previewPrepared(
        draft.workbook(), draft.context(), reason.trim(), "FINANCE_CU_ADJUSTMENT");
  }

  public FinanceFactorPublicationService.Publication publish(
      Long monthlyPriceId,
      BigDecimal perTon,
      String reason,
      String bu,
      String fingerprint,
      String operator) {
    Draft draft = draft(monthlyPriceId, perTon, reason, bu);
    return publications.publishPrepared(
        draft.workbook(),
        draft.context(),
        fingerprint,
        operator,
        reason.trim(),
        "FINANCE_CU_ADJUSTMENT");
  }

  private Draft draft(Long monthlyPriceId, BigDecimal perTon, String reason, String bu) {
    if (monthlyPriceId == null || monthlyPriceId <= 0)
      throw new IllegalArgumentException("财务Cu价格ID必填");
    if (perTon == null
        || perTon.signum() <= 0
        || perTon.stripTrailingZeros().scale() > 13
        || perTon.precision() - perTon.scale() > 18)
      throw new IllegalArgumentException("Cu元/吨价格必须大于0且不能超出精确存储范围");
    if (reason == null || reason.isBlank() || reason.trim().length() > 512)
      throw new IllegalArgumentException("调整原因必填且不超过512字");
    FactorMonthlyPrice target = prices.selectById(monthlyPriceId);
    if (target == null || !"PUBLISHED".equals(target.getStatus()))
      throw new IllegalArgumentException("已发布财务Cu价格不存在");
    var batch = batches.selectById(target.getSourceUploadBatchId());
    var identity = identities.selectById(target.getFactorIdentityId());
    if (batch == null
        || !"FINANCE_BASE".equals(batch.getImportType())
        || !"PUBLISHED".equals(batch.getStatus())
        || bu == null
        || !bu.equals(batch.getBusinessUnitType())
        || identity == null
        || !bu.equals(identity.getBusinessUnitType())
        || !"AVG|1#CU".equals(identity.getCanonicalFactorKey())) {
      throw new IllegalArgumentException("价格不属于当前业务单元的财务Cu因素");
    }
    BigDecimal newPrice =
        switch (target.getUnit()) {
          case "CNY/KG" -> perTon.movePointLeft(3);
          case "CNY/TON" -> perTon;
          default -> throw new IllegalArgumentException("财务Cu单位无效：" + target.getUnit());
        };
    var context =
        new FinanceFactorImportContext(
            bu, batch.getPriceOrgCode(), batch.getPriceMonth(), null, batch.getDefaultSchemeCode());
    Map<Long, FactorMonthlyPrice> priceById =
        prices
            .selectList(
                Wrappers.lambdaQuery(FactorMonthlyPrice.class)
                    .eq(FactorMonthlyPrice::getSourceUploadBatchId, batch.getId())
                    .eq(FactorMonthlyPrice::getStatus, "PUBLISHED"))
            .stream()
            .collect(Collectors.toMap(FactorMonthlyPrice::getId, p -> p));
    var identityById =
        identities
            .selectBatchIds(
                priceById.values().stream()
                    .map(FactorMonthlyPrice::getFactorIdentityId)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(i -> i.getId(), i -> i));
    List<Value> values = new ArrayList<>();
    var sourceRows =
        sources.selectList(
            Wrappers.lambdaQuery(FactorRowRef.class)
                .eq(FactorRowRef::getFactorUploadBatchId, batch.getId())
                .orderByAsc(FactorRowRef::getId));
    for (FactorRowRef source : sourceRows) {
      FactorMonthlyPrice price = priceById.get(source.getFactorMonthlyPriceId());
      if (price == null || identityById.get(price.getFactorIdentityId()) == null) {
        throw new IllegalStateException("财务修订的来源与因素价格不一致：" + source.getId());
      }
      String key = identityById.get(price.getFactorIdentityId()).getCanonicalFactorKey();
      boolean changed = Objects.equals(price.getId(), target.getId());
      values.add(
          new Value(
              key,
              source.getFactorName(),
              source.getShortName(),
              source.getPriceSource(),
              price.getSchemeCode(),
              Condition.valueOf(price.getConditionCode()),
              changed ? newPrice : price.getPrice(),
              price.getUnit(),
              price.getTaxIncluded() == 1,
              source.getSourceRowNumber(),
              source.getSourceColumnNumber(),
              source.getSourceCellRef(),
              source.getSourceValueText(),
              source.getOriginalPrice(),
              source.getSourceFormulaExpr(),
              source.getSourceNumberFormat(),
              source.getSourceUnit(),
              changed ? "FINANCE_MANUAL_CU_ADJUSTMENT" : source.getConversionBasis()));
    }
    if (values.isEmpty()
        || values.stream()
            .noneMatch(
                v ->
                    v.canonicalKey().equals("AVG|1#CU")
                        && v.schemeCode().equals(target.getSchemeCode())
                        && v.condition().name().equals(target.getConditionCode()))) {
      throw new IllegalStateException("待调整的财务Cu缺少来源记录");
    }
    // 单次修订继承全部方案及来源，只改变被选中的有效Cu值；原稿原值仍保留。
    List<Scheme> schemes =
        values.stream()
            .map(v -> v.schemeCode())
            .distinct()
            .map(code -> new Scheme(code, code))
            .toList();
    String sheet = sourceRows.getFirst().getSourceSheetName();
    var workbook =
        new FinanceFactorWorkbook(
            batch.getFileName(),
            batch.getFileSha256(),
            sheet,
            batch.getParserVersion(),
            schemes,
            values,
            List.of());
    return new Draft(workbook, context, batch.getId());
  }

  private record Draft(
      FinanceFactorWorkbook workbook, FinanceFactorImportContext context, Long sourceBatchId) {}
}
