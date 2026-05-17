package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.BindingCandidateBuildResult;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedBindingCandidateBuilderImplTest {

  private final PriceLinkedBindingCandidateBuilderImpl builder =
      new PriceLinkedBindingCandidateBuilderImpl();

  @Test
  @DisplayName("build：示例公式生成材料含税价格 -> E64，废料含税价格 -> E44")
  void buildMapsFirstTwoRefsToMaterialAndScrap() {
    String formula = "下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格+含税加工费";

    BindingCandidateBuildResult result = builder.build(
        "MAT-1001", "MAT-1001|S0001|SHF-001", formula,
        List.of(ref("影响因素", 64, 1001L, 2001L), ref("影响因素", 44, 1002L, 2002L)));

    assertThat(result.getWarnings()).isEmpty();
    assertThat(result.getCandidates()).hasSize(2);
    BindingCandidate material = result.getCandidates().get(0);
    assertThat(material.getMaterialCode()).isEqualTo("MAT-1001");
    assertThat(material.getLinkedItemImportKey()).isEqualTo("MAT-1001|S0001|SHF-001");
    assertThat(material.getTokenName()).isEqualTo("材料含税价格");
    assertThat(material.getFactorIdentityId()).isEqualTo(1001L);
    assertThat(material.getFactorMonthlyPriceId()).isEqualTo(2001L);
    assertThat(material.getSourceRef().getRowNumber()).isEqualTo(64);

    BindingCandidate scrap = result.getCandidates().get(1);
    assertThat(scrap.getTokenName()).isEqualTo("废料含税价格");
    assertThat(scrap.getFactorIdentityId()).isEqualTo(1002L);
    assertThat(scrap.getFactorMonthlyPriceId()).isEqualTo(2002L);
    assertThat(scrap.getSourceRef().getRowNumber()).isEqualTo(44);
  }

  @Test
  @DisplayName("build：少于两处引用时只生成确定的材料候选并提示")
  void buildSingleRefCreatesMaterialOnlyWithWarning() {
    BindingCandidateBuildResult result = builder.build(
        "MAT-1001", "KEY", "下料重量*材料含税价格+含税加工费",
        List.of(ref("影响因素", 64, 1001L, 2001L)));

    assertThat(result.getCandidates()).hasSize(1);
    assertThat(result.getCandidates().getFirst().getTokenName()).isEqualTo("材料含税价格");
    assertThat(result.getWarnings()).hasSize(1);
    assertThat(result.getWarnings().getFirst()).contains("仅识别到 1 处");
  }

  @Test
  @DisplayName("build：多于两处引用时记录 warning，第一版只处理前两处")
  void buildMoreThanTwoRefsWarnsAndUsesFirstTwo() {
    BindingCandidateBuildResult result = builder.build(
        "MAT-1001", "KEY", "复杂公式",
        List.of(
            ref("影响因素", 64, 1001L, 2001L),
            ref("影响因素", 44, 1002L, 2002L),
            ref("影响因素", 12, 1003L, 2003L)));

    assertThat(result.getCandidates()).hasSize(2);
    assertThat(result.getCandidates().get(0).getFactorIdentityId()).isEqualTo(1001L);
    assertThat(result.getCandidates().get(1).getFactorIdentityId()).isEqualTo(1002L);
    assertThat(result.getWarnings()).hasSize(1);
    assertThat(result.getWarnings().getFirst()).contains("识别到 3 处");
  }

  @Test
  @DisplayName("build：中文公式使用短 token 时生成材料价格/废料价格")
  void buildUsesShortTokenNamesWhenFormulaContainsShortNames() {
    String formula = "下料重量*材料价格-(下料重量-产品净重)*废料价格";

    BindingCandidateBuildResult result = builder.build(
        "MAT-1001", "KEY", formula,
        List.of(ref("影响因素", 64, 1001L, 2001L), ref("影响因素", 44, 1002L, 2002L)));

    assertThat(result.getCandidates()).extracting(BindingCandidate::getTokenName)
        .containsExactly("材料价格", "废料价格");
  }

  @Test
  @DisplayName("build：V2-08 未解析引用会跳过并提示，不生成错误候选")
  void buildSkipsUnresolvedRefs() {
    BindingCandidateBuildResult result = builder.build(
        "MAT-1001", "KEY", "下料重量*材料含税价格",
        List.of(unresolved("影响因素", 64), ref("影响因素", 44, 1002L, 2002L)));

    assertThat(result.getCandidates()).hasSize(1);
    assertThat(result.getCandidates().getFirst().getFactorIdentityId()).isEqualTo(1002L);
    assertThat(result.getCandidates().getFirst().getTokenName()).isEqualTo("材料含税价格");
    assertThat(result.getWarnings()).anySatisfy(warning ->
        assertThat(warning).contains("影响因素引用未解析"));
  }

  private ResolvedFactorRef ref(
      String sheetName, Integer rowNumber, Long factorIdentityId, Long factorMonthlyPriceId) {
    ResolvedFactorRef ref = new ResolvedFactorRef();
    ref.setWorkbookName("monthly.xlsx");
    ref.setSheetName(sheetName);
    ref.setRowNumber(rowNumber);
    ref.setFactorIdentityId(factorIdentityId);
    ref.setFactorMonthlyPriceId(factorMonthlyPriceId);
    ref.setFactorSeqNo(String.valueOf(rowNumber));
    ref.setShortName("SUS304/2B");
    ref.setPriceSource("出厂价");
    ref.setPrice(new BigDecimal("16.4"));
    return ref;
  }

  private ResolvedFactorRef unresolved(String sheetName, Integer rowNumber) {
    ResolvedFactorRef ref = new ResolvedFactorRef();
    ref.setWorkbookName("monthly.xlsx");
    ref.setSheetName(sheetName);
    ref.setRowNumber(rowNumber);
    ref.setErrorMessage("找不到影响因素引用");
    return ref;
  }
}
