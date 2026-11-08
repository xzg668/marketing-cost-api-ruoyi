package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceRequest;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationPrice;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;
import com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Component;

/** Projects actual resolved inputs. Only explicitly known consumer coordinates are removed. */
@Component
public final class MonthlyRepriceInputProjection {
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyBomSnapshotDetailService boms;

  public MonthlyRepriceInputProjection(ObjectMapper json, MonthlyPriceDocumentCodec codec,
      MonthlyBomSnapshotDetailService boms) {
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // Sets represent coverage, not BOM ordering. Lists keep every occurrence in original order.
    var module = new com.fasterxml.jackson.databind.module.SimpleModule();
    module.addSerializer(Set.class, new JsonSerializer<Set>() {
      @Override public void serialize(Set value, com.fasterxml.jackson.core.JsonGenerator out,
          SerializerProvider provider) throws java.io.IOException {
        var rows = new ArrayList<JsonNode>();
        for (Object item : value) rows.add(MonthlyRepriceInputProjection.this.json.valueToTree(item));
        rows.sort(Comparator.comparing(node -> SourceEvidence.ofJson("{\"value\":" + node + "}").json()));
        out.writeStartArray(); for (var row : rows) out.writeTree(row); out.writeEndArray();
      }
    });
    this.json.registerModule(module); this.codec = codec; this.boms = boms;
  }

  public MonthlyRepriceInputDocument capture(MonthlyRepriceCalculation calculation, ResolvedCostRun resolved,
      MonthlyQuotationCosting.MaterialInputs materials, QuoteCostRunVersion version) {
    var context = resolved.context(); var technical = context.getEffectiveTechnicalData();
    var paths = new TechnicalPaths(technical);
    var sections = new EnumMap<MonthlyRepriceInputDocument.Section,String>(MonthlyRepriceInputDocument.Section.class);
    var bom = json.createObjectNode(); var raw = bom.putArray("monthlyRows");
    for (var row : boms.load(calculation.bom().getSyncRecordId())) {
      var node = object(row);
      node.remove(List.of("id","sourceU9RowId","sourceImportBatchId","buildBatchId","builtAt"));
      raw.add(node);
    }
    var uses = bom.putArray("actualUses");
    materials.plan().uses().stream().sorted(Comparator.comparing(use -> paths.path(use.path()))).forEach(use -> {
      var node = object(use); node.put("path",paths.path(use.path())); uses.add(node);
    });
    bom.set("suppressedPaths", json.valueToTree(materials.plan().suppressedPaths().stream().map(paths::path).sorted().toList()));
    var parts = bom.putArray("parts");
    for (var part : resolved.parts()) {
      var node = object(part);
      node.remove(List.of("id","bomRowId","pricePrepareItemId","oaNo","remark"));
      parts.add(node);
    }
    sections.put(MonthlyRepriceInputDocument.Section.BOM,bom.toString());

    var quantities = json.createObjectNode();
    var item = calculation.input().item();
    quantities.set("annualUsage",json.valueToTree(calculation.input().annualUsage()));
    var selected = object(item);
    quantities.set("quotation",pick(selected,"packageType","packageMethod","packageComponentCode","packageQty",
        "supportQty","annualVolume","sunlModel","spec","sus304WeightG","sus316WeightG","copperWeightG"));
    var prepared = quantities.putArray("preparations");
    for (var row : materials.preparations().stream().sorted(Comparator.comparing(row -> paths.path(row.path()))).toList()) {
      var node = object(row); node.put("path",paths.path(row.path()));
      node.set("financialInputs",preparation(row.financialInputs(),paths,materials.plan().selections()));
      node.set("customerInputs",preparation(row.customerInputs(),paths,materials.plan().selections())); prepared.add(node);
    }
    sections.put(MonthlyRepriceInputDocument.Section.QUANTITIES_AND_PACKAGING,quantities.toString());

    var prices = json.createArrayNode();
    materials.plan().selections().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(entry -> prices.add(object(Map.of("request",entry.getKey(),"selection",entry.getValue()))));
    sections.put(MonthlyRepriceInputDocument.Section.MATERIAL_PRICES,object(Map.of("prices",prices)).toString());
    var supplier = json.createArrayNode();
    materials.plan().selections().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(entry -> supplier.add(object(Map.of("request",entry.getKey(),
            "applicability",entry.getValue().applicability()))));
    sections.put(MonthlyRepriceInputDocument.Section.SUPPLIER_TERMS,object(Map.of("conditions",supplier)).toString());

    var manufacturing = json.createObjectNode();
    manufacturing.set("ordinary",json.valueToTree(resolved.inputs()));
    manufacturing.set("publicSources",Objects.requireNonNull(context.getPreparedInputs(),"调价复用缺少已解析公共资料").resolvedFacts());
    var form = object(calculation.input().form());
    manufacturing.set("quotationConditions",pick(form,"processCode","applicantUnit","accountingPeriodMonth",
        "sourceSystem","sourceCompany","sourceBusinessDivision","applicantDept","applicantOffice",
        "expenseProductCategory","overseasSalesMode"));
    sections.put(MonthlyRepriceInputDocument.Section.MANUFACTURING,manufacturing.toString());
    sections.put(MonthlyRepriceInputDocument.Section.TECHNICAL_INPUTS,object(Map.of("input",technical(technical,paths))).toString());

    var copper = json.createObjectNode();
    copper.set("financialPerKg",json.valueToTree(version.getFinanceCuPrice()));
    copper.set("customerPerKg",json.valueToTree(version.getOaCuPrice()));
    copper.set("financialBasePriceId",json.valueToTree(version.getFinanceBasePriceId()));
    copper.set("overrides",json.valueToTree(context.getPriceVariableOverrides()));
    copper.set("customerMaterialPrices",json.valueToTree(CustomerMaterialPrices.fromQuotation(calculation.input().form())));
    copper.set("householdCustomerPrices",json.valueToTree(materials.householdCustomerPrices()));
    sections.put(MonthlyRepriceInputDocument.Section.COPPER_SCENARIOS,copper.toString());
    sections.put(MonthlyRepriceInputDocument.Section.POLICIES,
        object(Map.of("projectionVersion",3,"algorithm",MonthlyProductCost.ALGORITHM_VERSION,
            "materialPriceProjection",CustomerMaterialPrices.CALCULATION_VERSION,
            "fees",ProductFeeContract.POLICY_VERSION,"priceAsOf",calculation.priceAsOfTime())).toString());
    return MonthlyRepriceInputDocument.capture(new MonthlyRepriceInputDocument.Scope(context.getBusinessUnitType(),
        YearMonth.parse(context.getPricingMonth()),context.getProductCode(),calculation.input().context().organization(),
        MonthlyProductCost.ALGORITHM_VERSION,ProductFeeContract.POLICY_VERSION),sections);
  }

  private JsonNode technical(EffectiveTechnicalDataInput input, TechnicalPaths paths) {
    if (input == null) return NullNode.instance;
    var result = object(input);
    result.remove(List.of("productId","versionId","versionNo","retrievedAt","contentFingerprint","moduleSources"));
    for (String field : List.of("packageItems","auxiliaryItems","salaryItems"))
      for (var row : result.path(field)) ((ObjectNode) row).remove("id");
    for (var row : result.path("materialItems")) {
      var node = (ObjectNode) row; node.remove("versionId"); node.put("itemKey",paths.key(node.path("itemKey").asText()));
    }
    for (var row : result.path("priceSources")) ((ObjectNode) row)
        .remove(List.of("productId","versionId","approvedModuleVersionId","contentFingerprint"));
    return result;
  }

  /** Known preparation schemas retain all formula/quantity/unit fields and public price identities. */
  JsonNode preparation(String document, TechnicalPaths paths, Map<MonthlyPriceRequest,Selection> adopted) {
    try {
      var node = json.readTree(document);
      if (node.has("schemaVersion") && node.has("document")) node = json.readTree(codec.unwrap(document));
      return preparation(node,paths,adopted);
    } catch (java.io.IOException error) { throw new IllegalArgumentException("调价计算输入底稿不可读取",error); }
  }

  private JsonNode preparation(JsonNode input, TechnicalPaths paths, Map<MonthlyPriceRequest,Selection> adopted) {
    if (input.has("schemaVersion") && input.has("document")) return preparation(input.toString(), paths,adopted);
    if (!(input instanceof ObjectNode source)) throw new IllegalArgumentException("调价底稿须为完整对象");
    var node = source.deepCopy();
    // Complete adopted selections/uses are captured once in MATERIAL_PRICES and BOM.
    node.remove("monthlyInputs");
    if ("MONTHLY_PRICE".equals(node.path("sourceKind").asText())) {
      var captured=codec.decode(codec.wrap(node.toString()),MonthlyPreparationPrice.class);
      var selected=adopted.get(captured.request());
      if (selected!=null && !selected.equals(captured.selection()))
        throw new IllegalArgumentException("准备价格与本次完整已采用月价不一致");
      // MATERIAL_PRICES contains the exact public fact once. Keep this occurrence's full request,
      // quantity/conversion/P0/P1 and ID; unadopted zero-use evidence stays self-contained.
      if (selected!=null && selected.monthlyPriceId()!=null && selected.monthlyPriceId()>0)
        node.putObject("selection").put("monthlyPriceId",selected.monthlyPriceId());
      if (node.hasNonNull("path")) node.put("path",paths.path(node.path("path").asText()));
    } else if ("MAKE_PART_V1".equals(node.path("calculatorVersion").asText())) {
      node.remove("sourceCostingRowId");
      for (var value : node.path("rows")) {
        var row = (ObjectNode) value;
        row.remove(List.of("id","calcBatchId","sourceCostingRowId","oaNo","noScrapConfirmationId","createdAt"));
        for (String field : List.of("rawInputSnapshotJson","scrapInputSnapshotJson"))
          if (row.hasNonNull(field)) row.set(field,preparation(row.path(field).asText(),paths,adopted));
      }
    } else if ("PACKAGE_PRICE_V1".equals(node.path("calculatorVersion").asText())) {
      node.remove(List.of("snapshotId","sourceRawHierarchyId"));
      if (node.hasNonNull("sourcePath")) node.put("sourcePath",paths.path(node.path("sourcePath").asText()));
      for (var value : node.path("components")) {
        var row = (ObjectNode) value; row.remove(List.of("snapshotDetailId","sourceHierarchyId"));
        if (row.hasNonNull("sourcePath")) row.put("sourcePath",paths.path(row.path("sourcePath").asText()));
        if (row.path("householdUse") instanceof ObjectNode use) use.put("path",paths.path(use.path("path").asText()));
      }
    }
    for (var value : node.path("components")) if (value.path("inputs").isObject())
      ((ObjectNode) value).set("inputs",preparation(value.path("inputs"),paths,adopted));
    return node;
  }

  private ObjectNode object(Object value) { return json.valueToTree(value); }
  private ObjectNode pick(ObjectNode input, String... fields) {
    var result = json.createObjectNode(); for (var field : fields) result.set(field,input.path(field)); return result;
  }

  /** Preserve occurrence/order while replacing only known technical contribution coordinates. */
  static final class TechnicalPaths {
    private final Map<String,String> keys = new LinkedHashMap<>();
    TechnicalPaths(EffectiveTechnicalDataInput input) {
      if (input != null) for (var row : input.materialItems()) {
        if (keys.putIfAbsent(row.itemKey(),"INPUT_" + keys.size()) != null)
          throw new IllegalArgumentException("有效技术材料节点重复");
      }
    }
    String key(String source) { return Objects.requireNonNull(keys.get(source),"技术节点未在有效输入中"); }
    String path(String source) {
      var segments = source.split("/",-1);
      for (int i=0; i<segments.length; i++) for (var entry : keys.entrySet()) {
        String suffix = "@TECH_" + entry.getKey().replace(':','_');
        if (segments[i].endsWith(suffix)) {
          segments[i] = segments[i].substring(0,segments[i].length()-suffix.length()) + "@TECH_" + entry.getValue();
          break;
        }
      }
      return String.join("/",segments);
    }
  }
}
