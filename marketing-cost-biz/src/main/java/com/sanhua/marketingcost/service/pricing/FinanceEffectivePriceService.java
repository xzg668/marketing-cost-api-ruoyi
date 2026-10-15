package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.FinanceEffectivePriceMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleCalculator;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot;
import com.sanhua.marketingcost.service.impl.OriginalRangeRuleCalculator;
import com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 公共有效价生成与只读查询。算法由原稿计算器负责，选供应商不属于本服务。 */
@Service
public class FinanceEffectivePriceService {
  public static final String CALCULATOR_VERSION = "FINANCE_EFFECTIVE_V1";
  private static final ObjectMapper FINGERPRINT_JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final int WRITE_BATCH_SIZE = 200;
  private static final int WRITE_BATCH_BYTES = 2 * 1024 * 1024;
  private final FinanceEffectivePriceMapper results;
  private final PriceLinkedItemMapper linked;
  private final PriceRangeFactorRuleMapper ranges;
  private final FactorUploadBatchMapper publications;
  private final FinanceFactorPriceReader finance;
  private final OriginalRangeRuleSnapshot rangeSnapshots;
  private final OriginalLinkedRuleCalculator linkedCalculator;
  private final OriginalRangeRuleCalculator rangeCalculator;
  private final ObjectMapper json;
  private final com.sanhua.marketingcost.service.impl.OriginalLinkedPublicationSource linkedSources;

  public FinanceEffectivePriceService(
      FinanceEffectivePriceMapper results,
      PriceLinkedItemMapper linked,
      PriceRangeFactorRuleMapper ranges,
      FactorUploadBatchMapper publications,
      FinanceFactorPriceReader finance,
      OriginalRangeRuleSnapshot rangeSnapshots,
      OriginalLinkedRuleCalculator linkedCalculator,
      OriginalRangeRuleCalculator rangeCalculator,
      ObjectMapper json,
      com.sanhua.marketingcost.service.impl.OriginalLinkedPublicationSource linkedSources) {
    this.results = results;
    this.linked = linked;
    this.ranges = ranges;
    this.publications = publications;
    this.finance = finance;
    this.rangeSnapshots = rangeSnapshots;
    this.linkedCalculator = linkedCalculator;
    this.rangeCalculator = rangeCalculator;
    this.json = json;
    this.linkedSources = linkedSources;
  }

  public record Generation(int generated, int reused, int waitingRules) {
    public Generation plus(Generation other) {
      return new Generation(
          generated + other.generated, reused + other.reused, waitingRules + other.waitingRules);
    }
  }

  /** 既有正式资料的显式初始化；和导入发布采用同一生成规则，查询不调用本入口。 */
  @Transactional(rollbackFor = Exception.class)
  public Generation initialize(String businessUnit, String organization) {
    String unit =
        FactorPriceScope.required(businessUnit, "业务单元", 32).toUpperCase(java.util.Locale.ROOT);
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(unit))
      throw new IllegalArgumentException("业务单元仅支持COMMERCIAL/HOUSEHOLD");
    String org =
        com.sanhua.marketingcost.enums.MaterialOrganization.fromPriceOrgCode(organization)
            .getPriceOrgCode();
    return generateScope(unit, org);
  }

  /** 同步发布只调用此方法；事务和目录锁由发布应用服务持有。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public Generation generateScope(String businessUnit, String organization) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("财务有效价必须在原稿或基价发布事务内生成");
    var linkedRules =
        linked.selectList(
            Wrappers.lambdaQuery(PriceLinkedItem.class)
                .eq(PriceLinkedItem::getBusinessUnitType, businessUnit)
                .eq(PriceLinkedItem::getOrgCode, organization)
                .eq(PriceLinkedItem::getSourceKind, "PUBLIC")
                .eq(PriceLinkedItem::getDeleted, 0)
                .eq(PriceLinkedItem::getRuleStatus, "PUBLISHED")
                .eq(PriceLinkedItem::getExpressionVersion, OriginalLinkedRuleSnapshot.VERSION));
    var rangeRules =
        ranges
            .selectList(
                Wrappers.lambdaQuery(PriceRangeFactorRule.class)
                    .eq(PriceRangeFactorRule::getBusinessUnitType, businessUnit)
                    .eq(PriceRangeFactorRule::getOrgCode, organization)
                    .eq(PriceRangeFactorRule::getRuleStatus, "PUBLISHED")
                    .isNotNull(PriceRangeFactorRule::getPricingMonth))
            .stream()
            .filter(OriginalRangeRuleSnapshot::isOriginal)
            .toList();
    var batches =
        publications.selectList(
            Wrappers.lambdaQuery(FactorUploadBatch.class)
                .eq(FactorUploadBatch::getBusinessUnitType, businessUnit)
                .eq(FactorUploadBatch::getPriceOrgCode, organization)
                .eq(FactorUploadBatch::getImportType, "FINANCE_BASE")
                .eq(FactorUploadBatch::getStatus, "PUBLISHED")
                .isNotNull(FactorUploadBatch::getPublishedAt));
    var financeByMonth = new TreeMap<String, FactorUploadBatch>();
    for (var batch : batches)
      financeByMonth.merge(
          batch.getPriceMonth(),
          batch,
          (a, b) -> a.getPublicationRevision() >= b.getPublicationRevision() ? a : b);
    var rulesByMonth = new TreeMap<String, Map<String, SourceRule>>();
    for (var rule : linkedRules) add(rulesByMonth, SourceRule.linked(rule));
    for (var rule : rangeRules) add(rulesByMonth, SourceRule.range(rule));
    var existing = new HashSet<String>();
    results
        .selectList(
            Wrappers.lambdaQuery(FinanceEffectivePrice.class)
                .select(FinanceEffectivePrice::getInputFingerprint)
                .eq(FinanceEffectivePrice::getBusinessUnitType, businessUnit)
                .eq(FinanceEffectivePrice::getOrgCode, organization))
        .forEach(row -> existing.add(row.getInputFingerprint()));
    var boundaries = new TreeSet<>(rulesByMonth.keySet());
    boundaries.addAll(financeByMonth.keySet());
    var pending = new ArrayList<FinanceEffectivePrice>();
    Set<String> visited = new HashSet<>();
    Set<String> waiting = new HashSet<>();
    int reused = 0;
    int generated = 0;
    int pendingBytes = 0;
    var snapshots = new HashMap<String, FactorPriceSnapshot>();
    var bindings =
        new com.sanhua.marketingcost.service.impl.OriginalLinkedPublicationSource.BindingBatch();
    for (String month : boundaries) {
      var original = rulesByMonth.floorEntry(month);
      if (original == null) continue;
      var published = financeByMonth.floorEntry(month);
      if (published == null) {
        original.getValue().values().forEach(r -> waiting.add(r.type() + ":" + r.id()));
        continue;
      }
      var batch = published.getValue();
      var conditions =
          "220".equals(organization)
              ? List.of(FactorPriceScope.Condition.NON_TENDER, FactorPriceScope.Condition.TENDER)
              : List.of(FactorPriceScope.Condition.GENERAL);
      for (var condition : conditions) {
        String snapshotKey = batch.getId() + ":" + condition;
        var snapshot =
            snapshots.computeIfAbsent(
                snapshotKey,
                ignored ->
                    finance.read(
                        new FinanceFactorImportContext(
                            businessUnit,
                            organization,
                            batch.getPriceMonth(),
                            null,
                            batch.getDefaultSchemeCode()),
                        condition,
                        batch.getPublicationRevision()));
        for (var rule : original.getValue().values()) {
          String fingerprint = inputFingerprint(rule, snapshot);
          if (!visited.add(fingerprint)) continue;
          waiting.remove(rule.type() + ":" + rule.id());
          if (existing.contains(fingerprint)) {
            reused++;
            continue;
          }
          var calculated = calculate(rule, snapshot, bindings);
          int rowBytes = calculated.getCalculationJson().getBytes(StandardCharsets.UTF_8).length;
          if (!pending.isEmpty() && pendingBytes + rowBytes > WRITE_BATCH_BYTES) {
            results.insertBatch(pending);
            pending.clear();
            pendingBytes = 0;
          }
          pending.add(calculated);
          pendingBytes += rowBytes;
          generated++;
          if (pending.size() == WRITE_BATCH_SIZE) {
            results.insertBatch(pending);
            pending.clear();
            pendingBytes = 0;
          }
        }
      }
    }
    if (!pending.isEmpty()) results.insertBatch(pending);
    return new Generation(generated, reused, waiting.size());
  }

  private static void add(Map<String, Map<String, SourceRule>> byMonth, SourceRule rule) {
    FactorPriceScope.month(rule.month());
    byMonth
        .computeIfAbsent(rule.month(), ignored -> new LinkedHashMap<>())
        .merge(
            rule.type() + ":" + rule.key().identityKey(),
            rule,
            (a, b) -> a.revision() >= b.revision() ? a : b);
  }

  private FinanceEffectivePrice calculate(
      SourceRule source,
      FactorPriceSnapshot snapshot,
      com.sanhua.marketingcost.service.impl.OriginalLinkedPublicationSource.BindingBatch bindings) {
    String fromMonth =
        source.month().compareTo(snapshot.scope().priceMonth()) >= 0
            ? source.month()
            : snapshot.scope().priceMonth();
    var context =
        new PriceFactorContext(
            source.key().materialCode(),
            new PriceMonthSelection(fromMonth, snapshot.scope().priceMonth()),
            snapshot,
            null);
    if ("220".equals(snapshot.scope().priceOrgCode()))
      context =
          context.withBoardInputs(
              new BoardPriceInputs(
                  null, null, snapshot.scope().condition() == FactorPriceScope.Condition.TENDER));
    try {
      OriginalLinkedRuleCalculator.Result linkedResult = null;
      OriginalRangeRuleCalculator.Result rangeResult = null;
      com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule boundRule = null;
      Set<String> factorKeys;
      if (source.linked() != null) {
        var rule = linkedSources.read(source.linked(), bindings);
        boundRule = rule;
        linkedResult = linkedCalculator.calculate(rule, context);
        factorKeys =
            rule.factors().values().stream()
                .map(f -> f.canonicalKey())
                .collect(java.util.stream.Collectors.toSet());
      } else {
        var rule = rangeSnapshots.read(source.range()).rule();
        rangeResult = rangeCalculator.calculate(rule, context);
        factorKeys = Set.of(rule.factorKey());
      }
      var evidence =
          new Calculation(
              context.evidenceForFactors(factorKeys), linkedResult, rangeResult, boundRule);
      var row = new FinanceEffectivePrice();
      var key = source.key();
      row.setBusinessUnitType(key.businessUnitType());
      row.setOrgCode(key.orgCode());
      row.setPriceType(source.type());
      row.setOriginalId(source.id());
      row.setOriginalRevision(source.revision());
      row.setOriginalMonth(source.month());
      row.setRuleIdentityKey(key.identityKey());
      row.setRuleFingerprint(source.fingerprint());
      row.setMaterialCode(key.materialCode());
      row.setSupplierCode(key.supplierCode());
      row.setSupplierName(key.supplierName());
      row.setSpecification(key.specification());
      row.setUnit(key.unit());
      row.setFinanceBatchId(snapshot.batchId());
      row.setFinanceMonth(snapshot.scope().priceMonth());
      row.setSchemeCode(snapshot.scope().schemeCode());
      row.setConditionCode(snapshot.scope().condition().name());
      row.setEffectiveFromMonth(fromMonth);
      row.setCalculatorVersion(CALCULATOR_VERSION);
      row.setInputFingerprint(inputFingerprint(source, snapshot));
      row.setUnitPrice(
          linkedResult != null
              ? linkedResult.priceExcludingTax()
              : rangeResult.priceExcludingTax());
      row.setCalculationJson(json.writeValueAsString(evidence));
      row.setCreatedAt(LocalDateTime.now());
      return row;
    } catch (IllegalArgumentException | JsonProcessingException failure) {
      throw new IllegalArgumentException(
          "财务有效价生成失败：类型="
              + source.type()
              + "，组织="
              + source.key().orgCode()
              + "，物料="
              + source.key().materialCode()
              + "，供应商="
              + source.key().supplierCode()
              + "，原稿="
              + source.id()
              + "，财务发布="
              + snapshot.batchId()
              + "："
              + failure.getMessage(),
          failure);
    }
  }

  /** 按已选的正式财务版本批量读取，缺失交给调用方明确报错，不在查询中补算。 */
  public Map<Long, FinanceEffectivePrice> read(
      String priceType, Collection<Long> originalIds, FactorPriceSnapshot snapshot) {
    if (!Set.of("LINKED", "RANGE").contains(priceType))
      throw new IllegalArgumentException("不支持的公共有效价类型：" + priceType);
    if (originalIds.isEmpty()) return Map.of();
    var answer = new LinkedHashMap<Long, FinanceEffectivePrice>();
    var rows =
        results.selectList(
            Wrappers.lambdaQuery(FinanceEffectivePrice.class)
                .eq(FinanceEffectivePrice::getBusinessUnitType, snapshot.scope().businessUnitType())
                .eq(FinanceEffectivePrice::getOrgCode, snapshot.scope().priceOrgCode())
                .eq(FinanceEffectivePrice::getPriceType, priceType)
                .eq(FinanceEffectivePrice::getFinanceBatchId, snapshot.batchId())
                .eq(FinanceEffectivePrice::getSchemeCode, snapshot.scope().schemeCode())
                .eq(FinanceEffectivePrice::getConditionCode, snapshot.scope().condition().name())
                .eq(FinanceEffectivePrice::getCalculatorVersion, CALCULATOR_VERSION)
                .in(FinanceEffectivePrice::getOriginalId, originalIds));
    for (var row : rows) {
      if (answer.putIfAbsent(row.getOriginalId(), row) != null)
        throw new IllegalArgumentException("同一原稿版本存在冲突的财务有效价：" + row.getOriginalId());
    }
    return answer;
  }

  public FinanceEffectivePrice require(PriceLinkedItem rule, FactorPriceSnapshot finance) {
    return require(SourceRule.linked(rule), read("LINKED", List.of(rule.getId()), finance));
  }

  public FinanceEffectivePrice require(PriceRangeFactorRule rule, FactorPriceSnapshot finance) {
    return require(SourceRule.range(rule), read("RANGE", List.of(rule.getId()), finance));
  }

  public FinanceEffectivePrice require(SourceRule rule, Map<Long, FinanceEffectivePrice> values) {
    var row = values.get(rule.id());
    if (row == null
        || !rule.fingerprint().equals(row.getRuleFingerprint())
        || !Objects.equals(rule.revision(), row.getOriginalRevision()))
      throw new IllegalArgumentException(
          "财务有效价未就绪或来源已变化：物料="
              + rule.key().materialCode()
              + "，供应商="
              + rule.key().supplierCode()
              + "，原稿="
              + rule.id()
              + "；请完成原稿/基价发布或有效价初始化");
    return row;
  }

  public Calculation calculation(FinanceEffectivePrice row) {
    try {
      var saved = json.readValue(row.getCalculationJson(), Calculation.class);
      if (!Objects.equals(saved.context().financial().batchId(), row.getFinanceBatchId()))
        throw new IllegalArgumentException("有效价计算依据与财务发布不一致：" + row.getId());
      return saved;
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("有效价计算依据损坏：" + row.getId(), failure);
    }
  }

  public record Calculation(
      PriceFactorContext context,
      OriginalLinkedRuleCalculator.Result linked,
      OriginalRangeRuleCalculator.Result range,
      com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule linkedRule) {}

  private static String inputFingerprint(SourceRule rule, FactorPriceSnapshot finance) {
    return hash(
        String.join(
            "|",
            rule.type(),
            rule.id().toString(),
            rule.revision().toString(),
            rule.fingerprint(),
            finance.batchId().toString(),
            finance.scope().schemeCode(),
            finance.scope().condition().name(),
            CALCULATOR_VERSION));
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM不支持SHA-256", impossible);
    }
  }

  /** MySQL JSON会规范空白及字段顺序；来源摘要按内容比较，不把存储格式当成规则修订。 */
  private static String canonicalSnapshot(String value) {
    try {
      return FINGERPRINT_JSON.writeValueAsString(
          normalizeNumbers(FINGERPRINT_JSON.readValue(value, Object.class)));
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("原稿来源快照不是有效JSON", failure);
    }
  }

  private static Object normalizeNumbers(Object value) {
    if (value instanceof java.math.BigDecimal decimal) return decimal.stripTrailingZeros();
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put(key.toString(), normalizeNumbers(item)));
      return sorted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(FinanceEffectivePriceService::normalizeNumbers).toList();
    return value;
  }

  public record SourceRule(
      String type,
      Long id,
      Integer revision,
      String month,
      MaterialPriceRuleKey key,
      String fingerprint,
      PriceLinkedItem linked,
      PriceRangeFactorRule range) {
    public static SourceRule linked(PriceLinkedItem row) {
      var key =
          new MaterialPriceRuleKey(
              row.getBusinessUnitType(),
              row.getOrgCode(),
              row.getMaterialCode(),
              row.getSupplierCode(),
              row.getSupplierName(),
              row.getSpecModel(),
              row.getUnit());
      return new SourceRule(
          "LINKED",
          row.getId(),
          row.getRuleRevision(),
          row.getPricingMonth(),
          key,
          hash(
              key.identityKey()
                  + "|"
                  + row.getPricingMonth()
                  + "|"
                  + row.getFormulaExpr()
                  + "|"
                  + row.getTaxIncluded()
                  + "|"
                  + canonicalSnapshot(row.getSourceInputSnapshotJson())),
          row,
          null);
    }

    public static SourceRule range(PriceRangeFactorRule row) {
      var key =
          new MaterialPriceRuleKey(
              row.getBusinessUnitType(),
              row.getOrgCode(),
              row.getMaterialCode(),
              row.getSupplierCode(),
              row.getSupplierName(),
              row.getSpecModel(),
              row.getPriceUnit());
      return new SourceRule(
          "RANGE",
          row.getId(),
          row.getVersionNo(),
          row.getPricingMonth(),
          key,
          hash(
              key.identityKey()
                  + "|"
                  + row.getPricingMonth()
                  + "|"
                  + row.getFactorCode()
                  + "|"
                  + row.getFactorUnit()
                  + "|"
                  + canonicalSnapshot(row.getSourceInputSnapshotJson())),
          null,
          row);
    }
  }
}
