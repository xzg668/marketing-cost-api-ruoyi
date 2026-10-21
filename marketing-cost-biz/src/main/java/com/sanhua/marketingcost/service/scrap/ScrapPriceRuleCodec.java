package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.ScrapPrice;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 导入、编辑和月度沿用共用的规则持久化边界。 */
@Component
public class ScrapPriceRuleCodec {
  private static final TypeReference<Map<String, String>> BINDINGS = new TypeReference<>() {};
  private final ObjectMapper json;

  public ScrapPriceRuleCodec(ObjectMapper json) {
    this.json = json;
  }

  public ScrapPriceRule read(ScrapPrice row) {
    try {
      return new ScrapPriceRule(
          row.getMaterialCode(),
          row.getMaterialName(),
          row.getSpecification(),
          row.getUnit(),
          row.getSupplierCode(),
          row.getSupplierName(),
          row.getPriceType(),
          row.getFixedPrice(),
          row.getSourceFormula(),
          row.getExpression(),
          json.readValue(row.getFactorBindingsJson(), BINDINGS));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("废料" + row.getMaterialCode() + "的因素绑定无法读取", error);
    }
  }

  public void write(ScrapPriceRule rule, ScrapPrice row) {
    row.setMaterialCode(rule.materialCode());
    row.setMaterialName(rule.materialName());
    row.setSpecification(rule.specification());
    row.setUnit(rule.unit());
    row.setCurrency("CNY");
    row.setTaxIncluded(0);
    row.setSupplierCode(rule.supplierCode());
    row.setSupplierName(rule.supplierName());
    row.setPriceType(rule.priceType());
    row.setFixedPrice(rule.fixedPrice());
    row.setSourceFormula(rule.sourceFormula());
    row.setExpression(rule.expression());
    row.setFactorBindingsJson(encode(rule.factorBindings()));
    row.setContentHash(rule.contentHash(json));
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("废料价格证据序列化失败", error);
    }
  }

  public String hash(ScrapPriceRule rule) {
    return rule.contentHash(json);
  }
}
