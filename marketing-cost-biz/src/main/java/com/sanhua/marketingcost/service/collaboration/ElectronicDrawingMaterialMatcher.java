package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 在任务指定的 U9 物料组织中，把电子图库图号匹配为正式料号。 */
@Component
public class ElectronicDrawingMaterialMatcher {
  private static final int MAX_QUERY_RESULT = 20_000;
  private final MaterialMasterRawMapper mapper;

  public ElectronicDrawingMaterialMatcher(MaterialMasterRawMapper mapper) {
    this.mapper = mapper;
  }

  public List<Match> match(
      String materialOrganizationCode,
      List<ElectronicDrawingExcelParseResult.SourceNode> sourceNodes) {
    String organization = required(materialOrganizationCode, "物料组织不能为空");
    List<ElectronicDrawingExcelParseResult.SourceNode> nodes =
        sourceNodes == null ? List.of() : sourceNodes;
    Set<String> drawingCodes = nodes.stream()
        .map(ElectronicDrawingExcelParseResult.SourceNode::drawingCode)
        .map(ElectronicDrawingMaterialMatcher::normalize)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    List<MaterialMasterRaw> candidates = drawingCodes.isEmpty() ? List.of()
        : mapper.selectByDrawingIdentities(drawingCodes, null, organization, MAX_QUERY_RESULT);
    Map<String, MaterialMasterRaw> uniqueCandidates = candidates.stream()
        .filter(row -> text(row.getMaterialCode()) != null)
        .collect(Collectors.toMap(row -> text(row.getMaterialCode()), Function.identity(),
            (first, ignored) -> first, LinkedHashMap::new));

    List<Match> matches = new ArrayList<>();
    for (ElectronicDrawingExcelParseResult.SourceNode node : nodes) {
      String identity = normalize(node.drawingCode());
      List<Option> options = options(identity, uniqueCandidates.values());
      Status status = options.isEmpty() ? Status.UNMATCHED
          : options.size() == 1 ? Status.AUTO_MATCHED : Status.AMBIGUOUS;
      matches.add(new Match(node.sourceSequence(), node.sourceRowNumber(), node.drawingCode(),
          node.sourceName(), status,
          status == Status.AUTO_MATCHED ? options.getFirst().materialCode() : null, options));
    }
    return List.copyOf(matches);
  }

  public List<Option> search(
      String materialOrganizationCode, String keyword, int limit) {
    String organization = required(materialOrganizationCode, "物料组织不能为空");
    String query = required(keyword, "请输入料号、品名、规格、型号或图号");
    int safeLimit = Math.max(1, Math.min(limit, 100));
    return mapper.selectOptionsByLatestBatchKeyword(query, null, organization, safeLimit).stream()
        .map(row -> option(row, "SEARCH"))
        .toList();
  }

  private List<Option> options(String drawingCode, Collection<MaterialMasterRaw> candidates) {
    if (drawingCode == null) return List.of();
    List<MaterialMasterRaw> exactDrawing = candidates.stream()
        .filter(row -> drawingCode.equals(normalize(row.getDrawingNo()))).toList();
    if (!exactDrawing.isEmpty()) return toOptions(exactDrawing, "DRAWING_NO");
    List<MaterialMasterRaw> exactSpec = candidates.stream()
        .filter(row -> drawingCode.equals(normalize(row.getMaterialSpec()))).toList();
    if (!exactSpec.isEmpty()) return toOptions(exactSpec, "MATERIAL_SPEC");
    List<MaterialMasterRaw> exactModel = candidates.stream()
        .filter(row -> drawingCode.equals(normalize(row.getMaterialModel()))).toList();
    return toOptions(exactModel, "MATERIAL_MODEL");
  }

  private List<Option> toOptions(List<MaterialMasterRaw> rows, String matchedBy) {
    return rows.stream().map(row -> option(row, matchedBy))
        .sorted(Comparator.comparing(Option::materialCode)).toList();
  }

  private Option option(MaterialMasterRaw row, String matchedBy) {
    return new Option(text(row.getMaterialCode()), text(row.getMaterialName()),
        text(row.getMaterialSpec()), text(row.getMaterialModel()), text(row.getDrawingNo()),
        text(row.getShapeAttr()), text(row.getUnit()), text(row.getMainCategoryCode()),
        text(row.getMainCategoryName()), matchedBy);
  }

  private static String normalize(String value) {
    String text = text(value);
    return text == null ? null : text.toUpperCase(Locale.ROOT);
  }

  private static String required(String value, String message) {
    String result = text(value);
    if (result == null) throw new IllegalArgumentException(message);
    return result;
  }

  private static String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  public enum Status { AUTO_MATCHED, UNMATCHED, AMBIGUOUS, CONFIRMED }

  public record Match(
      String sourceSequence,
      int sourceRowNumber,
      String drawingCode,
      String sourceName,
      Status status,
      String selectedMaterialCode,
      List<Option> options) {
    public Match {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  public record Option(
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String materialNature,
      String unit,
      String mainCategoryCode,
      String mainCategoryName,
      String matchedBy) {}
}
