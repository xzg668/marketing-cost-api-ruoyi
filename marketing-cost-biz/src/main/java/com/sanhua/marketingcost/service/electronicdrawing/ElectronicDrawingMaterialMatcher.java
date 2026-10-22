package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 在任务指定的 U9 物料组织中，把电子图库图号匹配为正式料号。 */
@Component
public class ElectronicDrawingMaterialMatcher {
  private final MaterialMasterRawMapper mapper;

  public ElectronicDrawingMaterialMatcher(MaterialMasterRawMapper mapper) {
    this.mapper = mapper;
  }

  public List<Match> match(String materialOrganizationCode, List<Input> sourceNodes) {
    String organization = required(materialOrganizationCode, "物料组织不能为空");
    List<Input> nodes = sourceNodes == null ? List.of() : sourceNodes;
    Set<String> drawingCodes =
        nodes.stream()
            .map(Input::drawingCode)
            .map(ElectronicDrawingMaterialMatcher::normalize)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<MaterialMasterRaw> candidates =
        drawingCodes.isEmpty()
            ? List.of()
            : mapper.selectByDrawingNumbers(drawingCodes, organization);
    Map<String, List<MaterialMasterRaw>> byDrawing =
        candidates.stream()
            .filter(
                row -> text(row.getMaterialCode()) != null && normalize(row.getDrawingNo()) != null)
            .collect(Collectors.groupingBy(row -> normalize(row.getDrawingNo())));

    List<Match> matches = new ArrayList<>();
    for (Input node : nodes) {
      String identity = normalize(node.drawingCode());
      List<Option> options =
          identity == null
              ? List.of()
              : toOptions(byDrawing.getOrDefault(identity, List.of()), "DRAWING_NO");
      Status status =
          options.isEmpty()
              ? Status.UNMATCHED
              : options.size() == 1 ? Status.AUTO_MATCHED : Status.AMBIGUOUS;
      matches.add(
          new Match(
              node.sourceSequence(),
              node.sourceRowNumber(),
              node.drawingCode(),
              node.sourceName(),
              status,
              status == Status.AUTO_MATCHED ? options.getFirst().materialCode() : null,
              options));
    }
    return List.copyOf(matches);
  }

  private List<Option> toOptions(List<MaterialMasterRaw> rows, String matchedBy) {
    return rows.stream()
        .map(row -> option(row, matchedBy))
        .sorted(Comparator.comparing(Option::materialCode))
        .toList();
  }

  private Option option(MaterialMasterRaw row, String matchedBy) {
    return new Option(
        text(row.getMaterialCode()),
        text(row.getMaterialName()),
        text(row.getMaterialSpec()),
        text(row.getMaterialModel()),
        text(row.getDrawingNo()),
        text(row.getShapeAttr()),
        text(row.getUnit()),
        text(row.getMainCategoryCode()),
        text(row.getMainCategoryName()),
        matchedBy);
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

  /** 匹配只读行身份与图号，不依赖 Excel 或 PDF 协议，也不合并重复图号行。 */
  public record Input(
      String sourceSequence, int sourceRowNumber, String drawingCode, String sourceName) {}

  public enum Status {
    AUTO_MATCHED,
    UNMATCHED,
    AMBIGUOUS,
    CONFIRMED
  }

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
