package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.FormulaFactorRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedFormulaFactorRefParserImplTest {

  private final PriceLinkedFormulaFactorRefParserImpl parser =
      new PriceLinkedFormulaFactorRefParserImpl();

  @Test
  @DisplayName("parse：能解析未加引号的影响因素引用")
  void parseUnquotedFactorRef() {
    List<FormulaFactorRef> refs =
        parser.parse("ROUND($I$2*影响因素!$E$64/1000+K2,4)/1.13");

    assertThat(refs).hasSize(1);
    FormulaFactorRef ref = refs.getFirst();
    assertThat(ref.getWorkbookName()).isNull();
    assertThat(ref.getSheetName()).isEqualTo("影响因素");
    assertThat(ref.getColumnName()).isEqualTo("E");
    assertThat(ref.getRowNumber()).isEqualTo(64);
    assertThat(ref.getRawRef()).isEqualTo("影响因素!$E$64");
    assertThat(ref.getOrderIndex()).isEqualTo(1);
  }

  @Test
  @DisplayName("parse：能解析加引号的影响因素引用")
  void parseQuotedFactorRef() {
    List<FormulaFactorRef> refs =
        parser.parse("ROUND($I$2*'影响因素'!$E$64/1000+K2,4)/1.13");

    assertThat(refs).hasSize(1);
    FormulaFactorRef ref = refs.getFirst();
    assertThat(ref.getWorkbookName()).isNull();
    assertThat(ref.getSheetName()).isEqualTo("影响因素");
    assertThat(ref.getColumnName()).isEqualTo("E");
    assertThat(ref.getRowNumber()).isEqualTo(64);
    assertThat(ref.getRawRef()).isEqualTo("'影响因素'!$E$64");
  }

  @Test
  @DisplayName("parse：能解析跨 Excel 的影响因素引用")
  void parseExternalWorkbookFactorRef() {
    String formula = "=ROUND($I$2*'[产品成本计算表（3.29- 提供）.xls]影响因素10'!$E$64/1000"
        + "-(I2-J2)*'[产品成本计算表（3.29- 提供）.xls]影响因素10'!$E$44/1000+K2,4)/1.13";

    List<FormulaFactorRef> refs = parser.parse(formula);

    assertThat(refs).hasSize(2);
    FormulaFactorRef material = refs.get(0);
    assertThat(material.getWorkbookName()).isEqualTo("产品成本计算表（3.29- 提供）.xls");
    assertThat(material.getSheetName()).isEqualTo("影响因素10");
    assertThat(material.getColumnName()).isEqualTo("E");
    assertThat(material.getRowNumber()).isEqualTo(64);
    assertThat(material.getRawRef()).isEqualTo("'[产品成本计算表（3.29- 提供）.xls]影响因素10'!$E$64");
    assertThat(material.getOrderIndex()).isEqualTo(1);

    FormulaFactorRef scrap = refs.get(1);
    assertThat(scrap.getWorkbookName()).isEqualTo("产品成本计算表（3.29- 提供）.xls");
    assertThat(scrap.getSheetName()).isEqualTo("影响因素10");
    assertThat(scrap.getRowNumber()).isEqualTo(44);
    assertThat(scrap.getOrderIndex()).isEqualTo(2);
  }

  @Test
  @DisplayName("parse：同一公式重复引用同一 sheet + row 时去重并保留首次顺序")
  void parseDeduplicatesSameSheetAndRow() {
    String formula = "影响因素!$E$64+影响因素!E64+'影响因素'!$E$44+影响因素!$E$64";

    List<FormulaFactorRef> refs = parser.parse(formula);

    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).getSheetName()).isEqualTo("影响因素");
    assertThat(refs.get(0).getRowNumber()).isEqualTo(64);
    assertThat(refs.get(0).getOrderIndex()).isEqualTo(1);
    assertThat(refs.get(1).getSheetName()).isEqualTo("影响因素");
    assertThat(refs.get(1).getRowNumber()).isEqualTo(44);
    assertThat(refs.get(1).getOrderIndex()).isEqualTo(2);
  }

  @Test
  @DisplayName("parse：忽略非影响因素 sheet 和本地单元格引用")
  void parseIgnoresNonFactorRefs() {
    String formula = "联动公式!$E$64+SUM(A1:B2)+影响因素10!$E$64";

    List<FormulaFactorRef> refs = parser.parse(formula);

    assertThat(refs).hasSize(1);
    assertThat(refs.getFirst().getSheetName()).isEqualTo("影响因素10");
    assertThat(refs.getFirst().getRowNumber()).isEqualTo(64);
  }
}
