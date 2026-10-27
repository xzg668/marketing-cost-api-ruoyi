package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.*;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.formula.workbook.LinkedApprovalTable;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** 审批表缺单位时，仅按财务明确选择的料品组织补齐；不跨组织猜单位。 */
@Component
public final class LinkedApprovalImportContext {
  private final MaterialMasterRawMapper materials;

  public LinkedApprovalImportContext(MaterialMasterRawMapper materials) { this.materials = materials; }

  public OriginalLinkedWorkbookParser.ImportContext read(byte[] bytes, String orgCode) {
    Set<String> codes = new LinkedHashSet<>();
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      for (var layout : LinkedApprovalTable.layouts(workbook)) {
        if (!layout.approval()) continue;
        var header = layout.header();
        for (int i = header.row() + 1; i <= header.sheet().getLastRowNum(); i++) {
          var row = header.sheet().getRow(i);
          String material = materialCode(at(row, header, "物料代码"));
          if (!material.isBlank() && text(at(row, header, "单位")).isBlank()) codes.add(material);
        }
      }
    } catch (Exception error) {
      throw new IllegalArgumentException("读取审批表计价单位失败：" + error.getMessage(), error);
    }
    Map<String, String> units = new LinkedHashMap<>();
    if (!codes.isEmpty()) {
      String organization = MaterialOrganization.fromPriceOrgCode(orgCode).getCode();
      for (var row : materials.selectByLatestBatchAndCodes(codes, null, organization)) {
        String value = row.getUnit() == null ? "" : row.getUnit().trim();
        if (value.isBlank()) continue;
        String previous = units.putIfAbsent(row.getMaterialCode(), value);
        if (previous != null && !previous.equals(value))
          throw new IllegalArgumentException("料品档案计价单位冲突：" + row.getMaterialCode());
      }
    }
    return new OriginalLinkedWorkbookParser.ImportContext(orgCode, units);
  }
}
