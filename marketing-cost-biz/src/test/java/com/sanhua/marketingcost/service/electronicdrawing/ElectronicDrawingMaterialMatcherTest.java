package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库图号与当前组织 U9 料号匹配")
class ElectronicDrawingMaterialMatcherTest {
  private final MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingMaterialMatcher matcher =
      new ElectronicDrawingMaterialMatcher(mapper);

  @Test
  void autoSelectsOnlyWhenDrawingHasOneCandidate() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL")))
        .thenReturn(List.of(material("1001", "D-1", null, null, "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("1", "D-1")));

    assertThat(result.getFirst().status())
        .isEqualTo(ElectronicDrawingMaterialMatcher.Status.AUTO_MATCHED);
    assertThat(result.getFirst().selectedMaterialCode()).isEqualTo("1001");
    assertThat(result.getFirst().options().getFirst().matchedBy()).isEqualTo("DRAWING_NO");
    verify(mapper).selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL"));
  }

  @Test
  void neverSilentlyChoosesFirstWhenDrawingHasMultipleCandidates() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL")))
        .thenReturn(
            List.of(
                material("1001", "D-1", null, null, "制造件"),
                material("1002", "D-1", null, null, "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("1", "D-1")));

    assertThat(result.getFirst().status())
        .isEqualTo(ElectronicDrawingMaterialMatcher.Status.AMBIGUOUS);
    assertThat(result.getFirst().selectedMaterialCode()).isNull();
    assertThat(result.getFirst().options())
        .extracting(ElectronicDrawingMaterialMatcher.Option::materialCode)
        .containsExactly("1001", "1002");
  }

  @Test
  void onlyDrawingNumberMatchesRegardlessOfOtherFields() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL")))
        .thenReturn(
            List.of(
                material("DRAWING", "D-1", null, null, "采购件"),
                material("SPEC", null, "D-1", null, "采购件"),
                material("MODEL", null, null, "D-1", "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("1", "D-1")));

    assertThat(result.getFirst().status())
        .isEqualTo(ElectronicDrawingMaterialMatcher.Status.AUTO_MATCHED);
    assertThat(result.getFirst().selectedMaterialCode()).isEqualTo("DRAWING");
  }

  @Test
  void returnsUnmatchedInsteadOfCrossOrganizationFallback() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("PLATE"))).thenReturn(List.of());
    var result = matcher.match("PLATE", List.of(node("1", "D-1")));
    assertThat(result.getFirst().status())
        .isEqualTo(ElectronicDrawingMaterialMatcher.Status.UNMATCHED);
    assertThat(result.getFirst().options()).isEmpty();
  }

  @Test
  void dashUnderscoreAndSpaceDifferencesNeverBecomeAnAutomaticMatch() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL")))
        .thenReturn(
            List.of(material("9990000053986", null, "不锈钢_M8_A2-70_钝化_GB/T 6170", null, "采购件")));

    var result = matcher.match("COMMERCIAL", List.of(node("40", "M8-A2-70-钝化-GB/T6170")));

    assertThat(result.getFirst().status())
        .isEqualTo(ElectronicDrawingMaterialMatcher.Status.UNMATCHED);
    assertThat(result.getFirst().selectedMaterialCode()).isNull();
  }

  @Test
  void neverFallsBackToCodeSpecOrModelWhenDrawingIsAbsent() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL")))
        .thenReturn(List.of(material("D-1", null, "D-1", "D-1", "采购件")));
    assertThat(matcher.match("COMMERCIAL", List.of(node("1", "D-1"))).getFirst().status())
        .isEqualTo(ElectronicDrawingMaterialMatcher.Status.UNMATCHED);
  }

  @Test
  void retainsEveryRowEvenWhenDrawingAndPrintedSequenceRepeat() {
    when(mapper.selectByDrawingNumbers(anyCollection(), eq("COMMERCIAL")))
        .thenReturn(List.of(material("P1", "D-1", null, null, "采购件")));
    var rows =
        List.of(
            new ElectronicDrawingMaterialMatcher.Input("group1:1", 1, "D-1", "first"),
            new ElectronicDrawingMaterialMatcher.Input("group1:2", 2, "D-1", "second"),
            new ElectronicDrawingMaterialMatcher.Input("group2:1", 1, "D-1", "third"));
    var matches = matcher.match("COMMERCIAL", rows);
    assertThat(matches)
        .extracting(ElectronicDrawingMaterialMatcher.Match::sourceSequence)
        .containsExactly("group1:1", "group1:2", "group2:1");
    assertThat(matches)
        .extracting(ElectronicDrawingMaterialMatcher.Match::selectedMaterialCode)
        .containsExactly("P1", "P1", "P1");
  }

  private ElectronicDrawingMaterialMatcher.Input node(String sequence, String drawing) {
    return new ElectronicDrawingMaterialMatcher.Input(sequence, 2, drawing, "名称");
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
