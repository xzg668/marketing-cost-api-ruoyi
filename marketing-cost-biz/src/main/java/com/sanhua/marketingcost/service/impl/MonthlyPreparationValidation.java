package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Rechecks saved preparation evidence with the existing MAKE/PACK calculators. */
public final class MonthlyPreparationValidation {
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPreparationEvidence evidence;

  public MonthlyPreparationValidation(ObjectMapper json, MonthlyPriceDocumentCodec codec) {
    this.json = json; this.codec = codec; this.evidence = new MonthlyPreparationEvidence(codec);
  }

  public void validate(PricePrepareItem row, boolean financial, MonthlyAdoptionPlan plan,
      Map<Long, MonthlyPriceScenarios.Pair> scenarios, String root) {
    var inputs = evidence.read(row);
    if (inputs == null || !inputs.equals(MonthlyPreparationInputs.capture(plan, root)))
      throw new IllegalArgumentException("准备底稿与本次月价范围不一致");
    var expected = new HashMap<MonthlyPriceRequest, BigDecimal>();
    if (MonthlyPreparationPrice.KIND.equals(row.getResultRefType())) {
      var captured = codec.decode(row.getInputSnapshotJson(), MonthlyPreparationPrice.class);
      price(row, captured, row.getMaterialCode(), row.getUnitPrice(), row.getQuantity(),
          financial, plan, scenarios, expected);
    } else if ("MAKE_PART_PRICE".equals(row.getResultRefType())) {
      var rows = new MakePartCalculationEvidence(json).rows(row);
      if (rows == null) throw new IllegalArgumentException("制造准备缺少保存的计算行");
      for (var component : rows) {
        BigDecimal rawQuantity = MakePartProcessTypePolicy.PROCESS_TYPE_RAW.equals(component.getItemProcessType())
            ? component.getGrossWeightG().movePointLeft(3) : BigDecimal.ONE;
        price(row, codec.decode(component.getRawInputSnapshotJson(), MonthlyPreparationPrice.class),
            component.getChildMaterialNo(), component.getRawUnitPrice(), rawQuantity.multiply(row.getQuantity()),
            financial, plan, scenarios, expected);
        if (!Boolean.TRUE.equals(component.getNoScrapConfirmed())) {
          var quantity = component.getGrossWeightG().subtract(component.getNetWeightG()).movePointLeft(3)
              .multiply(row.getQuantity());
          price(row, codec.decode(component.getScrapInputSnapshotJson(), MonthlyPreparationPrice.class),
              component.getScrapCode(), component.getScrapUnitPrice(), quantity,
              financial, plan, scenarios, expected);
        }
      }
    } else if (row.getResultRefType() != null && row.getResultRefType().startsWith("PACKAGE")) {
      var packageEvidence = new PackageCalculationEvidence(json);
      var snapshot = packageEvidence.trace(row);
      if (snapshot == null) throw new IllegalArgumentException("包装准备缺少保存的计算行");
      for (var component : snapshot.path("components")) {
        BigDecimal quantity = number(component, "quantity");
        if (component.hasNonNull("parentBaseQty"))
          quantity = quantity.divide(number(component, "parentBaseQty"), 8, RoundingMode.HALF_UP);
        if (snapshot.hasNonNull("parentBaseQty") && number(snapshot, "parentBaseQty").signum() != 0)
          quantity = quantity.divide(number(snapshot, "parentBaseQty"), 8, RoundingMode.HALF_UP);
        quantity = quantity.multiply(row.getQuantity());
        var excluded = packageEvidence.household(component);
        if (excluded != null) {
          var selected = plan.selections().get(excluded.request());
          if (!plan.uses().contains(excluded) || selected == null
              || selected.fact().type() != MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY
              || !MonthlyBomPricingScope.pathKey(excluded.path()).startsWith(
                  MonthlyBomPricingScope.pathKey(root) + "/"))
            throw new IllegalArgumentException("包装排除子件没有本次独立家用采用依据");
          decimal(quantity.abs(), excluded.cumulativeQuantity(), "包装排除的原始家用用量");
          continue;
        }
        if (quantity.signum() == 0) continue;
        if (!MonthlyPreparationPrice.KIND.equals(component.path("priceSource").asText()))
          throw new IllegalArgumentException("包装基础单价未接入月价");
        price(row, codec.decode(component.path("inputs").toString(), MonthlyPreparationPrice.class),
            component.path("materialCode").asText(), number(component, "unitPrice"), quantity,
            financial, plan, scenarios, expected);
      }
    } else throw new IllegalArgumentException("当前成本准备未接入月价");
    var actual = new HashMap<MonthlyPriceRequest, BigDecimal>();
    inputs.uses().forEach(use -> actual.merge(use.request(), use.cumulativeQuantity(), BigDecimal::add));
    if (!actual.keySet().equals(expected.keySet())) throw new IllegalArgumentException("准备实际用价范围不一致");
    expected.forEach((request, quantity) -> {
      if (actual.get(request).compareTo(quantity) != 0) throw new IllegalArgumentException("准备实际用量与保存依据不一致");
    });
    decimal(row.getAmount(), row.getQuantity().multiply(row.getUnitPrice()), "准备金额");
  }

  private void price(PricePrepareItem parent, MonthlyPreparationPrice value, String code,
      BigDecimal workingPrice, BigDecimal physicalQuantity, boolean financial, MonthlyAdoptionPlan plan,
      Map<Long, MonthlyPriceScenarios.Pair> scenarios, Map<MonthlyPriceRequest, BigDecimal> uses) {
    if (physicalQuantity.signum() == 0) return;
    var request = value.request();
    if (!Objects.equals(code, request.family().materialCode())
        || !Objects.equals(parent.getBusinessUnitType(), request.family().businessUnitType())
        || !Objects.equals(parent.getPriceOrgCode(), request.priceOrgCode())
        || !Objects.equals(parent.getPeriodMonth(), request.family().month().toString())
        || !value.selection().equals(plan.selections().get(request)))
      throw new IllegalArgumentException("准备基础用价身份不一致");
    var pair = scenarios.get(value.selection().monthlyPriceId());
    if (pair == null) throw new IllegalArgumentException("准备基础价缺少保存的双场景依据");
    decimal(value.financialPrice(), pair.financialPrice(), "准备 P0");
    decimal(value.customerPrice(), pair.customerPrice(), "准备 P1");
    decimal(workingPrice, (financial ? pair.financialPrice() : pair.customerPrice())
        .multiply(value.unitPriceMultiplier()), "准备工作单位价格");
    uses.merge(request, physicalQuantity.abs().multiply(value.unitPriceMultiplier()), BigDecimal::add);
  }

  private static BigDecimal number(JsonNode node, String field) {
    if (!node.path(field).isNumber()) throw new IllegalArgumentException("准备计算行缺少数值：" + field);
    return node.path(field).decimalValue();
  }

  private static void decimal(BigDecimal actual, BigDecimal expected, String field) {
    if (actual == null || expected == null || actual.setScale(8, RoundingMode.HALF_UP)
        .compareTo(expected.setScale(8, RoundingMode.HALF_UP)) != 0)
      throw new IllegalArgumentException(field + "与保存依据不一致");
  }
}
