package com.sanhua.marketingcost.formula.workbook;

import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.*;

import java.math.BigDecimal;
import java.util.*;
import com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.Header;
import org.apache.poi.ss.usermodel.*;

/** 加工费审批原稿的列映射；不改写工作簿、实际价格公式或既有 ImportData 契约。 */
public final class LinkedApprovalTable {
  private LinkedApprovalTable() {}

  private static final Set<String> STANDARD =
      Set.of("组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税");
  private static final Set<String> APPROVAL =
      Set.of("物料代码", "现联动公式", "现含税加工费", "现不含税加工费", "现供方名称", "税率");

  public record Layout(Header header, boolean approval) {}

  public static List<Layout> layouts(Workbook workbook) {
    // 既有主表优先；辅助 Sheet 即使出现物料列也不重复追加。
    if (workbook.getSheet("ImportData") != null
        || java.util.stream.StreamSupport.stream(workbook.spliterator(), false)
            .anyMatch(sheet -> header(sheet, STANDARD) != null))
      return List.of(new Layout(mainSheet(workbook), false));
    List<Layout> result = new ArrayList<>();
    for (Sheet sheet : workbook) {
      for (int i = sheet.getFirstRowNum(); i <= Math.min(24, sheet.getLastRowNum()); i++) {
        Row row = sheet.getRow(i);
        if (row == null) continue;
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : row) {
          String name = text(cell).replaceAll("\\s", "").replaceAll("\\d+$", "");
          if (!name.isEmpty()) columns.put(name, cell.getColumnIndex());
        }
        if (!columns.keySet().containsAll(APPROVAL)) continue;
        Map<String, Integer> mapped = new LinkedHashMap<>(columns);
        alias(mapped, columns, "物料名称", "物料名称");
        alias(mapped, columns, "规格型号", "规格");
        alias(mapped, columns, "供应商名称", "现供方名称");
        alias(mapped, columns, "联动公式", "现联动公式");
        alias(mapped, columns, "单位", "价格单位");
        Integer price = columns.get("价格");
        if (price == null) price = columns.get("单价");
        if (price == null) {
          // 无标题的价格列必须是物料区唯一的公式列，不能取工作簿物理末列的行情数值。
          Set<Integer> candidates = new LinkedHashSet<>();
          for (int n = i + 1; n <= sheet.getLastRowNum(); n++) {
            Row data = sheet.getRow(n);
            if (data == null || materialCode(data.getCell(columns.get("物料代码"))).isBlank()) continue;
            for (Cell cell : data)
              if (cell.getCellType() == CellType.FORMULA
                  && cell.getColumnIndex() > columns.get("现供方名称")
                  && text(row.getCell(cell.getColumnIndex())).isBlank())
                candidates.add(cell.getColumnIndex());
          }
          if (candidates.size() != 1)
            throw new IllegalArgumentException(sheet.getSheetName() + "：无法唯一确定审批表价格公式列，候选=" + candidates);
          price = candidates.iterator().next();
        }
        mapped.put("单价", price);
        result.add(new Layout(new Header(sheet, i, Map.copyOf(mapped)), true));
        break;
      }
    }
    if (result.isEmpty()) mainSheet(workbook); // 保留既有格式错误，不把不认识的表当成空文件。
    return List.copyOf(result);
  }

  private static void alias(Map<String, Integer> target, Map<String, Integer> source, String name, String from) {
    if (source.containsKey(from)) target.put(name, source.get(from));
  }

  public static String unit(Row row, Header header, Map<String, String> materialUnits) {
    String value = text(at(row, header, "单位"));
    if (value.startsWith("人民币,") || value.startsWith("人民币，")) value = value.substring(4).trim();
    return value.isBlank()
        ? materialUnits.getOrDefault(materialCode(at(row, header, "物料代码")), "") : value;
  }

  /** 当前审批原稿的未税口径必须由实际的最后除税步骤和本行税率共同证实。 */
  public static boolean taxIncluded(Row row, Header header) {
    BigDecimal rate = numeric(at(row, header, "税率"));
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0)
      throw new IllegalArgumentException("审批表税率缺失或无效");
    String formula = at(row, header, "单价").getCellFormula().replaceAll("\\s", "");
    String divisor = BigDecimal.ONE.add(rate).stripTrailingZeros().toPlainString();
    if (!formula.endsWith("/" + divisor))
      throw new IllegalArgumentException("不能从实际价格公式的最后除税步骤确认未税口径");
    return false;
  }
}
