package com.sanhua.marketingcost.service.financefactor;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl;
import com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl;
import org.junit.jupiter.api.Test;

class FinanceFactor1109WorkbookTest {
  @Test void originalXlsReadsTextFormulaMetadataAndStopsBeforeRawSourceAppendix() throws Exception {
    byte[] original;
    try (var in=getClass().getResourceAsStream("/finance-factor/finance-base-1109.xls")) { original=in.readAllBytes(); }
    var parser=new FinanceFactorWorkbookParser(new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var parsed=parser.parse(original,"1109.xls","210","2026.11.2（9万报价）");
    assertThat(parsed.problems()).isEmpty();
    assertThat(parsed.schemes()).extracting(v->v.code()).containsExactly("CU_90000","CU_100000","CU_110000","CU_120000");
    assertThat(parsed.values()).hasSize(64).allMatch(v->v.rowNumber()>=5 && v.rowNumber()<=20);
    assertThat(parsed.values()).noneMatch(v->v.canonicalKey().contains("304") || v.canonicalKey().contains("316"));
    assertThat(parsed.values().stream().filter(v->v.rowNumber()==20)).hasSize(4).allMatch(v->v.canonicalKey().equals("AVG|A00") && v.unit().equals("CNY/KG"));
    assertThat(parsed.values().stream().filter(v->v.cellRef().equals("AD11")).findFirst().orElseThrow().price()).isEqualByComparingTo("58.4703101600419");
    assertThat(parsed.values().stream().filter(v->v.cellRef().equals("AD12")).findFirst().orElseThrow().price()).isEqualByComparingTo("60.996337260704");
    assertThat(FinanceFactorWorkbookParser.sha256(original)).isEqualTo("9a450d753dc410f42eb1bbd0677147b74d8af80dfcc0bb4684d3f557218f20af");
  }
}
