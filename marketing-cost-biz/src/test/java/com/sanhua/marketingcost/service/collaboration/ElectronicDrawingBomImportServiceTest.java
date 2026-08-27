package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingMaterialMappingRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomWorkspaceResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Match;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Option;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Status;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomDraftApplicationService.ImportedNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("电子图库 Excel 导入、草稿恢复和人工映射")
class ElectronicDrawingBomImportServiceTest {
  private final ElectronicDrawingExcelParser parser = mock(ElectronicDrawingExcelParser.class);
  private final ElectronicDrawingMaterialMatcher matcher = mock(ElectronicDrawingMaterialMatcher.class);
  private final TechnicalBomDraftApplicationService draftService = mock(TechnicalBomDraftApplicationService.class);
  private final QuoteBomSupplementDetailMapper detailMapper = mock(QuoteBomSupplementDetailMapper.class);
  private final MaterialMasterRawMapper materialMapper = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingBomImportService service = new ElectronicDrawingBomImportService(
      parser, new ElectronicDrawingBomCandidateFactory(), matcher, draftService, detailMapper,
      materialMapper);
  private final ElectronicDrawingSourceMetadataCodec metadata =
      new ElectronicDrawingSourceMetadataCodec();

  @Test
  void importPersistsAutoMatchedAndAmbiguousRowsWithoutChoosingFirstCandidate() {
    var first = source("1", null, "D-1", "唯一", 2);
    var second = source("2", null, "D-2", "歧义", 3);
    var parsed = new ElectronicDrawingExcelParseResult(
        "formal.xlsx", "Sheet", List.of(first, second), List.of());
    when(parser.parse(eq("formal.xlsx"), any())).thenReturn(parsed);
    Option only = option("1001", "D-1", "采购件");
    Option a = option("2001", "D-2", "制造件");
    Option b = option("2002", "D-2", "采购件");
    List<Match> matches = List.of(
        new Match("1", 2, "D-1", "唯一", Status.AUTO_MATCHED, "1001", List.of(only)),
        new Match("2", 3, "D-2", "歧义", Status.AMBIGUOUS, null, List.of(a, b)));
    when(matcher.match(eq("COMMERCIAL"), anyList())).thenReturn(matches);
    TechnicalBomWorkspaceResponse before = workspace(3, null);
    TechnicalBomDraftResponse afterDraft = draft(4, false);
    when(draftService.workspace(10L)).thenReturn(before, workspace(4, afterDraft));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(details(first, second));

    var response = service.importFile(10L, 3, "formal.xlsx", new byte[] {1, 2, 3});

    ArgumentCaptor<List<ImportedNode>> imported = ArgumentCaptor.forClass(List.class);
    verify(draftService).replaceFromElectronicDrawingExcel(eq(10L), eq(3), imported.capture());
    assertThat(imported.getValue()).hasSize(3);
    assertThat(imported.getValue().get(1).materialCode()).isEqualTo("1001");
    assertThat(imported.getValue().get(2).materialCode()).isNull();
    assertThat(response.parsed()).isTrue();
    assertThat(response.autoMatchedCount()).isEqualTo(1);
    assertThat(response.ambiguousCount()).isEqualTo(1);
    assertThat(response.mappingComplete()).isFalse();
    assertThat(response.mappings().get(1).selectedMaterialCode()).isNull();
  }

  @Test
  void confirmedMappingAlwaysUsesCurrentOrganizationMasterFields() {
    var source = source("1", null, "D-2", "歧义", 2);
    TechnicalBomDraftResponse beforeDraft = draft(4, false);
    TechnicalBomDraftResponse afterDraft = draft(5, true);
    when(draftService.workspace(10L)).thenReturn(workspace(4, beforeDraft), workspace(5, afterDraft));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(details(source));
    Option a = option("2001", "D-2", "制造件");
    Option b = option("2002", "D-2", "采购件");
    when(matcher.match(eq("COMMERCIAL"), anyList())).thenReturn(List.of(
        new Match("1", 2, "D-2", "歧义", Status.AMBIGUOUS, null, List.of(a, b))));
    MaterialMasterRaw selected = master("2002", "U9名称", "U9规格", "U9型号", "U9图号", "采购件");
    when(materialMapper.selectByLatestBatchAndCodes(any(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(selected));

    var response = service.applyMappings(10L,
        new ElectronicDrawingMaterialMappingRequest(4,
            List.of(new ElectronicDrawingMaterialMappingRequest.Selection("N2", "2002"))));

    ArgumentCaptor<List<ImportedNode>> imported = ArgumentCaptor.forClass(List.class);
    verify(draftService).replaceFromElectronicDrawingExcel(eq(10L), eq(4), imported.capture());
    ImportedNode mapped = imported.getValue().get(1);
    assertThat(mapped.materialCode()).isEqualTo("2002");
    assertThat(mapped.materialName()).isEqualTo("U9名称");
    assertThat(mapped.materialSpec()).isEqualTo("U9规格");
    assertThat(mapped.materialModel()).isEqualTo("U9型号");
    assertThat(mapped.materialNature()).isEqualTo("PURCHASE");
    assertThat(response.confirmedCount()).isEqualTo(1);
    assertThat(response.mappingComplete()).isTrue();
  }

  private TechnicalBomWorkspaceResponse workspace(int version, TechnicalBomDraftResponse draft) {
    return new TechnicalBomWorkspaceResponse(10L, version,
        new TechnicalBomWorkspaceResponse.TargetProduct(
            "P-1", null, "产品", "S", "M", "PD", "MANUFACTURE", "210", "COMMERCIAL"),
        draft == null ? 1 : 2, "ACTION", "操作", List.of(), draft, null, "NOT_CHECKED", List.of());
  }

  private TechnicalBomDraftResponse draft(int version, boolean mapped) {
    var root = node("N1", null, 0, "P-1", false, "产品", "MANUFACTURE");
    var child = node("N2", "N1", 1, mapped ? "2002" : null, !mapped, "歧义", "PURCHASE");
    return new TechnicalBomDraftResponse(99L, version, "ELECTRONIC_DRAWING_EXCEL", null,
        mapped, List.of(), List.of(root), List.of(root, child));
  }

  private TechnicalBomDraftResponse.Node node(
      String id, String parent, int level, String code, boolean temporary, String name, String nature) {
    return new TechnicalBomDraftResponse.Node(id, parent, level, code, temporary, name,
        "规格", "型号", "D-2", nature, BigDecimal.ONE, BigDecimal.ONE,
        "件", level + 1, true, List.of());
  }

  private List<QuoteBomSupplementDetail> details(
      ElectronicDrawingExcelParseResult.SourceNode... sources) {
    QuoteBomSupplementDetail root = detail(1, 0, "P-1", "产品", "PD", "MANUFACTURE");
    root.setRemark(metadata.root("formal.xlsx", "HASH", "Sheet"));
    java.util.ArrayList<QuoteBomSupplementDetail> rows = new java.util.ArrayList<>();
    rows.add(root);
    for (int index = 0; index < sources.length; index++) {
      var source = sources[index];
      QuoteBomSupplementDetail row = detail(index + 2, 1,
          index == 0 && sources.length > 1 ? "1001" : "TMP-10-X",
          source.sourceName(), source.drawingCode(), "PURCHASE");
      row.setRemark(metadata.node(source,
          index == 0 && sources.length > 1 ? Status.AUTO_MATCHED : Status.AMBIGUOUS));
      rows.add(row);
    }
    return rows;
  }

  private QuoteBomSupplementDetail detail(
      int line, int level, String code, String name, String drawing, String nature) {
    QuoteBomSupplementDetail row = new QuoteBomSupplementDetail();
    row.setSupplementVersionId(99L);
    row.setLineNo(line);
    row.setLevel(level);
    row.setMaterialCode(code);
    row.setMaterialName(name);
    row.setMaterialSpec("规格");
    row.setMaterialModel("型号");
    row.setDrawingNo(drawing);
    row.setShapeAttr(nature);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setUnit("件");
    row.setSortSeq(line);
    return row;
  }

  private ElectronicDrawingExcelParseResult.SourceNode source(
      String sequence, String parent, String drawing, String name, int row) {
    return new ElectronicDrawingExcelParseResult.SourceNode(sequence, parent, 1, drawing, name,
        "铜", "B", "B", BigDecimal.ONE, new BigDecimal("1.2"), "备注", row);
  }

  private Option option(String code, String drawing, String nature) {
    return new Option(code, "名称" + code, "规格", "型号", drawing, nature, "件",
        "MC", "分类", "DRAWING_NO");
  }

  private MaterialMasterRaw master(
      String code, String name, String spec, String model, String drawing, String nature) {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode(code);
    row.setMaterialName(name);
    row.setMaterialSpec(spec);
    row.setMaterialModel(model);
    row.setDrawingNo(drawing);
    row.setShapeAttr(nature);
    row.setUnit("件");
    return row;
  }
}
