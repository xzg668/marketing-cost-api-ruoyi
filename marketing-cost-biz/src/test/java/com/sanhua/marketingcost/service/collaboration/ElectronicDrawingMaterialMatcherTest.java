package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库图号与当前组织 U9 料号匹配")
class ElectronicDrawingMaterialMatcherTest {
  private final MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingMaterialMatcher matcher = new ElectronicDrawingMaterialMatcher(mapper);

  @Test
  void autoSelectsOnlyWhenHighestPriorityIdentityHasOneCandidate() {
    when(mapper.selectByDrawingIdentities(anyCollection(), eq(null), eq("COMMERCIAL"), eq(20_000)))
        .thenReturn(List.of(material("1001", "D-1", null, null, "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("1", "D-1")));

    assertThat(result.getFirst().status()).isEqualTo(ElectronicDrawingMaterialMatcher.Status.AUTO_MATCHED);
    assertThat(result.getFirst().selectedMaterialCode()).isEqualTo("1001");
    assertThat(result.getFirst().options().getFirst().matchedBy()).isEqualTo("DRAWING_NO");
    verify(mapper).selectByDrawingIdentities(anyCollection(), eq(null), eq("COMMERCIAL"), eq(20_000));
  }

  @Test
  void neverSilentlyChoosesFirstWhenDrawingHasMultipleCandidates() {
    when(mapper.selectByDrawingIdentities(anyCollection(), eq(null), eq("COMMERCIAL"), eq(20_000)))
        .thenReturn(List.of(
            material("1001", "D-1", null, null, "制造件"),
            material("1002", "D-1", null, null, "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("1", "D-1")));

    assertThat(result.getFirst().status()).isEqualTo(ElectronicDrawingMaterialMatcher.Status.AMBIGUOUS);
    assertThat(result.getFirst().selectedMaterialCode()).isNull();
    assertThat(result.getFirst().options()).extracting(ElectronicDrawingMaterialMatcher.Option::materialCode)
        .containsExactly("1001", "1002");
  }

  @Test
  void drawingNumberBeatsSpecAndModelCandidates() {
    when(mapper.selectByDrawingIdentities(anyCollection(), eq(null), eq("COMMERCIAL"), eq(20_000)))
        .thenReturn(List.of(
            material("DRAWING", "D-1", null, null, "采购件"),
            material("SPEC", null, "D-1", null, "采购件"),
            material("MODEL", null, null, "D-1", "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("1", "D-1")));

    assertThat(result.getFirst().status()).isEqualTo(ElectronicDrawingMaterialMatcher.Status.AUTO_MATCHED);
    assertThat(result.getFirst().selectedMaterialCode()).isEqualTo("DRAWING");
  }

  @Test
  void returnsUnmatchedInsteadOfCrossOrganizationFallback() {
    when(mapper.selectByDrawingIdentities(anyCollection(), eq(null), eq("PLATE"), eq(20_000)))
        .thenReturn(List.of());
    var result = matcher.match("PLATE", List.of(node("1", "D-1")));
    assertThat(result.getFirst().status()).isEqualTo(ElectronicDrawingMaterialMatcher.Status.UNMATCHED);
    assertThat(result.getFirst().options()).isEmpty();
  }

  private ElectronicDrawingExcelParseResult.SourceNode node(String sequence, String drawing) {
    return new ElectronicDrawingExcelParseResult.SourceNode(sequence, null, 1, drawing,
        "名称", null, null, null, BigDecimal.ONE, null, null, 2);
  }

  private MaterialMasterRaw material(
      String code, String drawing, String spec, String model, String nature) {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode(code);
    row.setMaterialName("名称" + code);
    row.setDrawingNo(drawing);
    row.setMaterialSpec(spec);
    row.setMaterialModel(model);
    row.setShapeAttr(nature);
    row.setUnit("件");
    return row;
  }
}
