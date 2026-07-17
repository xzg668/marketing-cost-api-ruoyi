package com.sanhua.marketingcost.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 可重复构造的 CMS Excel 测试夹具，避免单测依赖个人桌面文件。 */
public final class CmsWorkbookFixtures {

  private CmsWorkbookFixtures() {}

  public static InputStream materialScrapWorkbook() {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Sheet0");
      writeRow(sheet.createRow(0), materialScrapHeader());
      writeRow(sheet.createRow(1), materialScrapChineseHeader());
      writeRow(sheet.createRow(2), materialScrapChineseHeader());
      writeRow(sheet.createRow(3), materialScrapChineseHeader());
      writeRow(sheet.createRow(4), materialScrapRow(
          "701010001000", "铜材", "301990218", "废铜", "SEQ-1"));
      writeRow(sheet.createRow(5), materialScrapRow(
          "301050066", "拉制铜管", "301990317", "废紫铜沫（干净）", "SEQ-2"));
      writeRow(sheet.createRow(6), materialScrapRow(
          "301220046", "铜管", "301990752", "废铜管", "SEQ-3"));
      writeRow(sheet.createRow(7), materialScrapRow(
          "301240123", "不锈钢带", "301990444", "废不锈钢沫和丝网", "SEQ-4"));
      writeRow(sheet.createRow(8), materialScrapRow(
          "301050054", "拉制铜管", "301990317", "废紫铜沫（干净）", "SEQ-5"));
      writeRow(sheet.createRow(9), materialScrapRow(
          "301280056", "不锈钢丝网", "301990444", "废不锈钢沫和丝网", "SEQ-6"));
      workbook.write(output);
      return new ByteArrayInputStream(output.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException("构造 CMS 原材料-废料夹具失败", ex);
    }
  }

  private static List<String> materialScrapHeader() {
    return List.of(
        "materialCode", "materialName", "materialSpecifications", "materialModel", "materialUnit",
        "recycleMaterialCode", "recycleMaterialName", "recycleMaterialSpecification",
        "recycleMaterialModel", "recycleMaterialUnit", "RecycleMaterialInfoVersion", "costGroupName",
        "creater", "createdDeptId", "owner", "ownerDeptId", "createdTime", "modifier", "modifiedTime",
        "sequenceNo", "id", "name", "workflowInstanceId", "sequenceStatus", "linkDetailId",
        "approvalPerson", "syncTime", "approvalTime", "costGroupCode", "costGroup", "effectiveDate",
        "postingPeriod");
  }

  private static List<String> materialScrapChineseHeader() {
    return List.of(
        "物料料号", "物料品名", "物料规格", "物料型号", "物料单位", "回收料号", "回收料品名",
        "回收料规格", "回收料型号", "回收料单位", "同步版本", "成本组名称", "创建人", "创建人部门",
        "拥有者", "拥有者部门", "创建时间", "修改人", "修改时间", "单据号", "id", "数据标题",
        "流程实例ID", "单据状态", "关联明细id", "审核人", "同步时间", "审核时间", "成本组编码",
        "成本组关联Id", "生效时间", "期间");
  }

  private static List<String> materialScrapRow(
      String materialCode,
      String materialName,
      String recycleMaterialCode,
      String recycleMaterialName,
      String sequenceNo) {
    return List.of(
        materialCode, materialName, "T2 Y2", "", "千克",
        recycleMaterialCode, recycleMaterialName, "", "", "千克",
        "1", "商用大明市生产基地", "admin", "", "admin", "",
        "2025-09-12 18:14:50", "admin", "2025-09-12 18:14:50",
        sequenceNo, "CMS-" + sequenceNo, "CMS单据", "", "已完成", "LINK-" + sequenceNo,
        "审核人", "2025-09-12", "2025-09-12", "002", "COST-GROUP-1", "", "2025-09");
  }

  private static void writeRow(Row row, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      row.createCell(index).setCellValue(values.get(index));
    }
  }
}
