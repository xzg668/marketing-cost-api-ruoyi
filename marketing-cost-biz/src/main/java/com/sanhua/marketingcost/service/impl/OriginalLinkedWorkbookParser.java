package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.*;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.Header;
import com.sanhua.marketingcost.formula.workbook.LinkedApprovalTable;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.pricing.BoardStainlessGrade;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/** 既有原稿使用唯一主表；加工费审批原稿按各物料工作表读取，因素区只提供依赖。 */
@Component
public final class OriginalLinkedWorkbookParser {
  private final FactorCanonicalKeyService canonicalKeys;

  public OriginalLinkedWorkbookParser(FactorCanonicalKeyService canonicalKeys) {
    this.canonicalKeys = canonicalKeys;
  }

  /** financialKeys为财务管理的因素目录；本月缺值仍保留绑定，由取价报告缺因素。 */
  public ParsedWorkbook parse(
      byte[] bytes, String fileName, String businessUnit, Set<String> financialKeys) {
    return parseWithCatalog(bytes, fileName, businessUnit, org -> financialKeys);
  }

  public ParsedWorkbook parseWithCatalog(
      byte[] bytes,
      String fileName,
      String businessUnit,
      java.util.function.Function<String, Set<String>> catalog) {
    return parseWithCatalog(bytes, fileName, businessUnit, catalog, new ImportContext(null, Map.of()));
  }

  public record ImportContext(String orgCode, Map<String, String> materialUnits) {
    public ImportContext { materialUnits = Map.copyOf(materialUnits); }
  }

  public ParsedWorkbook parseWithCatalog(
      byte[] bytes, String fileName, String businessUnit,
      java.util.function.Function<String, Set<String>> catalog, ImportContext context) {
    List<Rule> rules = new ArrayList<>();
    List<RowError> errors = new ArrayList<>();
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var layouts = LinkedApprovalTable.layouts(workbook);
      Map<SourceRow, String> factorProblems = new LinkedHashMap<>();
      Map<Address, FactorCell> candidates = factorCells(workbook, factorProblems);
      addApprovalFactors(layouts, candidates);
      Map<String, Bindings> byOrganization = new LinkedHashMap<>();
      for (var layout : layouts) {
        Header main = layout.header();
        for (int index = main.row() + 1; index <= main.sheet().getLastRowNum(); index++) {
          Row row = main.sheet().getRow(index);
          String material = null;
          try {
            material = materialCode(at(row, main, "物料代码"));
            if (material.isBlank()) continue;
            if ("固定".equals(text(at(row, main, "订单类型")))) {
              throw new IllegalArgumentException("联动原稿中包含固定价记录，请通过固定价入口导入；本批不发布任何记录");
            }
            Cell price = at(row, main, "单价");
            if (price == null || price.getCellType() != CellType.FORMULA) {
              throw new IllegalArgumentException("主价格格缺少实际公式，不能用文字说明或缓存价替代");
            }
            var identity =
                new MaterialPriceRuleKey(
                    businessUnit,
                    layout.approval() ? context.orgCode() : text(at(row, main, "组织")),
                    material,
                    text(at(row, main, "供应商代码")),
                    text(at(row, main, "供应商名称")),
                    text(at(row, main, "规格型号")),
                    layout.approval() ? LinkedApprovalTable.unit(row, main, context.materialUnits()) : text(at(row, main, "单位")));
            Bindings binding =
                byOrganization.computeIfAbsent(
                    identity.orgCode(),
                    org ->
                        bindings(
                            workbook, fileName, candidates, catalog.apply(org), "220".equals(org)));
            CompiledFormula compiled =
                binding
                    .compiler()
                    .compile(
                        new Address(
                            main.sheet().getSheetName(), price.getAddress().formatAsString()));
            for (var source : compiled.sources()) {
              var sourceRow =
                  new SourceRow(
                      source.address().sheet(), new CellReference(source.address().cell()).getRow());
              String problem = factorProblems.get(sourceRow);
              if (problem != null)
                throw new IllegalArgumentException(source.address() + "：" + problem);
            }
            Map<String, FactorInput> required = new LinkedHashMap<>();
            for (String variable : compiled.sourceInputs().keySet()) {
              FactorCell factor = binding.factors().get(variable);
              required.put(
                  variable,
                  new FactorInput(
                      factor.canonicalKey(),
                      factor.address(),
                      unit(factor.unit()),
                      factor.unit(),
                      factor.sourceKey(),
                      factor.bindingBasis()));
            }
            if ("220".equals(identity.orgCode()))
              validateBoardGrade(identity, text(at(row, main, "物料名称")), required);
            BigDecimal original =
                ExpressionEvaluator.evaluate(compiled.expression(), compiled.sourceInputs());
            BigDecimal cached = numeric(price);
            if (cached == null
                || original.subtract(cached).abs().compareTo(new BigDecimal("0.00000001")) > 0) {
              throw new IllegalArgumentException("原输入复算与原稿缓存价不一致：复算=" + original + "，原稿=" + cached);
            }
            rules.add(
                new Rule(
                    identity,
                    text(at(row, main, "物料名称")),
                    main.sheet().getSheetName(),
                    index + 1,
                    price.getAddress().formatAsString(),
                    price.getCellFormula(),
                    text(at(row, main, "联动公式")),
                    price.getCellStyle().getDataFormatString(),
                    layout.approval() ? LinkedApprovalTable.taxIncluded(row, main) : taxIncluded(at(row, main, "是否含税")),
                    compiled,
                    Map.copyOf(required),
                    original));
          } catch (RuntimeException error) {
            errors.add(
                new RowError(main.sheet().getSheetName(), index + 1, material, error.getMessage()));
          }
        }
      }
      if (rules.isEmpty() && errors.isEmpty())
        errors.add(new RowError(null, null, null, "未读取到主价格记录"));
    } catch (Exception error) {
      errors.add(new RowError(null, null, null, fileName + "：" + error.getMessage()));
    }
    return new ParsedWorkbook(List.copyOf(rules), List.copyOf(errors));
  }

  /** 只读取主表组织，确保财务目录按原稿实际组织隔离。 */
  public Set<String> organizations(byte[] bytes) { return organizations(bytes, null); }

  public Set<String> organizations(byte[] bytes, String approvalOrgCode) {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      Set<String> result = new LinkedHashSet<>();
      for (var layout : LinkedApprovalTable.layouts(workbook)) {
        if (layout.approval() && (approvalOrgCode == null || approvalOrgCode.isBlank()))
          throw new IllegalArgumentException("审批原稿未列组织，请选择适用组织（商用或板换）");
        Header main = layout.header();
        for (int i = main.row() + 1; i <= main.sheet().getLastRowNum(); i++) {
          Row row = main.sheet().getRow(i);
          Cell material = at(row, main, "物料代码");
          if (!materialCode(material).isBlank())
            result.add(
                com.sanhua.marketingcost.enums.MaterialOrganization.fromPriceOrgCode(
                        layout.approval() ? approvalOrgCode : text(at(row, main, "组织")))
                    .getPriceOrgCode());
        }
      }
      if (result.isEmpty()) throw new IllegalArgumentException("原稿没有联动物料主价记录");
      return java.util.Collections.unmodifiableSet(result);
    } catch (Exception error) {
      throw new IllegalArgumentException("读取原稿组织失败：" + error.getMessage(), error);
    }
  }

  private Bindings bindings(
      Workbook workbook,
      String fileName,
      Map<Address, FactorCell> candidates,
      Set<String> financialKeys,
      boolean board) {
    Set<String> publicKeys = new LinkedHashSet<>(financialKeys);
    publicKeys.add("AVG|1#CU");
    Map<Address, String> variables = new LinkedHashMap<>();
    Map<String, FactorCell> bindings = new LinkedHashMap<>();
    for (var entry : candidates.entrySet()) {
      FactorCell factor = entry.getValue();
      if (!publicKeys.contains(factor.canonicalKey())
          && !(board && BoardStainlessGrade.fromFactorKey(factor.canonicalKey()) != null)
          && !"APPROVAL_PUBLIC_FACTOR".equals(factor.bindingBasis())) continue;
      // 每个源格独立保留原输入；多个Cu源格在运行时指向同一个财务身份。
      String variable = "factor_" + variables.size();
      variables.put(entry.getKey(), variable);
      bindings.put(variable, factor);
    }
    var compiler = new WorkbookFormulaCompiler(workbook, fileName, variables);
    return new Bindings(compiler, bindings);
  }

  private record Bindings(WorkbookFormulaCompiler compiler, Map<String, FactorCell> factors) {}

  private void validateBoardGrade(
      MaterialPriceRuleKey identity, String materialName, Map<String, FactorInput> factors) {
    var specificationGrade = BoardStainlessGrade.fromSpecification(identity.specification());
    Set<BoardStainlessGrade> referenced = new LinkedHashSet<>();
    for (var input : factors.values()) {
      var grade = BoardStainlessGrade.fromFactorKey(input.canonicalKey());
      if (grade != null) referenced.add(grade);
    }
    if (specificationGrade == null && referenced.isEmpty() && !materialName.contains("不锈钢")) return;
    if (specificationGrade == null)
      throw new IllegalArgumentException("板换不锈钢规格缺少明确材质，实际引用=" + referenced);
    if (referenced.size() != 1 || !referenced.contains(specificationGrade))
      throw new IllegalArgumentException(
          "板换规格材质=" + specificationGrade + "与实际因素引用不一致：" + referenced);
  }

  private Map<Address, FactorCell> factorCells(Workbook workbook, Map<SourceRow, String> problems) {
    Map<Address, FactorCell> factors = new LinkedHashMap<>();
    for (Sheet sheet : workbook) {
      Header header = header(sheet, Set.of("简称", "取价来源", "价格"));
      if (header != null) {
        for (int i = header.row() + 1; i <= sheet.getLastRowNum(); i++) {
          Row row = sheet.getRow(i);
          addFactor(
              factors,
              new Address(
                  sheet.getSheetName(),
                  new CellReference(i, header.columns().get("价格")).formatAsString()),
              text(at(row, header, "取价来源")),
              text(at(row, header, "简称")),
              text(at(row, header, "单位")));
        }
      }
      for (Row row : sheet) {
        List<Cell> cells = new ArrayList<>();
        for (Cell cell : row) if (!text(cell).isBlank()) cells.add(cell);
        // 无表头因素行的实际结构：序号、名称、简称、价源、数值、单位。
        if (cells.size() >= 5
            && numeric(cells.get(0)) != null
            && Set.of("平均价", "月平均价", "月均价", "现货价", "采购价", "出厂价").contains(text(cells.get(3)))
            && !text(cells.get(2)).isBlank()) {
          addFactor(
              factors,
              new Address(
                  sheet.getSheetName(),
                  new CellReference(row.getRowNum(), cells.get(3).getColumnIndex() + 1)
                      .formatAsString()),
              text(cells.get(3)),
              text(cells.get(2)),
              text(row.getCell(cells.get(3).getColumnIndex() + 2)));
        }
        // 漆包线成本表的独立铜行情行：明确电解铜/铜均价说明，同行唯一行情数值。
        // 只识别该实际布局，不按供应商、文件名、料号或固定格址识别。
        boolean copperRow =
            cells.stream()
                .map(com.sanhua.marketingcost.formula.workbook.OriginalPriceTable::text)
                .anyMatch(
                    value ->
                        value.equals("1#电解铜")
                            || value.startsWith("漆包线：铜价")
                            || value.startsWith("铜均价按入库月"));
        if (copperRow) {
          List<Cell> numbers = cells.stream().filter(cell -> numeric(cell) != null).toList();
          if (numbers.size() == 1)
            addFactor(
                factors,
                new Address(sheet.getSheetName(), numbers.getFirst().getAddress().formatAsString()),
                "平均价",
                "Cu",
                "元/公斤");
          else
            problems.put(
                new SourceRow(sheet.getSheetName(), row.getRowNum()),
                "铜行情行必须具有唯一明确的价格，实际候选数=" + numbers.size());
        }
      }
    }
    return factors;
  }

  private void addFactor(
      Map<Address, FactorCell> factors, Address address, String source, String name, String unit) {
    if (name.isBlank() || source.isBlank()) return;
    String sourceKey = canonicalKeys.build(source, name);
    String financialKey = sourceKey;
    String basis = "SAME_CANONICAL_FACTOR";
    // 已确认原稿中的成品磁粉身份：财务表NEOLET-6HR标在原料行，成品值来自其氧化钕派生行。
    // 保留两边身份，不把783.864原料价误用于成品，也不写死364.187结果。
    if ("联动价".equals(source) && "NEOLET-6HR".equalsIgnoreCase(name)) {
      financialKey = canonicalKeys.build("平均价", "氧化钕");
      basis = "CONFIRMED_NEOLET_FINISHED_POWDER";
    }
    var factor = new FactorCell(address, financialKey, unit, sourceKey, basis);
    FactorCell previous = factors.putIfAbsent(address, factor);
    if (previous != null && !previous.equals(factor))
      throw new IllegalArgumentException("因素格身份冲突：" + address);
  }

  private FactorPriceUnit unit(String sourceUnit) {
    return switch (sourceUnit.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "")) {
      case "公斤", "千克", "KG", "元/公斤", "元/千克", "元/KG", "万元/吨" -> FactorPriceUnit.CNY_KG;
      case "吨", "T", "元/吨", "元/T" -> FactorPriceUnit.CNY_TON;
      case "克", "G", "元/克", "元/G" -> FactorPriceUnit.CNY_G;
      default -> throw new IllegalArgumentException("因素单位无法识别：" + sourceUnit);
    };
  }

  /** 只在明确的加工费审批物料区右侧识别行情块，行加工费始终沿实际公式读取。 */
  private void addApprovalFactors(
      List<LinkedApprovalTable.Layout> layouts, Map<Address, FactorCell> factors) {
    for (var layout : layouts) {
      if (!layout.approval()) continue;
      Header main = layout.header();
      for (Row row : main.sheet()) {
        for (Cell label : row) {
          if (label.getColumnIndex() <= main.columns().get("单价")) continue;
          String name = text(label).toUpperCase(java.util.Locale.ROOT).replace(" ", "");
          String key = switch (name) {
            case "CU", "1#CU" -> "AVG|1#CU";
            case "SN", "1#SN" -> "AVG|1#SN";
            case "黄金AU9999" -> "AVG|黄金AU9999";
            default -> null;
          };
          if (key == null) continue;
          var address = new Address(main.sheet().getSheetName(),
              new CellReference(row.getRowNum(), label.getColumnIndex() + 1).formatAsString());
          String unit = key.equals("AVG|黄金AU9999") ? "元/克" : "元/公斤";
          var factor = new FactorCell(address, key, unit, key, "APPROVAL_PUBLIC_FACTOR");
          var previous = factors.putIfAbsent(address, factor);
          if (previous != null && !previous.canonicalKey().equals(key))
            throw new IllegalArgumentException("审批表因素格身份冲突：" + address);
        }
      }
    }
  }

  private record FactorCell(
      Address address, String canonicalKey, String unit, String sourceKey, String bindingBasis) {}

  private record SourceRow(String sheet, int row) {}

  public record FactorInput(
      String canonicalKey,
      Address address,
      FactorPriceUnit unit,
      String sourceUnit,
      String sourceKey,
      String bindingBasis) {}

  public record Rule(
      MaterialPriceRuleKey identity,
      String materialName,
      String sheet,
      int row,
      String cell,
      String sourceFormula,
      // 原稿文字说明仅用于展示，计算始终使用单价格的实际公式。
      String sourceFormulaText,
      String numberFormat,
      boolean taxIncluded,
      CompiledFormula compiled,
      Map<String, FactorInput> factors,
      BigDecimal originalPrice) {}

  public record RowError(String sheet, Integer row, String materialCode, String message) {}

  public record ParsedWorkbook(List<Rule> rules, List<RowError> errors) {}
}
