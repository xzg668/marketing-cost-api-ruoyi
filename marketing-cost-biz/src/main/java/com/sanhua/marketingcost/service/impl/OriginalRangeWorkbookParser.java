package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.*;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

/** 读取完整原稿。主表定义业务身份，矩阵提供真实边界、税价列与原始公式。 */
@Component
public final class OriginalRangeWorkbookParser {
  private static final Pattern CLOSED =
      Pattern.compile(
          "^(?:CU:)?([0-9]+(?:\\.[0-9]+)?)(?:元/(吨|T|KG|公斤|克|G))?[-—–~～]+"
              + "([0-9]+(?:\\.[0-9]+)?)(?:元/(吨|T|KG|公斤|克|G))?$");
  private static final Pattern INEQUALITY =
      Pattern.compile(
          "^([0-9]+(?:\\.[0-9]+)?)元/(KG|公斤|吨|T|克|G)(≤|<)铜价(≤|<)" + "([0-9]+(?:\\.[0-9]+)?)元/\\2$");
  private static final BigDecimal TOLERANCE = new BigDecimal("0.00000001");

  public ParsedWorkbook parse(byte[] bytes, String fileName, String businessUnit) {
    List<Rule> rules = new ArrayList<>();
    List<RowError> errors = new ArrayList<>();
    try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var main = mainSheet(book);
      List<MainRow> mainRows = new ArrayList<>();
      for (int index = main.row() + 1; index <= main.sheet().getLastRowNum(); index++) {
        Row row = main.sheet().getRow(index);
        String material = null;
        try {
          material = materialCode(at(row, main, "物料代码"));
          if (material.isBlank()) continue;
          var identity =
              new MaterialPriceRuleKey(
                  businessUnit,
                  text(at(row, main, "组织")),
                  material,
                  text(at(row, main, "供应商代码")),
                  text(at(row, main, "供应商名称")),
                  text(at(row, main, "规格型号")),
                  text(at(row, main, "单位")));
          mainRows.add(
              new MainRow(
                  identity,
                  text(at(row, main, "物料名称")),
                  main.sheet().getSheetName(),
                  index + 1,
                  source(at(row, main, "单价")),
                  taxIncluded(at(row, main, "是否含税"))));
        } catch (RuntimeException error) {
          errors.add(
              new RowError(main.sheet().getSheetName(), index + 1, material, error.getMessage()));
        }
      }
      List<MatrixRow> matrices = matrices(book, main.sheet());
      var materialMatrices = new java.util.HashMap<String, List<MatrixRow>>();
      var specificationMatrices = new java.util.HashMap<String, List<MatrixRow>>();
      for (var matrix : matrices) {
        boolean byMaterial = matrix.block().materialColumn() >= 0;
        String key =
            byMaterial
                ? materialCode(cell(matrix, matrix.block().materialColumn()))
                : normalize(text(cell(matrix, matrix.block().specColumn())));
        (byMaterial ? materialMatrices : specificationMatrices)
            .computeIfAbsent(key, ignored -> new ArrayList<>())
            .add(matrix);
      }
      var mainsByMaterial = new java.util.HashMap<String, List<MainRow>>();
      for (var row : mainRows)
        mainsByMaterial
            .computeIfAbsent(row.identity().materialCode(), ignored -> new ArrayList<>())
            .add(row);
      // 文件内说明只读取一次；每个料号自己的档差仍单独保留，跨文件不共享缓存。
      var notesBySheet = new java.util.HashMap<Sheet, List<CellSource>>();
      var compiler = new WorkbookFormulaCompiler(book, fileName, Map.of());
      for (var row : mainRows) {
        try {
          var candidates =
              new ArrayList<>(
                  materialMatrices.getOrDefault(row.identity().materialCode(), List.of()));
          candidates.addAll(
              specificationMatrices.getOrDefault(
                  normalize(row.identity().specification()), List.of()));
          MatrixRow matrix =
              match(row, mainsByMaterial.get(row.identity().materialCode()), candidates);
          List<Band> bands = new ArrayList<>();
          for (var column : matrix.block().columns()) {
            int priceColumn = row.taxIncluded() ? column.includingTax() : column.excludingTax();
            if (priceColumn < 0) throw new IllegalArgumentException("矩阵缺少主表指定的税价列");
            Cell cell = matrix.row().getCell(priceColumn);
            var address =
                new Address(
                    matrix.block().sheet().getSheetName(),
                    new org.apache.poi.ss.util.CellReference(matrix.row().getRowNum(), priceColumn)
                        .formatAsString());
            CompiledFormula compiled = null;
            BigDecimal price = null;
            String problem = null;
            try {
              if (cell == null || cell.getCellType() == CellType.BLANK || text(cell).isBlank())
                throw new IllegalArgumentException("区间价格格为空或错误，不能按0取价");
              compiled = compiler.compile(address);
              price = ExpressionEvaluator.evaluate(compiled.expression(), Map.of());
              BigDecimal cached = numeric(cell);
              if (cached == null || price.subtract(cached).abs().compareTo(TOLERANCE) > 0)
                throw new IllegalArgumentException("区间实际公式与原稿缓存价不一致");
              if (price.signum() < 0) throw new IllegalArgumentException("区间单价不能为负数");
            } catch (RuntimeException error) {
              problem = error.getMessage();
              price = null;
            }
            // 错误档保留边界和源格；只有选中它时阻断取价，不伪造0或悄悄删除该档。
            bands.add(
                new Band(
                    column.bounds(),
                    column.header(),
                    address,
                    source(cell),
                    compiled,
                    price,
                    problem));
          }
          String basis =
              matrix.block().materialColumn() < 0
                  ? "SPECIFICATION_AND_ROW_SUPPLIER"
                  : matrix.block().supplierColumn() >= 0
                      ? "MATERIAL_AND_ROW_SUPPLIER"
                      : supplierMatches(
                              row.identity().supplierName(), matrix.block().supplierLabel())
                          ? "MATERIAL_AND_BLOCK_SUPPLIER"
                          : "UNIQUE_MAIN_MATERIAL_SUPPLIER";
          rules.add(
              new Rule(
                  row.identity(),
                  row.materialName(),
                  row.sheet(),
                  row.row(),
                  row.priceSource(),
                  row.taxIncluded(),
                  matrix.block().factorKey(),
                  matrix.block().factorUnit(),
                  matrix.block().sheet().getSheetName(),
                  matrix.row().getRowNum() + 1,
                  text(cell(matrix, matrix.block().specColumn())),
                  matrix.block().supplierLabel(),
                  basis,
                  List.copyOf(bands),
                  extrapolationSources(
                      matrix,
                      notesBySheet.computeIfAbsent(
                          matrix.block().sheet(), this::extrapolationNotes))));
        } catch (RuntimeException error) {
          errors.add(
              new RowError(
                  row.sheet(), row.row(), row.identity().materialCode(), error.getMessage()));
        }
      }
      if (mainRows.isEmpty() && errors.isEmpty())
        errors.add(new RowError(main.sheet().getSheetName(), null, null, "未读取到主价格记录"));
    } catch (Exception error) {
      errors.add(new RowError(null, null, null, fileName + "：" + error.getMessage()));
    }
    return new ParsedWorkbook(List.copyOf(rules), List.copyOf(errors));
  }

  private List<MatrixRow> matrices(Workbook book, Sheet main) {
    List<MatrixRow> result = new ArrayList<>();
    for (Sheet sheet : book) {
      if (sheet == main) continue;
      List<Block> blocks = new ArrayList<>();
      for (Row row : sheet) {
        int material = column(row, "U9代码", "料号", "物料代码");
        int spec = column(row, "图号", "规格", "规格图号", "规格型号");
        int supplier = column(row, "供方", "供应商名称", "供应商");
        if (spec < 0 || (material < 0 && supplier < 0)) continue;
        List<BandColumn> columns = new ArrayList<>();
        FactorPriceUnit factorUnit = null;
        Row tax = sheet.getRow(row.getRowNum() + 1);
        for (Cell header : row) {
          Bounds bounds = bounds(text(header));
          if (bounds == null) {
            String priceLabel =
                normalize(text(tax == null ? null : tax.getCell(header.getColumnIndex())));
            if (priceLabel.contains("不含税") || priceLabel.contains("未税"))
              throw new IllegalArgumentException(
                  sheet.getSheetName() + "!" + header.getAddress() + "：无法识别原始区间边界：" + text(header));
            continue;
          }
          FactorPriceUnit unit = headerUnit(text(header));
          if (factorUnit != null && factorUnit != unit)
            throw new IllegalArgumentException(sheet.getSheetName() + "：同矩阵因素单位不一致");
          factorUnit = unit;
          int excluded = -1;
          int included = -1;
          for (int c = header.getColumnIndex(); c <= header.getColumnIndex() + 1; c++) {
            String label = normalize(text(tax == null ? null : tax.getCell(c)));
            if (label.contains("不含税") || label.contains("未税")) excluded = c;
            else if (label.contains("含税") || label.contains("含13%税")) included = c;
          }
          columns.add(new BandColumn(bounds, source(header), excluded, included));
        }
        if (columns.isEmpty()) continue;
        StringBuilder title = new StringBuilder();
        for (int r = Math.max(0, row.getRowNum() - 2); r < row.getRowNum(); r++) {
          Row preceding = sheet.getRow(r);
          if (preceding != null) for (Cell c : preceding) title.append(text(c));
        }
        String factorKey;
        if (title.toString().contains("黄金")
            && title.toString().toUpperCase(Locale.ROOT).contains("AU9999")) {
          factorKey = "AVG|黄金AU9999";
          if (factorUnit != FactorPriceUnit.CNY_G)
            throw new IllegalArgumentException("黄金区间单位必须为元/克");
        } else if (title.toString().contains("铜价")) {
          factorKey = "AVG|1#CU";
        } else throw new IllegalArgumentException(sheet.getSheetName() + "：矩阵未明确行情因素身份");
        String supplierLabel = "";
        int last =
            columns.stream()
                .mapToInt(c -> Math.max(c.includingTax(), c.excludingTax()))
                .max()
                .orElse(-1);
        if (tax != null && supplier < 0)
          for (Cell c : tax) {
            if (c.getColumnIndex() > last && !text(c).isBlank()) {
              if (!supplierLabel.isEmpty()) throw new IllegalArgumentException("矩阵供应商说明不唯一");
              supplierLabel = text(c);
            }
          }
        blocks.add(
            new Block(
                sheet,
                row.getRowNum(),
                material,
                spec,
                supplier,
                supplierLabel,
                factorKey,
                factorUnit,
                List.copyOf(columns)));
      }
      for (int i = 0; i < blocks.size(); i++) {
        Block block = blocks.get(i);
        int end = i + 1 < blocks.size() ? blocks.get(i + 1).headerRow() - 1 : sheet.getLastRowNum();
        for (int r = block.headerRow() + 2; r <= end; r++) {
          Row row = sheet.getRow(r);
          if (row == null || text(row.getCell(block.specColumn())).isBlank()) continue;
          if (block.materialColumn() >= 0
              && materialCode(row.getCell(block.materialColumn())).isBlank()) continue;
          if (block.materialColumn() < 0 && text(row.getCell(block.supplierColumn())).isBlank())
            continue;
          result.add(new MatrixRow(block, row));
        }
      }
    }
    return result;
  }

  private MatrixRow match(MainRow main, List<MainRow> mains, List<MatrixRow> matrices) {
    var identity = main.identity();
    List<MatrixRow> candidates =
        matrices.stream()
            .filter(
                m -> {
                  var block = m.block();
                  if (block.materialColumn() >= 0) {
                    if (!identity
                        .materialCode()
                        .equals(materialCode(cell(m, block.materialColumn())))) return false;
                  } else if (!normalize(identity.specification())
                      .equals(normalize(text(cell(m, block.specColumn()))))) return false;
                  // 行上有明确供应商时，绝不以唯一料号为由忽略它。
                  return block.supplierColumn() < 0
                      || supplierMatches(
                          identity.supplierName(), text(cell(m, block.supplierColumn())));
                })
            .toList();
    if (candidates.size() > 1) {
      List<MatrixRow> supplier =
          candidates.stream()
              .filter(
                  m ->
                      m.block().supplierColumn() >= 0
                          || supplierMatches(identity.supplierName(), m.block().supplierLabel()))
              .toList();
      if (!supplier.isEmpty()) candidates = supplier;
    }
    if (candidates.size() > 1) {
      List<MatrixRow> spec =
          candidates.stream()
              .filter(
                  m ->
                      normalize(identity.specification())
                          .equals(normalize(text(cell(m, m.block().specColumn())))))
              .toList();
      if (!spec.isEmpty()) candidates = spec;
    }
    if (candidates.size() != 1)
      throw new IllegalArgumentException(
          "主表与区间矩阵未唯一匹配：供应商="
              + identity.supplierName()
              + "，规格="
              + identity.specification()
              + "，候选数="
              + candidates.size());
    MatrixRow selected = candidates.getFirst();
    if (selected.block().supplierColumn() < 0
        && !supplierMatches(identity.supplierName(), selected.block().supplierLabel())) {
      long suppliers =
          mains.stream()
              .map(MainRow::identity)
              .filter(key -> key.materialCode().equals(identity.materialCode()))
              .map(key -> key.supplierCode().isBlank() ? key.supplierName() : key.supplierCode())
              .distinct()
              .count();
      if (suppliers != 1) throw new IllegalArgumentException("矩阵供应商简称未匹配，主表同料号存在多个供应商");
    }
    return selected;
  }

  /** 这里只处理原稿局部简称关联，不改变发布后的完整供应商业务身份。 */
  static boolean supplierMatches(String full, String source) {
    String a = supplierName(full);
    String b = supplierName(source);
    return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
  }

  private static String supplierName(String value) {
    return normalize(value)
        .replaceAll("[\\p{Punct}（）]", "")
        .replace("股份有限公司", "")
        .replace("有限责任公司", "")
        .replace("有限公司", "")
        .replace("省", "")
        .replace("市", "")
        .replace("实业", "")
        .replace("汽配", "");
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .replaceAll("\\s+", "")
        .toUpperCase(Locale.ROOT);
  }

  static Bounds bounds(String raw) {
    String value = normalize(raw);
    // 原稿存在“45001元/Tg--50000元/T”。仅在另一端明确为吨时识别该笔误，
    // 原始表头仍完整保存在 Band.header 中；其他未知单位不能静默忽略。
    if (value.matches("[0-9]+(?:\\.[0-9]+)?元/TG[-—–~～]+[0-9]+(?:\\.[0-9]+)?元/T"))
      value = value.replace("元/TG", "元/T");
    var interval = INEQUALITY.matcher(value);
    if (interval.matches())
      return new Bounds(
          new BigDecimal(interval.group(1)),
          new BigDecimal(interval.group(5)),
          interval.group(3).equals("≤"),
          interval.group(4).equals("≤"));
    var closed = CLOSED.matcher(value);
    if (closed.matches()) {
      if (closed.group(2) != null
          && closed.group(4) != null
          && headerUnit("元/" + closed.group(2)) != headerUnit("元/" + closed.group(4)))
        throw new IllegalArgumentException("区间两端因素单位不一致：" + raw);
      return new Bounds(
          new BigDecimal(closed.group(1)), new BigDecimal(closed.group(3)), true, true);
    }
    return null;
  }

  private static FactorPriceUnit headerUnit(String raw) {
    String value = normalize(raw);
    if (value.contains("元/克") || value.contains("元/G")) return FactorPriceUnit.CNY_G;
    if (value.contains("元/KG") || value.contains("元/公斤")) return FactorPriceUnit.CNY_KG;
    // 无单位的整数铜价区间由矩阵标题明确为铜行情，按原稿惯例元/吨。
    return FactorPriceUnit.CNY_TON;
  }

  private static int column(Row row, String... names) {
    for (Cell cell : row)
      for (String name : names)
        if (normalize(text(cell)).equals(name)) return cell.getColumnIndex();
    return -1;
  }

  private static Cell cell(MatrixRow row, int column) {
    return column < 0 ? null : row.row().getCell(column);
  }

  private List<CellSource> extrapolationSources(MatrixRow matrix, List<CellSource> notes) {
    var result = new java.util.LinkedHashMap<Address, CellSource>();
    int last =
        matrix.block().columns().stream()
            .mapToInt(c -> Math.max(c.includingTax(), c.excludingTax()))
            .max()
            .orElse(-1);
    // 每个材料行自身的档差值，与说明中的调档步长共同构成可核对证据。
    for (Cell cell : matrix.row())
      if (cell.getColumnIndex() > last && numeric(cell) != null) {
        var value = source(cell);
        result.put(value.address(), value);
      }
    for (var note : notes) result.put(note.address(), note);
    return List.copyOf(result.values());
  }

  private List<CellSource> extrapolationNotes(Sheet sheet) {
    var result = new ArrayList<CellSource>();
    for (Row row : sheet)
      for (Cell cell : row) {
        String value = text(cell);
        if (value.contains("上涨") || value.contains("下浮") || value.contains("下降")) {
          var note = source(cell);
          result.add(note);
        }
      }
    return List.copyOf(result);
  }

  private static CellSource source(Cell cell) {
    if (cell == null) return null;
    CellType valueType =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    String raw =
        valueType == CellType.ERROR
            ? FormulaError.forInt(cell.getErrorCellValue()).getString()
            : text(cell);
    return new CellSource(
        new Address(cell.getSheet().getSheetName(), cell.getAddress().formatAsString()),
        raw,
        cell.getCellType().name(),
        cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null,
        cell.getCellStyle().getDataFormatString());
  }

  public record Bounds(
      BigDecimal low, BigDecimal high, boolean lowerInclusive, boolean upperInclusive) {
    public Bounds {
      if (low == null || high == null || low.compareTo(high) >= 0)
        throw new IllegalArgumentException("区间上下界非法");
    }

    public boolean contains(BigDecimal value) {
      int l = value.compareTo(low), h = value.compareTo(high);
      return (l > 0 || l == 0 && lowerInclusive) && (h < 0 || h == 0 && upperInclusive);
    }
  }

  public record CellSource(
      Address address, String rawValue, String cellType, String formula, String numberFormat) {}

  public record Band(
      Bounds bounds,
      CellSource header,
      Address address,
      CellSource source,
      CompiledFormula compiled,
      BigDecimal price,
      String problem) {}

  public record Rule(
      MaterialPriceRuleKey identity,
      String materialName,
      String sheet,
      int row,
      CellSource mainPriceSource,
      boolean taxIncluded,
      String factorKey,
      FactorPriceUnit factorUnit,
      String matrixSheet,
      int matrixRow,
      String matrixSpecification,
      String matrixSupplier,
      String bindingBasis,
      List<Band> bands,
      List<CellSource> extrapolationSources) {}

  public record RowError(String sheet, Integer row, String materialCode, String message) {}

  public record ParsedWorkbook(List<Rule> rules, List<RowError> errors) {}

  private record MainRow(
      MaterialPriceRuleKey identity,
      String materialName,
      String sheet,
      int row,
      CellSource priceSource,
      boolean taxIncluded) {}

  private record BandColumn(Bounds bounds, CellSource header, int excludingTax, int includingTax) {}

  private record Block(
      Sheet sheet,
      int headerRow,
      int materialColumn,
      int specColumn,
      int supplierColumn,
      String supplierLabel,
      String factorKey,
      FactorPriceUnit factorUnit,
      List<BandColumn> columns) {}

  private record MatrixRow(Block block, Row row) {}
}
