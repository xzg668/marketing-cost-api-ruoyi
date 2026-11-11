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
    if (inputs == null || !inputs.rootPath().equals(MonthlyBomPricingScope.pathKey(root)))
      throw new IllegalArgumentException("准备底稿不属于本次计算路径");
    if (inputs.separateRule() != null && (!Objects.equals(row.getBusinessUnitType(), inputs.separateRule().businessUnitType())
        || !Objects.equals(row.getTopProductCode(), inputs.separateRule().productCode())))
      throw new IllegalArgumentException("零件成本配置不属于本次成品和业务单元");
    BigDecimal calculationUnitPrice = row.getUnitPrice();
    if (MonthlyPreparationPrice.KIND.equals(row.getResultRefType())) {
      var captured = codec.decode(row.getInputSnapshotJson(), MonthlyPreparationPrice.class);
      price(row, captured, row.getMaterialCode(), row.getUnitPrice(), row.getQuantity(),
          financial, plan, inputs, scenarios);
      // The saved amount used the full price; the unit-price column rounds to eight decimals.
      calculationUnitPrice = (financial ? captured.financialPrice() : captured.customerPrice())
          .multiply(captured.unitPriceMultiplier());
    } else if ("MAKE_PART_PRICE".equals(row.getResultRefType())) {
      var rows = new MakePartCalculationEvidence(json).rows(row);
      if (rows == null) throw new IllegalArgumentException("制造准备缺少保存的计算行");
      for (var component : rows) {
        BigDecimal rawQuantity = com.sanhua.marketingcost.service.MakePartConsumption.rawQuantity(component);
        price(row, codec.decode(component.getRawInputSnapshotJson(), MonthlyPreparationPrice.class),
            component.getChildMaterialNo(), component.getRawUnitPrice(), rawQuantity.multiply(row.getQuantity()),
            financial, plan, inputs, scenarios);
        if (!Boolean.TRUE.equals(component.getNoScrapConfirmed())) {
          var quantity = com.sanhua.marketingcost.service.MakePartConsumption.scrapQuantityKg(component)
              .multiply(row.getQuantity());
          price(row, codec.decode(component.getScrapInputSnapshotJson(), MonthlyPreparationPrice.class),
              component.getScrapCode(), component.getScrapUnitPrice(), quantity,
              financial, plan, inputs, scenarios);
        }
      }
    } else if (row.getResultRefType() != null && row.getResultRefType().startsWith("PACKAGE")) {
      var packageEvidence = new PackageCalculationEvidence(json);
      var snapshot = packageEvidence.trace(row);
      if (snapshot == null) throw new IllegalArgumentException("包装准备缺少保存的计算行");
      for (var component : snapshot.path("components")) {
        BigDecimal quantity = packageEvidence.parentPrice(packageEvidence.componentQuantity(component), snapshot)
            .multiply(row.getQuantity());
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
            financial, plan, inputs, scenarios);
      }
    } else throw new IllegalArgumentException("当前成本准备未接入月价");
    // Monthly adoption locks the applicable unit price. Its recorded consumption
    // is evidence, not a required material list or BOM quantity for another calculation.
    decimal(row.getAmount(), row.getQuantity().multiply(calculationUnitPrice), "准备金额");
  }

  private void price(PricePrepareItem parent, MonthlyPreparationPrice value, String code,
      BigDecimal workingPrice, BigDecimal physicalQuantity, boolean financial, MonthlyAdoptionPlan plan,
      MonthlyPreparationInputs inputs, Map<Long, MonthlyPriceScenarios.Pair> scenarios) {
    if (physicalQuantity.signum() == 0) return;
    var request = value.request();
    if (!request.matchesBomMaterialCode(code)
        || !Objects.equals(parent.getBusinessUnitType(), request.family().businessUnitType())
        || (MonthlyPreparationPrice.KIND.equals(parent.getResultRefType())
            && !Objects.equals(parent.getPriceOrgCode(), request.priceOrgCode()))
        || !Objects.equals(parent.getPeriodMonth(), request.family().month().toString())
        || !value.selection().equals(plan.selections().get(request))
        || !value.selection().equals(inputs.plan().selections().get(request)))
      throw new IllegalArgumentException("准备基础用价身份不一致");
    var pair = scenarios.get(value.selection().monthlyPriceId());
    if (pair == null) throw new IllegalArgumentException("准备基础价缺少保存的双场景依据");
    decimal(value.financialPrice(), pair.financialPrice(), "准备 P0");
    decimal(value.customerPrice(), pair.customerPrice(), "准备 P1");
    decimal(workingPrice, (financial ? pair.financialPrice() : pair.customerPrice())
        .multiply(value.unitPriceMultiplier()), "准备工作单位价格");
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
