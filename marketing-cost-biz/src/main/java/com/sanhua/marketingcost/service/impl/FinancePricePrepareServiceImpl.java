package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.scrap.ScrapPriceCalculationEvidence;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FinancePricePrepareServiceImpl implements FinancePricePrepareService {
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String ITEM_STATUS_READY = "READY";
  private static final String REF_MAKE_PART = "MAKE_PART_PRICE";

  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final MakePartPriceCalcRowMapper makePartRowMapper;
  private final QuoteCostPriceScenarioMapper scenarioMapper;
  private final ObjectMapper objectMapper;
  private final MonthlyPriceDocumentCodec monthlyCodec;
  private final MonthlyPreparationEvidence monthlyEvidence;

  public FinancePricePrepareServiceImpl(
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      MakePartPriceCalcRowMapper makePartRowMapper,
      QuoteCostPriceScenarioMapper scenarioMapper,
      ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.makePartRowMapper = makePartRowMapper;
    this.scenarioMapper = scenarioMapper;
    this.objectMapper = objectMapper;
    this.monthlyCodec = new MonthlyPriceDocumentCodec(objectMapper);
    this.monthlyEvidence = new MonthlyPreparationEvidence(monthlyCodec);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FinancePricePrepareGenerateResult generateFromOa(String sourcePrepareNo) {
    String normalizedSourceNo = requireText(sourcePrepareNo, "sourcePrepareNo");
    PricePrepareBatch source = loadSourceBatch(normalizedSourceNo);
    validateSourceBatch(source);
    List<PricePrepareItem> sourceItems = loadItems(normalizedSourceNo);
    validateSourceItems(sourceItems);

    requireMonthlyInputs(sourceItems);
    String scenarioGroupNo = ensureScenarioGroup(source);
    // OA and finance are one workbench transaction. Never adopt again after OA preparation
    // inserts have acquired the monthly-reference trigger locks on this same connection.
    // Each P0 comes from the exact immutable evidence already saved with the OA calculation.
    var projection = new MonthlyPreparationFinanceProjection(objectMapper, monthlyCodec);
    List<PricePrepareItem> financeItems = sourceItems.stream().map(projection::project).toList();
    validateSameInputs(sourceItems, financeItems);
    validateMakePartInputs(sourceItems, financeItems, new LinkedHashMap<>());

    var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    var finance = new PricePrepareBatch();
    org.springframework.beans.BeanUtils.copyProperties(source, finance);
    finance.setId(null); finance.setPrepareNo("PPR-" + UUID.randomUUID());
    finance.setScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    finance.setScenarioGroupNo(scenarioGroupNo); finance.setSourcePrepareNo(source.getPrepareNo());
    finance.setStartedAt(now); finance.setFinishedAt(now);
    finance.setCreatedAt(null); finance.setUpdatedAt(null);
    finance.setMessage("财务价格已按本次 OA 保存的月价依据投影");
    if (batchMapper.insert(finance) != 1) throw new IllegalStateException("财务价格准备批次保存失败");
    for (var item : financeItems) {
      item.setId(null); item.setPrepareNo(finance.getPrepareNo()); item.setCurrentFlag(0);
      item.setCreatedAt(null); item.setUpdatedAt(null);
      if (itemMapper.insert(item) != 1) throw new IllegalStateException("财务价格准备明细保存失败");
    }
    return new FinancePricePrepareGenerateResult(normalizedSourceNo, finance.getPrepareNo(),
        scenarioGroupNo, null, null, toGenerateResult(finance), null);
  }

  @Override
  @Transactional(readOnly = true)
  public FinancePricePrepareGenerateResult loadPreparedFromOa(String sourcePrepareNo) {
    String normalizedSourceNo = requireText(sourcePrepareNo, "sourcePrepareNo");
    PricePrepareBatch source = loadSourceBatch(normalizedSourceNo);
    validateSourceBatch(source);
    List<PricePrepareItem> sourceItems = loadItems(normalizedSourceNo);
    validateSourceItems(sourceItems);

    String scenarioGroupNo = requireText(source.getScenarioGroupNo(), "OA价格准备场景组编号");
    PricePrepareBatch finance =
        batchMapper.selectOne(
            Wrappers.lambdaQuery(PricePrepareBatch.class)
                .eq(PricePrepareBatch::getSourcePrepareNo, normalizedSourceNo)
                .eq(
                    PricePrepareBatch::getScenarioType,
                    QuotePriceScenarioType.FINANCE_QUOTE_BASE.name())
                .eq(PricePrepareBatch::getScenarioGroupNo, scenarioGroupNo)
                .orderByDesc(PricePrepareBatch::getId)
                .last("LIMIT 1"));
    if (finance == null) {
      throw new IllegalStateException("最终价格缺少已生成的财务场景，请重新生成最终价格");
    }
    validatePreparedFinanceBatch(source, finance);

    Map<Long, List<MakePartPriceCalcRow>> makeRows = new LinkedHashMap<>();
    List<PricePrepareItem> financeItems = loadItems(finance.getPrepareNo());
    validateSameInputs(sourceItems, financeItems);
    validateMakePartInputs(sourceItems, financeItems, makeRows);

    if (hasMonthlyInputs(sourceItems)) {
      requireMonthlyInputs(financeItems);
      return new FinancePricePrepareGenerateResult(normalizedSourceNo, finance.getPrepareNo(),
          scenarioGroupNo, null, null, toGenerateResult(finance), null);
    }

    CopperBasis financeBase = savedCopperBasis(finance, financeItems, makeRows);
    return new FinancePricePrepareGenerateResult(
        normalizedSourceNo,
        finance.getPrepareNo(),
        scenarioGroupNo,
        financeBase.legacyPriceId(),
        financeBase.pricePerKg(),
        toGenerateResult(finance),
        financeBase.factorPriceId());
  }

  /** 只读取本批次真正采用的明细快照，不能按OA和料号混取其他产品的结果。 */
  private CopperBasis itemCopperBasis(
      PricePrepareBatch batch,
      List<PricePrepareItem> items,
      Map<Long, List<MakePartPriceCalcRow>> makeRows) {
    CopperBasis selected = null;
    for (var item : items) {
      selected =
          mergeBasis(
              selected,
              evidenceBasis(
                  batch,
                  item.getMaterialCode(),
                  item.getResultRefType(),
                  item.getInputSnapshotJson()));
      if (item.getResultRefType() != null && item.getResultRefType().startsWith("PACKAGE")) {
        for (var component :
            new PackageCalculationEvidence(objectMapper).inputs(item.getInputSnapshotJson()))
          selected =
              mergeBasis(
                  selected,
                  evidenceBasis(
                      batch,
                      component.path("materialCode").asText(),
                      component.path("priceType").asText(),
                      component.path("inputs").toString()));
      }
      if (REF_MAKE_PART.equals(item.getResultRefType())) {
        for (var component : loadMakeRows(item, makeRows)) {
          selected =
              mergeBasis(
                  selected,
                  evidenceBasis(
                      batch,
                      component.getChildMaterialNo(),
                      component.getRawPriceType(),
                      component.getRawInputSnapshotJson()));
          selected =
              mergeBasis(
                  selected,
                  evidenceBasis(
                      batch,
                      component.getScrapCode(),
                      component.getScrapPriceType(),
                      component.getScrapInputSnapshotJson()));
        }
      }
    }
    return selected;
  }

  private CopperBasis evidenceBasis(
      PricePrepareBatch batch, String material, String priceType, String serialized) {
    if (!StringUtils.hasText(serialized)) return null;
    if (ScrapPriceCalculationEvidence.isScrap(serialized)) {
      var scrap = new ScrapPriceCalculationEvidence(objectMapper).read(serialized);
      if (!java.util.Objects.equals(material, scrap.rule().materialCode()))
        throw new IllegalStateException("废料快照与价格准备料号不一致：" + material);
      if (scrap.context() == null) return null;
      var basis = contextBasis(batch, scrap.context());
      return new CopperBasis(
          basis.legacyPriceId(), basis.factorPriceId(), basis.pricePerKg(), basis.context(), true);
    }
    if (!linkedPriceType(priceType) && !rangePriceType(priceType)) return null;
    PriceFactorContext context;
    if (rangePriceType(priceType)) {
      context = new OriginalRangeCalculationEvidence(objectMapper).read(serialized).context();
    } else {
      try {
        var saved =
            objectMapper
                .reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(serialized);
        if (saved == null
            || !(OriginalLinkedRuleSnapshot.VERSION.equals(saved.path("calculatorVersion").asText())
                || TechnicalFormulaCalculationEvidence.VERSION.equals(
                    saved.path("calculatorVersion").asText()))
            || !saved.path("context").isObject()) {
          throw new IllegalStateException("原稿财务计算快照不完整：" + material);
        }
        context = objectMapper.treeToValue(saved.path("context"), PriceFactorContext.class);
      } catch (JsonProcessingException error) {
        throw new IllegalStateException("原稿财务计算快照损坏：" + material, error);
      }
    }
    if (!java.util.Objects.equals(material, context.materialCode())) {
      throw new IllegalStateException("原稿财务快照与价格准备料号不一致：" + material);
    }
    return contextBasis(batch, context);
  }

  private CopperBasis contextBasis(PricePrepareBatch batch, PriceFactorContext context) {
    if (context == null
        || !batch.getBusinessUnitType().equals(context.financial().scope().businessUnitType())
        || !batch.getPeriodMonth().equals(context.months().requestedMonth())) {
      throw new IllegalStateException("财务快照与价格准备范围不一致");
    }
    context =
        context.financialOnly().evidenceForFactors(java.util.Set.of(PriceFactorContext.COPPER));
    BigDecimal price =
        context.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true);
    Long id = context.financial().required(PriceFactorContext.COPPER).monthlyPriceId();
    if (id == null || price.signum() <= 0) throw new IllegalStateException("财务Cu来源不完整");
    return new CopperBasis(null, id, price, context, false);
  }

  private CopperBasis mergeBasis(CopperBasis selected, CopperBasis next) {
    // 废料共用210；板换普通材料仍保留220基准。只有废料时才采用其商用依据。
    if (selected != null && next != null && selected.sharedScrap() != next.sharedScrap())
      return selected.sharedScrap() ? next : selected;
    if (selected != null
        && next != null
        && selected.pricePerKg().compareTo(next.pricePerKg()) != 0) {
      throw new IllegalStateException("同一报价采用了不同财务Cu基准，请重新准备价格");
    }
    return selected == null ? next : selected;
  }

  private CopperBasis savedCopperBasis(
      PricePrepareBatch batch,
      List<PricePrepareItem> items,
      Map<Long, List<MakePartPriceCalcRow>> makeRows) {
    String serialized = batchMapper.selectFinanceFactorSnapshot(batch.getPrepareNo());
    if (StringUtils.hasText(serialized)) {
      try {
        var saved = objectMapper.readValue(serialized, SavedFinanceBasis.class);
        if (!java.util.Objects.equals(batch.getOaNo(), saved.oaNo())
            || !java.util.Objects.equals(batch.getOaFormItemId(), saved.oaFormItemId())) {
          throw new IllegalStateException("财务基价依据与报价产品不一致");
        }
        return contextBasis(batch, saved.context());
      } catch (JsonProcessingException error) {
        throw new IllegalStateException("财务基价依据损坏：" + batch.getPrepareNo(), error);
      }
    }
    var captured = itemCopperBasis(batch, items, makeRows);
    if (captured != null) return captured;
    // 旧报价只读已冻结场景里的价格和身份，绝不拿当前发布覆盖历史。
    var scenarios =
        scenarioMapper.selectList(
            Wrappers.lambdaQuery(QuoteCostPriceScenario.class)
                .eq(QuoteCostPriceScenario::getPricePrepareNo, batch.getPrepareNo())
                .eq(QuoteCostPriceScenario::getBusinessUnitType, batch.getBusinessUnitType())
                .eq(QuoteCostPriceScenario::getPricingMonth, batch.getPeriodMonth())
                .eq(
                    QuoteCostPriceScenario::getScenarioType,
                    QuotePriceScenarioType.FINANCE_QUOTE_BASE.name())
                .eq(QuoteCostPriceScenario::getStatus, STATUS_SUCCESS));
    CopperBasis historical = null;
    for (var scenario : scenarios) {
      boolean factor = "FINANCE_FACTOR".equals(scenario.getCuPriceSource());
      if ((!factor && !"FINANCE_QUOTE_BASE".equals(scenario.getCuPriceSource()))
          || scenario.getCuSourceRefId() == null
          || scenario.getCuPrice() == null
          || scenario.getCuPrice().signum() <= 0) {
        throw new IllegalStateException("历史财务场景的Cu依据不完整");
      }
      var value =
          new CopperBasis(
              factor ? null : scenario.getCuSourceRefId(),
              factor ? scenario.getCuSourceRefId() : null,
              scenario.getCuPrice(),
              null,
              false);
      if (historical != null
          && (!java.util.Objects.equals(historical.legacyPriceId(), value.legacyPriceId())
              || !java.util.Objects.equals(historical.factorPriceId(), value.factorPriceId()))) {
        throw new IllegalStateException("历史财务场景的Cu来源不一致");
      }
      historical = mergeBasis(historical, value);
    }
    if (historical != null) return historical;
    throw new IllegalStateException("该价格准备未保存财务基价依据，请重新生成最终价格");
  }

  private record SavedFinanceBasis(String oaNo, Long oaFormItemId, PriceFactorContext context) {}

  private boolean linkedPriceType(String value) {
    return "联动价".equals(value) || "LINKED".equals(value) || "LINKED_PRICE".equals(value);
  }

  private boolean rangePriceType(String value) {
    return "RANGE_PRICE".equals(value)
        || com.sanhua.marketingcost.enums.PriceTypeEnum.fromDbText(value).orElse(null)
            == com.sanhua.marketingcost.enums.PriceTypeEnum.RANGE;
  }

  private record CopperBasis(
      Long legacyPriceId,
      Long factorPriceId,
      BigDecimal pricePerKg,
      PriceFactorContext context,
      boolean sharedScrap) {}

  private PricePrepareBatch loadSourceBatch(String prepareNo) {
    PricePrepareBatch batch =
        batchMapper.selectOne(
            Wrappers.lambdaQuery(PricePrepareBatch.class)
                .eq(PricePrepareBatch::getPrepareNo, prepareNo)
                .last("LIMIT 1"));
    if (batch == null) {
      throw new IllegalArgumentException("OA价格准备批次不存在: " + prepareNo);
    }
    return batch;
  }

  private void validateSourceBatch(PricePrepareBatch source) {
    if (!QuotePriceScenarioType.OA_LOCKED.name().equals(source.getScenarioType())) {
      throw new IllegalArgumentException("sourcePrepareNo必须指向OA_LOCKED价格准备批次");
    }
    if (!STATUS_SUCCESS.equals(source.getStatus()) || value(source.getGapCount()) != 0) {
      throw new IllegalStateException("OA价格准备批次必须已成功且无缺口");
    }
    requireText(source.getOaNo(), "OA价格准备批次oaNo");
    requireText(source.getPeriodMonth(), "OA价格准备批次pricing_month");
    requireText(source.getBusinessUnitType(), "OA价格准备批次businessUnitType");
    if (source.getPriceAsOfTime() == null) {
      throw new IllegalStateException("OA价格准备批次缺price_as_of_time");
    }
    if (source.getOaFormItemId() == null || !StringUtils.hasText(source.getTopProductCode())) {
      throw new IllegalStateException("FCQ-05当前只支持一个产品的OA价格准备批次");
    }
  }

  private void validatePreparedFinanceBatch(PricePrepareBatch source, PricePrepareBatch finance) {
    if (!QuotePriceScenarioType.FINANCE_QUOTE_BASE.name().equals(finance.getScenarioType())) {
      throw new IllegalStateException("最终价格财务批次场景不正确，请重新生成最终价格");
    }
    if (!STATUS_SUCCESS.equals(finance.getStatus()) || value(finance.getGapCount()) != 0) {
      throw new IllegalStateException("最终价格财务批次未成功或仍有缺口，请重新生成最终价格");
    }
    requireSame("财务批次", "来源OA批次", source.getPrepareNo(), finance.getSourcePrepareNo());
    requireSame("财务批次", "场景组", source.getScenarioGroupNo(), finance.getScenarioGroupNo());
    requireSame("财务批次", "OA单号", source.getOaNo(), finance.getOaNo());
    requireSame("财务批次", "OA产品行", source.getOaFormItemId(), finance.getOaFormItemId());
    requireSame("财务批次", "顶层产品", source.getTopProductCode(), finance.getTopProductCode());
    requireSame("财务批次", "计价月份", source.getPeriodMonth(), finance.getPeriodMonth());
    requireSame("财务批次", "取价时点", source.getPriceAsOfTime(), finance.getPriceAsOfTime());
    requireSame("财务批次", "业务单元", source.getBusinessUnitType(), finance.getBusinessUnitType());
    requireSame("财务批次", "BOM目的", source.getBomPurpose(), finance.getBomPurpose());
    requireSame("财务批次", "BOM来源", source.getSourceType(), finance.getSourceType());
  }

  private PricePrepareGenerateResult toGenerateResult(PricePrepareBatch batch) {
    PricePrepareGenerateResult result = new PricePrepareGenerateResult();
    result.setPrepareNo(batch.getPrepareNo());
    result.setOaNo(batch.getOaNo());
    result.setOaFormItemId(batch.getOaFormItemId());
    result.setTopProductCode(batch.getTopProductCode());
    result.setPeriodMonth(batch.getPeriodMonth());
    result.setBomPurpose(batch.getBomPurpose());
    result.setSourceType(batch.getSourceType());
    result.setScenarioType(batch.getScenarioType());
    result.setScenarioGroupNo(batch.getScenarioGroupNo());
    result.setSourcePrepareNo(batch.getSourcePrepareNo());
    result.setStatus(batch.getStatus());
    result.setTotalCount(value(batch.getTotalCount()));
    result.setSuccessCount(value(batch.getSuccessCount()));
    result.setWarningCount(value(batch.getWarningCount()));
    result.setGapCount(value(batch.getGapCount()));
    result.setPriceAsOfTime(batch.getPriceAsOfTime());
    result.setPriceAsOfSource(batch.getPriceAsOfSource());
    result.setMessage(batch.getMessage());
    return result;
  }

  private List<PricePrepareItem> loadItems(String prepareNo) {
    List<PricePrepareItem> items =
        itemMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareItem.class)
                .eq(PricePrepareItem::getPrepareNo, prepareNo));
    // Sorting full calculator JSON in MySQL can exhaust its sort buffer even for a
    // small batch. The batch predicate is indexed; order the fetched rows in memory.
    return items == null ? List.of() : items.stream().sorted(java.util.Comparator.comparing(
        PricePrepareItem::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
  }

  private void validateSourceItems(List<PricePrepareItem> sourceItems) {
    if (sourceItems.isEmpty()) {
      throw new IllegalStateException("OA价格准备批次没有价格明细");
    }
    Map<String, PricePrepareItem> keyed = indexBySettlementKey(sourceItems, "OA");
    if (keyed.size() != sourceItems.size()) {
      throw new IllegalStateException("OA价格准备明细结算键不完整");
    }
    for (PricePrepareItem item : sourceItems) {
      if (!ITEM_STATUS_READY.equals(item.getStatus())) {
        throw new IllegalStateException("OA价格准备存在未就绪明细: " + item.getSettlementKey());
      }
    }
  }

  private String ensureScenarioGroup(PricePrepareBatch source) {
    if (StringUtils.hasText(source.getScenarioGroupNo())) {
      return source.getScenarioGroupNo().trim();
    }
    String groupNo = "FQG-" + UUID.randomUUID();
    source.setScenarioGroupNo(groupNo);
    if (batchMapper.updateById(source) != 1) {
      throw new IllegalStateException("OA价格准备场景组编号写入失败");
    }
    return groupNo;
  }

  private void validateSameInputs(
      List<PricePrepareItem> sourceItems, List<PricePrepareItem> financeItems) {
    Map<String, PricePrepareItem> sourceMap = indexBySettlementKey(sourceItems, "OA");
    Map<String, PricePrepareItem> financeMap = indexBySettlementKey(financeItems, "财务");
    if (!sourceMap.keySet().equals(financeMap.keySet())) {
      throw new IllegalStateException("财务与OA价格准备的结算明细范围不一致");
    }
    for (String key : sourceMap.keySet()) {
      PricePrepareItem oa = sourceMap.get(key);
      PricePrepareItem finance = financeMap.get(key);
      requireSame(key, "料号", oa.getMaterialCode(), finance.getMaterialCode());
      requireSame(key, "明细类型", oa.getItemType(), finance.getItemType());
      requireSame(key, "价格结果类型", oa.getResultRefType(), finance.getResultRefType());
      requireSame(key, "数量", oa.getQuantity(), finance.getQuantity());
      var monthly = monthlyEvidence.read(oa);
      if (monthly != null) {
        requireSame(key, "已采用月价及实际用量", monthly, monthlyEvidence.read(finance));
        if (MonthlyPreparationPrice.KIND.equals(oa.getResultRefType())) {
          var customer = monthlyCodec.decode(oa.getInputSnapshotJson(), MonthlyPreparationPrice.class);
          var financial = monthlyCodec.decode(finance.getInputSnapshotJson(), MonthlyPreparationPrice.class);
          requireSame(key, "捕获的双场景价格", customer, financial);
          requireAmount(oa.getUnitPrice(), customer.customerPrice(), "客户 P1");
          requireAmount(finance.getUnitPrice(), customer.financialPrice(), "财务 P0");
          requireAmount(oa.getAmount(), oa.getQuantity().multiply(customer.customerPrice()), "客户材料金额");
          requireAmount(finance.getAmount(), finance.getQuantity().multiply(customer.financialPrice()), "财务材料金额");
          continue;
        }
      }
      if ("RANGE_PRICE".equals(oa.getResultRefType())
          && (StringUtils.hasText(oa.getInputSnapshotJson())
              || StringUtils.hasText(finance.getInputSnapshotJson()))) {
        new OriginalRangeCalculationEvidence(objectMapper)
            .validatePair(
                oa.getMaterialCode(),
                oa.getInputSnapshotJson(),
                finance.getInputSnapshotJson(),
                oa.getUnitPrice(),
                finance.getUnitPrice(),
                null,
                null);
      } else if (oa.getResultRefType() != null && oa.getResultRefType().startsWith("PACKAGE")) {
        new PackageCalculationEvidence(objectMapper).validatePair(oa, finance, null, null);
      } else if (mustRemainExact(oa)) {
        requireSame(key, "非Cu单价", oa.getUnitPrice(), finance.getUnitPrice());
        requireSame(key, "非Cu金额", oa.getAmount(), finance.getAmount());
      }
    }
  }

  private boolean hasMonthlyInputs(List<PricePrepareItem> rows) {
    return rows.stream().allMatch(row -> zeroUse(row) || monthlyEvidence.read(row) != null);
  }

  private void requireMonthlyInputs(List<PricePrepareItem> rows) {
    if (!hasMonthlyInputs(rows)) throw new IllegalStateException("当前财务准备需要完整月价依据，请先重新生成本报价价格准备");
    for (var row : rows) {
      if (zeroUse(row)) continue;
      var inputs = monthlyEvidence.read(row);
      if (inputs.uses().isEmpty() || row.getQuantity() == null)
        throw new IllegalStateException("已计价明细缺少月价实际用量");
      for (var price : inputs.prices()) if (price.selection().monthlyPriceId() == null
          || price.selection().monthlyPriceId() <= 0
          || !row.getPeriodMonth().equals(price.request().family().month().toString())
          || !row.getBusinessUnitType().equals(price.request().family().businessUnitType()))
        throw new IllegalStateException("准备月价尚未采用或不属于本报价月份／业务单元");
    }
  }

  private static boolean zeroUse(PricePrepareItem row) {
    return row.getQuantity() != null && row.getQuantity().signum() == 0
        && row.getAmount() != null && row.getAmount().signum() == 0;
  }

  private static void requireAmount(BigDecimal actual, BigDecimal expected, String field) {
    if (actual == null || expected == null || actual.setScale(8, java.math.RoundingMode.HALF_UP)
        .compareTo(expected.setScale(8, java.math.RoundingMode.HALF_UP)) != 0)
      throw new IllegalStateException(field + "与保存月价不一致");
  }

  private boolean mustRemainExact(PricePrepareItem item) {
    String refType = item == null ? null : item.getResultRefType();
    return refType == null || "FIXED_PRICE".equals(refType) || "RANGE_PRICE".equals(refType)
        || com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceReader.RESULT_REF_TYPE.equals(refType);
  }

  private void validateMakePartInputs(
      List<PricePrepareItem> sourceItems,
      List<PricePrepareItem> financeItems,
      Map<Long, List<MakePartPriceCalcRow>> makeRows) {
    Map<String, PricePrepareItem> financeMap = indexBySettlementKey(financeItems, "财务");
    for (PricePrepareItem sourceItem : sourceItems) {
      if (!REF_MAKE_PART.equals(sourceItem.getResultRefType())) {
        continue;
      }
      PricePrepareItem financeItem = financeMap.get(sourceItem.getSettlementKey());
      List<MakePartPriceCalcRow> sourceRows = loadMakeRows(sourceItem, makeRows);
      List<MakePartPriceCalcRow> financeRows = loadMakeRows(financeItem, makeRows);
      Map<String, MakePartPriceCalcRow> sourceMap = indexMakeRows(sourceRows);
      Map<String, MakePartPriceCalcRow> financeRowMap = indexMakeRows(financeRows);
      if (!sourceMap.keySet().equals(financeRowMap.keySet())) {
        throw new IllegalStateException("制造件子原材料/废料范围不一致: " + sourceItem.getSettlementKey());
      }
      for (String componentKey : sourceMap.keySet()) {
        MakePartPriceCalcRow oa = sourceMap.get(componentKey);
        MakePartPriceCalcRow finance = financeRowMap.get(componentKey);
        requireSame(componentKey, "子项数量", oa.getQtyPerParent(), finance.getQtyPerParent());
        requireSame(componentKey, "毛重", oa.getGrossWeightG(), finance.getGrossWeightG());
        requireSame(componentKey, "净重", oa.getNetWeightG(), finance.getNetWeightG());
        if (monthlyEvidence.read(sourceItem) != null) {
          validateMonthlyMakePrice(sourceItem, oa.getChildMaterialNo(), oa.getRawInputSnapshotJson(),
              finance.getRawInputSnapshotJson(), oa.getRawUnitPrice(), finance.getRawUnitPrice());
          if (!Boolean.TRUE.equals(oa.getNoScrapConfirmed()))
            validateMonthlyMakePrice(sourceItem, oa.getScrapCode(), oa.getScrapInputSnapshotJson(),
                finance.getScrapInputSnapshotJson(), oa.getScrapUnitPrice(), finance.getScrapUnitPrice());
        }
      }
      if (monthlyEvidence.read(sourceItem) != null) {
        requireAmount(sourceItem.getAmount(), sourceItem.getQuantity().multiply(sourceItem.getUnitPrice()), "客户制造件金额");
        requireAmount(financeItem.getAmount(), financeItem.getQuantity().multiply(financeItem.getUnitPrice()), "财务制造件金额");
      }
    }
  }

  private void validateMonthlyMakePrice(PricePrepareItem parent, String code, String source,
      String finance, BigDecimal p1, BigDecimal p0) {
    var captured = monthlyCodec.decode(source, MonthlyPreparationPrice.class);
    requireSame(code, "制造基础月价", captured, monthlyCodec.decode(finance, MonthlyPreparationPrice.class));
    requireSame(code, "制造基础料号", code, captured.request().family().materialCode());
    requireSame(code, "制造基础采用依据", captured.selection(),
        monthlyEvidence.read(parent).plan().selections().get(captured.request()));
    requireAmount(p1, captured.customerPrice().multiply(captured.unitPriceMultiplier()), "制造基础 P1");
    requireAmount(p0, captured.financialPrice().multiply(captured.unitPriceMultiplier()), "制造基础 P0");
  }

  private List<MakePartPriceCalcRow> loadMakeRows(
      PricePrepareItem item, Map<Long, List<MakePartPriceCalcRow>> cache) {
    if (item != null && monthlyEvidence.read(item) != null) {
      var captured = new MakePartCalculationEvidence(objectMapper).rows(item);
      if (captured == null) throw new IllegalStateException("月价制造准备缺少完整计算底稿");
      return captured;
    }
    if (item == null || item.getResultRefId() == null)
      throw new IllegalStateException("制造件价格准备缺结果引用");
    return cache.computeIfAbsent(item.getResultRefId(), ignored -> loadMakeRows(item));
  }

  private List<MakePartPriceCalcRow> loadMakeRows(PricePrepareItem item) {
    if (item == null || item.getResultRefId() == null) {
      throw new IllegalStateException("制造件价格准备缺结果引用");
    }
    MakePartPriceCalcRow referenced = makePartRowMapper.selectById(item.getResultRefId());
    if (referenced == null || !StringUtils.hasText(referenced.getCalcBatchId())) {
      throw new IllegalStateException("制造件价格准备结果引用不存在: " + item.getResultRefId());
    }
    List<MakePartPriceCalcRow> rows =
        makePartRowMapper.selectList(
            Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
                .eq(MakePartPriceCalcRow::getCalcBatchId, referenced.getCalcBatchId())
                .eq(MakePartPriceCalcRow::getParentMaterialNo, referenced.getParentMaterialNo())
                .orderByAsc(MakePartPriceCalcRow::getId));
    return rows == null ? List.of() : rows;
  }

  private Map<String, PricePrepareItem> indexBySettlementKey(
      List<PricePrepareItem> items, String label) {
    Map<String, PricePrepareItem> result = new LinkedHashMap<>();
    for (PricePrepareItem item : items) {
      String key = requireText(item.getSettlementKey(), label + "结算键");
      if (result.putIfAbsent(key, item) != null) {
        throw new IllegalStateException(label + "价格准备明细结算键重复: " + key);
      }
    }
    return result;
  }

  private Map<String, MakePartPriceCalcRow> indexMakeRows(List<MakePartPriceCalcRow> rows) {
    return rows.stream()
        .collect(
            Collectors.toMap(
                row -> text(row.getChildMaterialNo()) + "|" + text(row.getScrapCode()),
                Function.identity(),
                (left, right) -> {
                  throw new IllegalStateException("制造件组件键重复");
                },
                LinkedHashMap::new));
  }

  private void requireSame(String key, String field, Object left, Object right) {
    if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
      if (a.compareTo(b) == 0) {
        return;
      }
    } else if (left == null ? right == null : left.equals(right)) {
      return;
    }
    throw new IllegalStateException(
        "财务与OA价格准备输入不一致: key=" + key + ", field=" + field + ", oa=" + left + ", finance=" + right);
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private boolean sameText(String left, String right) {
    return text(left).equals(text(right));
  }

  private String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }
}
