package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftResponse;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomDraftApplicationService.ElectronicBomTemplateSnapshot;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** 将页面同一份完整父子草稿输出为电子图库协作模板，不读取或重建 EasyData BOM。 */
@Service
public class TechnicalBomTemplateService {

  public static final List<String> HEADERS = List.of(
      "行号", "节点标识", "父节点标识", "层级", "父项料号", "子项料号", "子项名称",
      "规格", "型号", "型号/图号", "物料性质", "相对父项用量", "顶层累计用量", "单位", "排序");

  private final TechnicalBomDraftApplicationService draftService;

  public TechnicalBomTemplateService(TechnicalBomDraftApplicationService draftService) {
    this.draftService = draftService;
  }

  public TemplateFile export(Long taskId) {
    ElectronicBomTemplateSnapshot snapshot = draftService.exportSnapshot(taskId);
    try (Workbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("BOM导入");
      CellStyle headerStyle = headerStyle(workbook);
      Row header = sheet.createRow(0);
      for (int column = 0; column < HEADERS.size(); column++) {
        Cell cell = header.createCell(column);
        cell.setCellValue(HEADERS.get(column));
        cell.setCellStyle(headerStyle);
      }
      Map<String, TechnicalBomDraftResponse.Node> byId = new LinkedHashMap<>();
      for (TechnicalBomDraftResponse.Node node : snapshot.draft().flatNodes()) {
        byId.put(node.nodeId(), node);
      }
      int rowIndex = 1;
      for (TechnicalBomDraftResponse.Node node : snapshot.draft().flatNodes()) {
        TechnicalBomDraftResponse.Node parent = byId.get(node.parentNodeId());
        Row row = sheet.createRow(rowIndex);
        set(row, 0, rowIndex);
        set(row, 1, node.nodeId());
        set(row, 2, node.parentNodeId());
        set(row, 3, node.level());
        set(row, 4, parent == null ? null : parent.materialCode());
        set(row, 5, node.materialCode());
        set(row, 6, node.materialName());
        set(row, 7, node.materialSpec());
        set(row, 8, node.materialModel());
        set(row, 9, node.drawingNo());
        set(row, 10, natureLabel(node.materialNature()));
        set(row, 11, node.quantity());
        set(row, 12, node.quantityToTop());
        set(row, 13, node.unit());
        set(row, 14, node.sortSeq());
        rowIndex++;
      }
      int[] widths = {10, 18, 18, 8, 18, 18, 22, 22, 18, 18, 16, 16, 16, 10, 10};
      for (int column = 0; column < widths.length; column++) {
        sheet.setColumnWidth(column, widths[column] * 256);
      }
      sheet.createFreezePane(0, 1);
      workbook.write(output);
      String target = snapshot.productCode() == null
          ? snapshot.temporaryProductKey() : snapshot.productCode();
      return new TemplateFile("电子图库BOM协作模板_" + target + ".xlsx", output.toByteArray());
    } catch (Exception exception) {
      throw new IllegalStateException("电子图库BOM模板生成失败", exception);
    }
  }

  private static CellStyle headerStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    Font font = workbook.createFont();
    font.setBold(true);
    style.setFont(font);
    return style;
  }

  private static void set(Row row, int column, Object value) {
    if (value == null) return;
    Cell cell = row.createCell(column);
    if (value instanceof BigDecimal decimal) {
      cell.setCellValue(decimal.doubleValue());
    } else if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
    } else {
      cell.setCellValue(value.toString());
    }
  }

  private static String natureLabel(String value) {
    return switch (value == null ? "" : value) {
      case "PURCHASE" -> "采购件";
      case "MANUFACTURE" -> "制造件";
      case "OUTSOURCE" -> "委外件";
      case "VIRTUAL_PACKAGE" -> "虚拟件（包装）";
      default -> value;
    };
  }

  public record TemplateFile(String fileName, byte[] bytes) {}
}
