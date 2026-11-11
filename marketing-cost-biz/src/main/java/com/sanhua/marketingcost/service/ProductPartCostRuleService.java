package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.costing.ProductPartCostRule;
import com.sanhua.marketingcost.service.costing.ProductPartCostRuleStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductPartCostRuleService {
  public record Input(String productCode, String productName, String productSpec, String productModel,
      String partCode, String partName, String partModel, String shape, BigDecimal manufactureRate, Long revision) {}
  public record Query(String productCode, String productName, String productModel,
      String partCode, String partName, String partModel) {}
  public record ImportRow(int row, Input input) {}
  public record ImportError(int row, String message) {}
  public record ImportResult(int imported, List<ImportError> errors) {}
  private final ProductPartCostRuleStore store;

  public ProductPartCostRuleService(ProductPartCostRuleStore store) { this.store = store; }

  public List<ProductPartCostRule> list(Query query) {
    return store.list(unit()).stream().filter(rule -> contains(rule.productCode(), query.productCode())
        && contains(rule.productName(), query.productName()) && contains(rule.productModel(), query.productModel())
        && contains(rule.partCode(), query.partCode()) && contains(rule.partName(), query.partName())
        && contains(rule.partModel(), query.partModel())).toList();
  }

  public ProductPartCostRule get(long id) { return store.get(unit(), id); }

  @Transactional
  public ProductPartCostRule create(Input input) { return unique(() -> store.insert(normalize(0, input, false))); }

  @Transactional
  public ProductPartCostRule update(long id, Input input) {
    get(id);
    return unique(() -> store.update(normalize(id, input, true)));
  }

  @Transactional
  public void delete(long id, long revision) { store.delete(unit(), id, revision); }

  /** Validate the complete file first; a concurrent duplicate also rolls back the entire import. */
  @Transactional
  public ImportResult importNew(List<ImportRow> rows) {
    if (rows.isEmpty()) return new ImportResult(0, List.of(new ImportError(0, "请填写至少一行配置")));
    String unit = unit();
    Set<List<String>> existing = new HashSet<>(), seen = new HashSet<>();
    store.list(unit).forEach(rule -> existing.add(importKey(rule)));
    var errors = new ArrayList<ImportError>();
    var validated = new ArrayList<ProductPartCostRule>();
    for (var row : rows) {
      try {
        var value = normalize(0, row.input(), false);
        var key = importKey(value);
        if (!seen.add(key)) throw new IllegalArgumentException("文件内该成品的零件配置重复");
        if (existing.contains(key)) throw new IllegalArgumentException("该成品的零件配置已存在，请在列表中编辑原记录");
        validated.add(value);
      } catch (IllegalArgumentException invalid) { errors.add(new ImportError(row.row(), invalid.getMessage())); }
    }
    if (!errors.isEmpty()) return new ImportResult(0, List.copyOf(errors));
    for (var value : validated) unique(() -> store.insert(value));
    return new ImportResult(validated.size(), List.of());
  }

  private static List<String> importKey(ProductPartCostRule rule) {
    return List.of(rule.productCode(), rule.partCode() != null ? "CODE" : "MODEL",
        rule.partCode() != null ? rule.partCode() : rule.partModel());
  }

  private ProductPartCostRule normalize(long id, Input input, boolean editing) {
    if (input == null) throw new IllegalArgumentException("配置内容不能为空");
    String product = text(input.productCode(), "成品料号", 80);
    String code = text(input.partCode(), "零件料号", 80);
    String model = text(input.partModel(), "零件型号", 255);
    if (product == null) throw new IllegalArgumentException("成品料号必填");
    if (code == null && model == null) throw new IllegalArgumentException("零件料号、零件型号至少填写一项");
    if (editing && (input.revision() == null || input.revision() <= 0))
      throw new IllegalArgumentException("缺少配置版本，请刷新后重试");
    if (input.manufactureRate() != null && input.manufactureRate().stripTrailingZeros().scale() > 12)
      throw new IllegalArgumentException("制造费用率最多支持12位小数");
    return new ProductPartCostRule(id, editing ? input.revision() : 1, unit(), product,
        text(input.productName(), "成品名称", 255), text(input.productSpec(), "成品规格", 255),
        text(input.productModel(), "成品型号", 255), code, text(input.partName(), "零件名称", 255),
        model, input.shape(), input.manufactureRate());
  }

  private static String unit() {
    String unit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!List.of("COMMERCIAL", "HOUSEHOLD").contains(unit == null ? "" : unit))
      throw new IllegalArgumentException("缺少当前业务单元");
    return unit;
  }

  private static String text(String value, String label, int max) {
    if (value == null || value.isBlank()) return null;
    value = value.trim();
    if (value.length() > max) throw new IllegalArgumentException(label + "不能超过" + max + "个字符");
    return value;
  }

  private static boolean contains(String value, String filter) {
    return filter == null || filter.isBlank() || value != null && value.contains(filter.trim());
  }

  private static ProductPartCostRule unique(java.util.function.Supplier<ProductPartCostRule> operation) {
    try { return operation.get(); }
    catch (org.springframework.dao.DuplicateKeyException duplicate) {
      throw new IllegalArgumentException("该成品的零件料号或型号配置已存在，请修改原记录");
    }
  }
}
