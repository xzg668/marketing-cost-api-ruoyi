package com.sanhua.marketingcost.service.costing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.service.NetLossRateQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.BeanUtils;

/** One consumer's prepared input, with an immutable catalog of applicable public facts. */
public final class CostRunPreparedInputs {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private record Identity(String oaNo, Long itemId, String product, String month, String unit,
      String materialOrg, String priceOrg, String scene, String repriceNo, String objectKey,
      java.time.LocalDateTime priceAsOf, String priceScenario) {
    static Identity of(CostRunContext context) {
      return new Identity(context.getOaNo(), context.getOaFormItemId(), context.getProductCode(),
          context.getPricingMonth(), context.getBusinessUnitType(),
          context.getMaterialOrganizationCode(), context.getPriceOrgCode(), context.getScene(),
          context.getRepriceNo(), context.getCalcObjectKey(), context.getPriceAsOfTime(),
          context.getPriceScenarioType());
    }
  }

  /** Built once per bounded batch. A missing request is a preparation error, never a live-read fallback. */
  public static final class Catalog {
    private final Map<Object, List<?>> rows;
    private final Set<Object> consumed = new java.util.LinkedHashSet<>();

    public Catalog(Map<Object, List<?>> rows) {
      var frozen = new LinkedHashMap<Object, List<?>>();
      rows.forEach((key, values) -> frozen.put(Objects.requireNonNull(key), copy(values)));
      this.rows = Collections.unmodifiableMap(frozen);
    }

    private Catalog(Catalog shared) { rows = shared.rows; }

    public synchronized <T> List<T> require(Object query, Class<T> rowType) {
      var found = rows.get(query);
      if (found == null) throw new IllegalStateException("未准备本次核算需要的公共资料：" + query);
      consumed.add(query);
      return copy(found).stream().map(rowType::cast).toList();
    }

    /** Only this consumer's actual lookups, not other products in the bounded preparation batch. */
    synchronized JsonNode resolvedFacts() {
      var facts = new ArrayList<JsonNode>();
      for (var query : consumed) {
        var fact = JSON.createObjectNode();
        fact.put("kind", query.getClass().getSimpleName());
        fact.set("condition", JSON.valueToTree(query)); fact.set("rows", JSON.valueToTree(rows.get(query)));
        facts.add(fact);
      }
      facts.sort(java.util.Comparator.comparing(JsonNode::toString));
      return JSON.valueToTree(facts);
    }

    public <T> T one(Object query, Class<T> rowType) {
      var found = require(query, rowType);
      if (found.size() > 1) throw new org.apache.ibatis.exceptions.TooManyResultsException("公共资料存在多条匹配：" + query);
      return found.isEmpty() ? null : found.getFirst();
    }
  }

  private final Identity identity;
  private final JsonNode quotation;
  private final JsonNode technical;
  private final List<String> materialOrder;
  private final List<CmsCostSourceEffective> salary;
  private final List<AuxCostItemDto> auxiliary;
  private final Catalog fees;
  private final NetLossRateQuery.Source netLoss;
  private final int propertyYear;
  private final String ruleMonth;

  public CostRunPreparedInputs(CostRunContext context, Set<String> materialCodes,
      CostRunManufacturingSources.Sources manufacturing, Catalog fees,
      NetLossRateQuery.Source netLoss, int propertyYear, String ruleMonth) {
    Objects.requireNonNull(context.getQuotationInput(), "批量准备须使用选定的完整OA资料")
        .requireFor(context, context.getOaNo(), context.getProductCode());
    this.identity = Identity.of(context);
    this.quotation = JSON.valueToTree(context.getQuotationInput());
    this.technical = technicalFact(context.getEffectiveTechnicalData());
    this.materialOrder = List.copyOf(materialCodes);
    this.salary = copy(manufacturing.salary());
    this.auxiliary = copy(manufacturing.auxiliary());
    this.fees = new Catalog(Objects.requireNonNull(fees));
    this.netLoss = Objects.requireNonNull(netLoss);
    this.propertyYear = propertyYear;
    this.ruleMonth = Objects.requireNonNull(ruleMonth);
  }

  public void requireFor(CostRunContext context, Set<String> materialCodes) {
    if (!identity.equals(Identity.of(context))
        || !materialOrder.equals(new ArrayList<>(materialCodes))
        || !technical.equals(technicalFact(context.getEffectiveTechnicalData()))
        || !quotation.equals(JSON.valueToTree(context.getQuotationInput()))) {
      throw new IllegalStateException("核算输入或有效资料已变化，必须重新批量解析");
    }
  }

  public List<CmsCostSourceEffective> salary() { return copy(salary); }
  public List<AuxCostItemDto> auxiliary() { return copy(auxiliary); }
  public Catalog fees() { return fees; }
  public NetLossRateQuery.Source netLoss() { return netLoss; }
  public int propertyYear() { return propertyYear; }
  public String ruleMonth() { return ruleMonth; }

  /** Defensive value document of the exact applicable public inputs used by the original resolver. */
  public JsonNode resolvedFacts() {
    var result = JSON.createObjectNode();
    result.set("fees", fees.resolvedFacts()); result.set("salary", JSON.valueToTree(salary));
    result.set("auxiliary", JSON.valueToTree(auxiliary)); result.set("netLoss", JSON.valueToTree(netLoss));
    result.put("propertyYear", propertyYear); result.put("ruleMonth", ruleMonth);
    return result;
  }

  private static JsonNode technicalFact(EffectiveTechnicalDataInput input) {
    JsonNode value = JSON.valueToTree(input);
    // Retrieval time changes on every read; approval identities and all actual values remain compared.
    if (value.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) value).remove("retrievedAt");
    return value;
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> copy(List<T> source) {
    return source.stream().map(row -> {
      if (row.getClass().isRecord()) return row;
      T target = (T) BeanUtils.instantiateClass(row.getClass());
      BeanUtils.copyProperties(row, target);
      return target;
    }).toList();
  }
}
