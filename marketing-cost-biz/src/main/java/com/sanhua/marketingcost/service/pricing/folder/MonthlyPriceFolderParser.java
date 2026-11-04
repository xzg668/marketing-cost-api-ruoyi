package com.sanhua.marketingcost.service.pricing.folder;

import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.*;
import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;

import com.sanhua.marketingcost.formula.workbook.LinkedApprovalTable;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.service.impl.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

/** 通过表头和实际区间矩阵分类，不凭文件名中的月份、供应商或“调价”字样猜类型。 */
@Component
public class MonthlyPriceFolderParser {
  private final OriginalLinkedWorkbookParser linked;
  private final OriginalRangeWorkbookParser ranges;
  private final FactorIdentityMapper identities;
  private final LinkedApprovalImportContext approval;

  public MonthlyPriceFolderParser(OriginalLinkedWorkbookParser linked, OriginalRangeWorkbookParser ranges,
      FactorIdentityMapper identities, LinkedApprovalImportContext approval) {
    this.linked = linked; this.ranges = ranges; this.identities = identities; this.approval = approval;
  }

  public ParsedFile parse(byte[] bytes, String path, String unit) {
    String lower = path.toLowerCase(Locale.ROOT);
    String name = lower.substring(lower.lastIndexOf('/') + 1);
    if (name.startsWith("~$") || name.startsWith(".~") || name.startsWith("._") || name.equals(".ds_store"))
      return reference("办公软件临时文件，不是价格原稿，已跳过");
    if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls"))
      return reference("非Excel附件，保留原件，不作为联动或区间价格导入");
    List<Presence> present = new ArrayList<>();
    try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      List<LinkedApprovalTable.Layout> layouts;
      try { layouts = LinkedApprovalTable.layouts(book); }
      catch (IllegalArgumentException missing) {
        // 已明确命名的主表损坏时必须报错，不能退回附表或归类为参考资料。
        if (book.getSheet("合并联动价表") != null || book.getSheet("ImportData") != null) throw missing;
        boolean finance = false;
        boolean material = false;
        for (Sheet sheet : book) {
          finance |= header(sheet, Set.of("基价名称", "基价代号", "计量单位")) != null;
          for (int i = 0; i <= Math.min(24, sheet.getLastRowNum()); i++) {
            Row row = sheet.getRow(i);
            if (row != null) for (Cell cell : row)
              material |= Set.of("物料代码", "料号", "U9代码").contains(text(cell));
          }
        }
        if (finance && !material) return reference("财务基价资料，请在财务基价入口按目标月份维护；本次不重复导入");
        if (!material) return reference("未发现物料价格主表，作为参考资料保留");
        throw missing;
      }
      for (var layout : layouts) {
        var h = layout.header();
        for (int i = h.row() + 1; i <= h.sheet().getLastRowNum(); i++) {
          Row row = h.sheet().getRow(i);
          String code = text(at(row, h, "物料代码"));
          if (code.isBlank()) continue;
          String org;
          try { org = layout.approval() ? "210" : organization(at(row, h, "组织")); }
          catch (IllegalArgumentException invalid) { org = text(at(row, h, "组织")); }
          present.add(new Presence(org, code, text(at(row, h, "供应商代码")), text(at(row, h, "供应商名称"))));
        }
      }
      boolean range = !layouts.getFirst().approval()
          && !layouts.getFirst().header().sheet().getSheetName().equals("合并联动价表")
          && ranges.hasPriceMatrix(book, layouts.getFirst().header().sheet());
      if (range) {
        var parsed = ranges.parse(bytes, path, unit);
        return new ParsedFile("RANGE", List.of(), parsed.rules(), parsed.errors().stream()
            .map(e -> new Issue(e.sheet(), e.row(), e.materialCode(), e.message())).toList(),
            parsed.notices(), List.copyOf(present), "按主表及实际区间矩阵识别");
      }
      var context = approval.read(bytes, "210");
      var catalogs = new HashMap<String, Set<String>>();
      var parsed = linked.parseWithCatalog(bytes, path, unit,
          org -> catalogs.computeIfAbsent(org, ignored -> Set.copyOf(identities.findFinanceManagedKeys(unit, org))), context);
      return new ParsedFile("LINKED", parsed.rules(), List.of(), parsed.errors().stream()
          .map(e -> new Issue(e.sheet(), e.row(), e.materialCode(), e.message())).toList(),
          parsed.notices(), List.copyOf(present), "按物料主表实际单价公式识别，附表只读取引用");
    } catch (Exception error) {
      return new ParsedFile("ERROR", List.of(), List.of(),
          List.of(new Issue(null, null, null, Objects.toString(error.getMessage(), "文件无法读取"))),
          List.of(), List.copyOf(present), "文件待修正；其他文件继续处理");
    }
  }

  private ParsedFile reference(String message) {
    return new ParsedFile("REFERENCE", List.of(), List.of(), List.of(), List.of(), List.of(), message);
  }
}
