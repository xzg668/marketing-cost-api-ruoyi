package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationPrice;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 包装两侧使用同一结构及子件来源；只允许已保存因素依据证明的Cu价差。 */
final class PackageCalculationEvidence {
  static final String VERSION = "PACKAGE_PRICE_V1";
  private final ObjectMapper json;

  PackageCalculationEvidence(ObjectMapper json) {
    this.json = json;
  }

  String capture(PackageComponentPrice price, List<PackageComponentPriceDetail> details) {
    try {
      var snapshot = json.createObjectNode();
      snapshot.put("calculatorVersion", VERSION);
      snapshot.put("materialCode", price.getPackageMaterialCode());
      snapshot.put("businessUnitType", price.getBusinessUnitType());
      snapshot.put("priceOrgCode", price.getPriceOrgCode());
      snapshot.put("periodMonth", price.getPeriodMonth());
      snapshot.put("snapshotId", price.getSnapshotId());
      if (price.getSourceRawHierarchyId() != null) snapshot.put("sourceRawHierarchyId", price.getSourceRawHierarchyId());
      if (price.getSourcePath() != null) snapshot.put("sourcePath", price.getSourcePath());
      snapshot.put("parentBaseQty", price.getPackageParentBaseQty());
      var components = snapshot.putArray("components");
      for (var detail : details) {
        var row = components.addObject();
        row.put("snapshotDetailId", detail.getSnapshotDetailId());
        row.put("materialCode", detail.getChildMaterialCode());
        row.put("priceSource", detail.getPriceSource());
        row.put("priceType", detail.getPriceType());
        row.put("quantity", detail.getQtyPerParent());
        row.put("parentBaseQty", detail.getChildParentBaseQty());
        row.put("unitPrice", detail.getChildUnitPrice());
        row.put("amount", detail.getChildAmount());
        if (detail.getSourceHierarchyId() != null) row.put("sourceHierarchyId", detail.getSourceHierarchyId());
        if (detail.getSourcePath() != null) row.put("sourcePath", detail.getSourcePath());
        if (detail.getHouseholdUse() != null) {
          row.put("sourcePath", detail.getSourcePath());
          row.set("householdUse", json.valueToTree(detail.getHouseholdUse()));
        }
        if (detail.getInputSnapshotJson() != null)
          row.set("inputs", read(detail.getInputSnapshotJson()));
      }
      return json.writeValueAsString(snapshot);
    } catch (Exception error) {
      throw new IllegalStateException("包装子件取价底稿保存失败", error);
    }
  }

  boolean validatePair(
      PricePrepareItem customer,
      PricePrepareItem finance,
      BigDecimal customerCu,
      BigDecimal financeCu) {
    var oa = read(customer.getInputSnapshotJson());
    var fin = read(finance.getInputSnapshotJson());
    // 已冻结的旧包装底稿没有结构版本，沿用其原有两侧必须相等的约束。
    if (!VERSION.equals(oa.path("calculatorVersion").asText())
        && !VERSION.equals(fin.path("calculatorVersion").asText())) {
      samePrice(customer.getUnitPrice(), finance.getUnitPrice(), "旧包装单价");
      samePrice(customer.getAmount(), finance.getAmount(), "旧包装金额");
      return false;
    }
    for (String key :
        List.of(
            "calculatorVersion",
            "materialCode",
            "businessUnitType",
            "priceOrgCode",
            "periodMonth",
            "snapshotId",
            "sourceRawHierarchyId",
            "sourcePath",
            "parentBaseQty")) same(oa.get(key), fin.get(key), key);
    for (String key :
        List.of("materialCode", "businessUnitType", "priceOrgCode", "periodMonth"))
      if (!oa.hasNonNull(key) || oa.path(key).asText().isBlank())
        throw new IllegalStateException("包装底稿缺少范围：" + key);
    validateStructureIdentity(oa);
    validateStructureIdentity(fin);
    same(customer.getMaterialCode(), oa.path("materialCode").asText(), "包装料号");
    same(customer.getBusinessUnitType(), oa.path("businessUnitType").asText(), "包装业务单元");
    same(customer.getPeriodMonth(), oa.path("periodMonth").asText(), "包装月份");
    var left = components(oa);
    var right = components(fin);
    same(left.keySet(), right.keySet(), "包装子件范围");
    boolean copper = false;
    BigDecimal oaTotal = BigDecimal.ZERO, finTotal = BigDecimal.ZERO;
    for (String key : left.keySet()) {
      var a = left.get(key);
      var b = right.get(key);
      for (String field :
          List.of("materialCode", "priceType", "priceSource", "quantity", "parentBaseQty", "sourceHierarchyId", "sourcePath"))
        same(a.get(field), b.get(field), "包装子件" + field);
      var excluded = household(a);
      if (excluded != null || household(b) != null) {
        same(excluded, household(b), "包装家用独立用量");
        same(a.get("sourcePath"), b.get("sourcePath"), "包装家用原始路径");
        continue;
      }
      String type = a.path("priceType").asText();
      BigDecimal ap = decimal(a, "unitPrice"), bp = decimal(b, "unitPrice");
      if (MonthlyPreparationPrice.KIND.equals(a.path("priceSource").asText())) {
        var codec = new MonthlyPriceDocumentCodec(json);
        var captured = codec.decode(a.path("inputs").toString(), MonthlyPreparationPrice.class);
        var financial = codec.decode(b.path("inputs").toString(), MonthlyPreparationPrice.class);
        same(captured, financial, "包装子件保存月价");
        same(a.path("materialCode").asText(), captured.request().family().materialCode(), "包装子件月价料号");
        var inputs = new MonthlyPreparationEvidence(codec).read(customer);
        if (inputs == null || captured.selection().monthlyPriceId() == null
            || !captured.selection().equals(inputs.plan().selections().get(captured.request())))
          throw new IllegalStateException("包装子件缺少已采用月价依据");
        samePrice(ap, captured.customerPrice().multiply(captured.unitPriceMultiplier()), "客户包装子件 P1");
        samePrice(bp, captured.financialPrice().multiply(captured.unitPriceMultiplier()), "财务包装子件 P0");
        copper |= captured.customerPrice().compareTo(captured.financialPrice()) != 0;
      } else if ("RANGE".equals(type)
          && OriginalRangeRuleSnapshot.VERSION.equals(
              a.path("inputs").path("calculatorVersion").asText())) {
        copper |=
            new OriginalRangeCalculationEvidence(json)
                .validatePair(
                    a.path("materialCode").asText(),
                    a.path("inputs").toString(),
                    b.path("inputs").toString(),
                    ap,
                    bp,
                    customerCu,
                    financeCu);
      } else if ("LINKED".equals(type)
          && OriginalLinkedRuleSnapshot.VERSION.equals(
              a.path("inputs").path("calculatorVersion").asText())) {
        copper |= linked(a, b, customerCu, financeCu);
      } else samePrice(ap, bp, "非Cu包装子件单价");
      oaTotal = oaTotal.add(amount(a));
      finTotal = finTotal.add(amount(b));
    }
    samePrice(customer.getUnitPrice(), parentPrice(oaTotal, oa), "客户包装合计");
    samePrice(finance.getUnitPrice(), parentPrice(finTotal, fin), "财务包装合计");
    if (oa.has("monthlyInputs")) {
      samePrice(customer.getAmount(), customer.getUnitPrice().multiply(customer.getQuantity()), "客户包装金额");
      samePrice(finance.getAmount(), finance.getUnitPrice().multiply(finance.getQuantity()), "财务包装金额");
    }
    return copper;
  }

  List<JsonNode> inputs(String serialized) {
    var snapshot = read(serialized);
    if (!VERSION.equals(snapshot.path("calculatorVersion").asText())) return List.of();
    return components(snapshot).values().stream().filter(r -> r.path("inputs").isObject()).toList();
  }

  /** 当前包装底稿从价格准备读取，不能追到可被重算覆盖的月度包装价格表。 */
  JsonNode trace(PricePrepareItem prepared) {
    var snapshot = read(prepared.getInputSnapshotJson());
    if (!VERSION.equals(snapshot.path("calculatorVersion").asText())) return null;
    same(prepared.getMaterialCode(), snapshot.path("materialCode").asText(), "包装料号");
    same(prepared.getBusinessUnitType(), snapshot.path("businessUnitType").asText(), "包装业务单元");
    same(prepared.getPriceOrgCode(), snapshot.path("priceOrgCode").asText(), "包装组织");
    same(prepared.getPeriodMonth(), snapshot.path("periodMonth").asText(), "包装月份");
    validateStructureIdentity(snapshot);
    BigDecimal total = BigDecimal.ZERO;
    for (var component : components(snapshot).values()) {
      if (household(component) != null) continue;
      total = total.add(amount(component));
      String type = component.path("priceType").asText();
      if ("SETTLE_FIXED".equals(type) || "SETTLE_LINKED".equals(type)) {
        var inputs = component.path("inputs");
        var child = new PricePrepareItem();
        child.setMaterialCode(component.path("materialCode").asText());
        child.setBusinessUnitType(prepared.getBusinessUnitType());
        child.setPeriodMonth(prepared.getPeriodMonth());
        child.setPriceOrgCode(prepared.getPriceOrgCode());
        child.setResultRefId(inputs.path("sourceRecordId").longValue());
        child.setUnitPrice(decimal(component, "unitPrice"));
        child.setInputSnapshotJson(inputs.toString());
        new com.sanhua.marketingcost.service.settlement.SettlementCalculationEvidence(json).read(child);
      }
    }
    samePrice(prepared.getUnitPrice(), parentPrice(total, snapshot), "包装单价");
    return snapshot;
  }

  private boolean linked(JsonNode a, JsonNode b, BigDecimal customerCu, BigDecimal financeCu) {
    var x = a.path("inputs");
    var y = b.path("inputs");
    if (x.path("ruleId").asLong() <= 0
        || x.path("ruleRevision").asInt() <= 0
        || !x.path("ruleIdentity").isObject()
        || x.path("effectivePriceId").asLong() <= 0)
      throw new IllegalStateException("包装联动缺少原稿或有效价身份");
    for (String field :
        List.of(
            "calculatorVersion",
            "ruleId",
            "ruleRevision",
            "ruleIdentity",
            "ruleMonth",
            "sourceUploadBatchId",
            "expression",
            "sourceTaxIncluded",
            "factorBindings",
            "effectivePriceId")) same(x.get(field), y.get(field), "包装联动" + field);
    same(x.path("context").path("financial"), y.path("context").path("financial"), "包装联动财务版本");
    same(x.path("context").path("boardInputs"), y.path("context").path("boardInputs"), "包装联动板换条件");
    same(x.path("context").path("months"), y.path("context").path("months"), "包装联动月份");
    try {
      var xc = json.treeToValue(x.path("context"), PriceFactorContext.class);
      var yc = json.treeToValue(y.path("context"), PriceFactorContext.class);
      same(a.path("materialCode").asText(), xc.materialCode(), "包装联动料号");
      same(xc.materialCode(), yc.materialCode(), "包装联动料号");
      if (yc.customerCopperPricePerTon() != null) throw new IllegalStateException("财务包装不能使用客户Cu");
      if (customerCu != null)
        samePrice(
            customerCu,
            xc.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true),
            "客户Cu");
      if (financeCu != null)
        samePrice(
            financeCu,
            yc.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true),
            "财务Cu");
      var bindings = x.path("factorBindings");
      if (!bindings.isObject()
          || !x.path("inputs").isObject()
          || !y.path("inputs").isObject()
          || bindings.size() != x.path("inputs").size()
          || bindings.size() != y.path("inputs").size())
        throw new IllegalStateException("包装联动因素依据不完整");
      boolean copper = false;
      var fields = bindings.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        var binding = field.getValue();
        String factor = binding.path("canonicalKey").asText();
        var unit = FactorPriceUnit.valueOf(binding.path("unit").asText());
        var xv = decimal(x.path("inputs"), field.getKey());
        var yv = decimal(y.path("inputs"), field.getKey());
        samePrice(xv, xc.requiredPrice(factor, unit, true), "客户包装因素");
        samePrice(yv, yc.requiredPrice(factor, unit, true), "财务包装因素");
        if (PriceFactorContext.COPPER.equals(factor)) copper = true;
        else samePrice(xv, yv, "包装非Cu因素");
      }
      samePrice(
          decimal(a, "unitPrice"),
          decimal(x, "priceExcludingTax").setScale(6, RoundingMode.HALF_UP),
          "客户包装联动单价");
      samePrice(
          decimal(b, "unitPrice"),
          decimal(y, "priceExcludingTax").setScale(6, RoundingMode.HALF_UP),
          "财务包装联动单价");
      if (!copper) samePrice(decimal(a, "unitPrice"), decimal(b, "unitPrice"), "非Cu包装联动单价");
      return copper;
    } catch (Exception error) {
      throw new IllegalStateException("包装联动依据不一致：" + error.getMessage(), error);
    }
  }

  private JsonNode read(String value) {
    if (value == null || value.isBlank()) return json.createObjectNode();
    try {
      return json.reader()
          .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readTree(value);
    } catch (Exception error) {
      throw new IllegalStateException("包装底稿损坏", error);
    }
  }

  Map<String, JsonNode> components(JsonNode snapshot) {
    var rows = snapshot.path("components");
    if (!rows.isArray() || rows.isEmpty()) throw new IllegalStateException("包装底稿缺子件");
    Map<String, JsonNode> result = new LinkedHashMap<>();
    for (var row : rows) {
      String key = row.path("snapshotDetailId").asText("");
      if (key.isBlank() || "null".equals(key)) {
        String sourcePath = row.path("sourcePath").asText("");
        String parentPath = snapshot.path("sourcePath").asText("");
        if (snapshot.path("sourceRawHierarchyId").asLong() <= 0 || row.path("sourceHierarchyId").asLong() <= 0
            || sourcePath.isBlank() || parentPath.isBlank())
          throw new IllegalStateException("本次包装子件缺少原始结构身份");
        String child = com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.pathKey(sourcePath);
        String parent = com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.pathKey(parentPath);
        if (child.lastIndexOf('/') <= 0 || !child.substring(0, child.lastIndexOf('/')).equals(parent))
          throw new IllegalStateException("本次包装计算行不是原始父节点的直接子件");
        key = "source:" + row.path("sourceHierarchyId").asLong() + ":" + row.path("sourcePath").asText();
      }
      if (result.putIfAbsent(key, row) != null)
        throw new IllegalStateException("包装子件身份缺失或重复");
    }
    return result;
  }

  private void validateStructureIdentity(JsonNode snapshot) {
    if (snapshot.path("snapshotId").asLong() > 0) return;
    if (snapshot.path("sourceRawHierarchyId").asLong() <= 0 || snapshot.path("sourcePath").asText("").isBlank())
      throw new IllegalStateException("包装底稿缺少保存结构或本次原始父节点身份");
    com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.pathKey(snapshot.path("sourcePath").asText());
  }

  private BigDecimal amount(JsonNode row) {
    var amount = decimal(row, "amount");
    samePrice(amount, componentAmount(row, decimal(row, "unitPrice")), "包装子件金额");
    return amount;
  }

  BigDecimal componentQuantity(JsonNode row) {
    BigDecimal quantity = decimal(row, "quantity");
    return row.hasNonNull("parentBaseQty")
        ? quantity.divide(decimal(row, "parentBaseQty"), 8, RoundingMode.HALF_UP) : quantity;
  }

  BigDecimal componentAmount(JsonNode row, BigDecimal unitPrice) {
    return componentQuantity(row).multiply(unitPrice);
  }

  /** Explicit exclusion is only arithmetic-free when the saved original occurrence is complete. */
  com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Use household(JsonNode row) {
    boolean excluded = PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED.equals(row.path("priceSource").asText());
    if (!excluded && !row.has("householdUse")) return null;
    if (!excluded || !row.path("householdUse").isObject()
        || row.path("sourcePath").asText("").isBlank()
        || row.hasNonNull("unitPrice") || row.hasNonNull("amount") || row.hasNonNull("inputs")
        || row.hasNonNull("priceType"))
      throw new IllegalStateException("包装家用独立计价排除依据不完整");
    var codec = new MonthlyPriceDocumentCodec(json);
    var use = codec.decode(codec.wrap(row.path("householdUse").toString()),
        com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Use.class);
    same(row.path("materialCode").asText(), use.request().family().materialCode(), "包装家用排除料号");
    return use;
  }

  BigDecimal parentPrice(BigDecimal total, JsonNode snapshot) {
    return snapshot.hasNonNull("parentBaseQty") && decimal(snapshot, "parentBaseQty").signum() != 0
        ? total.divide(decimal(snapshot, "parentBaseQty"), 8, RoundingMode.HALF_UP)
        : total;
  }

  private BigDecimal decimal(JsonNode row, String field) {
    var value = row.get(field);
    if (value == null || !value.isNumber()) throw new IllegalStateException("包装底稿缺金额或数量：" + field);
    return value.decimalValue();
  }

  private void samePrice(BigDecimal a, BigDecimal b, String field) {
    if (a == null
        || b == null
        || a.setScale(8, RoundingMode.HALF_UP).compareTo(b.setScale(8, RoundingMode.HALF_UP)) != 0)
      throw new IllegalStateException(field + "不一致");
  }

  private void same(Object a, Object b, String field) {
    if (!Objects.equals(a, b)) throw new IllegalStateException(field + "不一致");
  }
}
