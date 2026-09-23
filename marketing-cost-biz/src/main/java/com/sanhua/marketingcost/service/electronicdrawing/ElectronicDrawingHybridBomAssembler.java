package com.sanhua.marketingcost.service.electronicdrawing;

import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.BOM_GAP;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.COMMAND_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.MAPPING_INCOMPLETE;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.STRUCTURE_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.U9_QUERY_BLOCKED;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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

/** 电子图库骨架与制造节点 U9 子 BOM 的纯合成领域服务。 */
@Component
public class ElectronicDrawingHybridBomAssembler {
  public static final String SOURCE_ELECTRONIC_DRAWING = "E_DRAWING";
  public static final String SOURCE_U9_EXPANDED = "U9_EXPANDED";
  public static final String MAPPING_U9_EXPANDED = "U9_EXPANDED";
  public static final String SOURCE_TECHNICAL_RAW = "TECH_RAW";
  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final int DIVISION_SCALE = 16;

  private final ElectronicDrawingU9SubBomPort u9SubBomPort;

  public ElectronicDrawingHybridBomAssembler(ElectronicDrawingU9SubBomPort u9SubBomPort) {
    this.u9SubBomPort = u9SubBomPort;
  }

  public HybridBom assemble(AssembleCommand command) {
    ValidCommand valid = validate(command);
    EdTree electronicTree = validateElectronicTree(valid.electronicNodes());
    List<Node> output = new ArrayList<>();
    MaterialSnapshot root = valid.rootMaterial();
    String rootKey = "ROOT:" + root.materialCode();
    // raw_hierarchy / effective BOM 的既有消费链以 /顶层料号/ 识别根节点。
    // nodeKey 继续保留 ROOT: 前缀用于节点身份，结构 path 则遵守全局 BOM 路径契约；
    // U9 子节点使用业务行键作为段值，避免重导入改变路径和组合指纹。
    String rootPath = "/" + root.materialCode() + "/";
    output.add(new Node(
        rootKey, null, 0, root.materialCode(), root.materialName(), root.materialSpec(),
        root.materialModel(), root.drawingNo(), root.shapeAttr(), root.mainCategoryCode(),
        root.sourceCategory(), root.costElementCode(), null, null, ONE, ONE, ONE,
        root.unit(), rootPath, 1, SOURCE_ELECTRONIC_DRAWING, null, null, null, null));

    ComposeStats stats = new ComposeStats();
    Set<String> rootAncestors = new LinkedHashSet<>();
    rootAncestors.add(normalizeCode(root.materialCode()));
    for (ElectronicNode child : electronicTree.roots()) {
      composeElectronic(valid, electronicTree, child, rootKey, rootPath, ONE,
          rootAncestors, output, stats);
    }
    validateFinalTree(output);
    String fingerprint = fingerprint(output);
    return new HybridBom(
        List.copyOf(output), fingerprint, stats.u9PurchaseLeaves,
        stats.electronicPurchaseLeaves, stats.replacedElectronicDescendants,
        stats.u9QueryCount);
  }

  private void composeElectronic(
      ValidCommand command,
      EdTree tree,
      ElectronicNode source,
      String parentKey,
      String parentPath,
      BigDecimal parentQtyToTop,
      Set<String> ancestorMaterialCodes,
      List<Node> output,
      ComposeStats stats) {
    MaterialSnapshot material = source.material();
    Nature nature = Nature.parse(material.shapeAttr());
    String normalizedCode = normalizeCode(material.materialCode());
    if (ancestorMaterialCodes.contains(normalizedCode)) {
      throw invalid(STRUCTURE_INVALID,
          "电子图库分支存在循环料号：" + material.materialCode());
    }
    BigDecimal quantity = positive(source.quantity(), "电子图库节点数量");
    BigDecimal toTop = parentQtyToTop.multiply(quantity);
    String nodeKey = "ED:" + source.sourceNodeId();
    String path = checkedPath(parentPath + nodeKey + "/");
    List<ElectronicNode> children = tree.children().getOrDefault(source.sourceSequence(), List.of());
    if (nature == Nature.PURCHASE && !children.isEmpty()) {
      throw invalid(STRUCTURE_INVALID,
          "采购件不能保留电子图库下级：" + material.materialCode());
    }
    output.add(new Node(
        nodeKey, parentKey, level(path), material.materialCode(), material.materialName(),
        material.materialSpec(), material.materialModel(), source.drawingCode(),
        material.shapeAttr(), material.mainCategoryCode(), material.sourceCategory(),
        material.costElementCode(), null, null, quantity, toTop, ONE,
        required(material.unit(), "电子图库节点单位"), path, source.sourceRowNo(),
        SOURCE_ELECTRONIC_DRAWING, source.sourceNodeId(), null, null, source.matchStatus()));
    if (nature == Nature.PURCHASE) {
      stats.electronicPurchaseLeaves++;
      return;
    }

    ElectronicDrawingU9SubBomPort.SubBomQuery query =
        new ElectronicDrawingU9SubBomPort.SubBomQuery(
            command.oaNo(), command.oaFormItemId(), material.materialCode(),
            command.periodMonth(), command.priceOrgCode(), command.materialOrganizationCode(),
            command.businessUnitType(), command.bomPurpose(), command.effectiveDate());
    ElectronicDrawingU9SubBomPort.SubBomResult result = u9SubBomPort.query(query);
    stats.u9QueryCount++;
    if (result == null || result.status() == null) {
      throw invalid(U9_QUERY_BLOCKED,
          "制造节点 U9 子 BOM 查询返回空结果：" + material.materialCode());
    }
    switch (result.status()) {
      case AVAILABLE -> {
        validateAvailableResult(command, material.materialCode(), result);
        Set<String> nextAncestors = withAncestor(ancestorMaterialCodes, normalizedCode);
        attachU9Tree(result.nodes(), source, nodeKey, path, toTop,
            nextAncestors, output, stats);
        stats.replacedElectronicDescendants += countDescendants(source.sourceSequence(), tree.children());
      }
      case NOT_FOUND -> {
        if (children.isEmpty()) {
          ManufacturingRawNode raw = command.manufacturingRawNodes().get(source.sourceNodeId());
          if (nature == Nature.MANUFACTURE && raw != null) {
            attachTechnicalRaw(raw, source, nodeKey, path, toTop, withAncestor(ancestorMaterialCodes, normalizedCode), output);
            return;
          }
          throw invalid(BOM_GAP,
              "制造/委外/虚拟件既无 U9 子 BOM，也无电子图库下级：" + material.materialCode());
        }
        Set<String> nextAncestors = withAncestor(ancestorMaterialCodes, normalizedCode);
        for (ElectronicNode child : children) {
          composeElectronic(command, tree, child, nodeKey, path, toTop,
              nextAncestors, output, stats);
        }
      }
      case MULTIPLE -> throw invalid(U9_QUERY_BLOCKED,
          "制造节点存在多个当前有效 U9 子 BOM：" + material.materialCode());
      case ORGANIZATION_MISMATCH -> throw invalid(U9_QUERY_BLOCKED,
          "制造节点 U9 子 BOM 组织不一致：" + material.materialCode());
      case TIMEOUT, ERROR -> throw invalid(U9_QUERY_BLOCKED,
          "制造节点 U9 子 BOM 查询失败，不能降级使用电子图库分支："
              + material.materialCode());
    }
  }

  private void attachTechnicalRaw(ManufacturingRawNode source, ElectronicNode parent, String parentKey,
      String parentPath, BigDecimal parentQtyToTop, Set<String> ancestors, List<Node> output) {
    MaterialSnapshot material = source.material();
    if (ancestors.contains(normalizeCode(material.materialCode()))) throw invalid(STRUCTURE_INVALID, "补录原材料不能形成循环料号");
    String key = "TECH:" + source.technicalVersionId() + ":" + source.itemKey();
    String path = checkedPath(parentPath + key + "/");
    output.add(new Node(key, parentKey, level(path), material.materialCode(), material.materialName(),
        material.materialSpec(), material.materialModel(), material.drawingNo(), material.shapeAttr(),
        material.mainCategoryCode(), material.sourceCategory(), material.costElementCode(), null, null,
        source.quantityPerParent(), parentQtyToTop.multiply(source.quantityPerParent()), ONE,
        material.unit(), path, 1, SOURCE_TECHNICAL_RAW, parent.sourceNodeId(), null, null, SOURCE_TECHNICAL_RAW));
  }

  private void attachU9Tree(
      List<ElectronicDrawingU9SubBomPort.U9Node> sourceNodes,
      ElectronicNode anchor,
      String anchorKey,
      String anchorPath,
      BigDecimal anchorQtyToTop,
      Set<String> ancestorMaterialCodes,
      List<Node> output,
      ComposeStats stats) {
    U9Tree tree = validateU9Tree(sourceNodes);
    for (ElectronicDrawingU9SubBomPort.U9Node root : tree.roots()) {
      attachU9Node(tree, root, anchor, anchorKey, anchorPath, anchorQtyToTop,
          ancestorMaterialCodes, output, stats);
    }
  }

  private void attachU9Node(
      U9Tree tree,
      ElectronicDrawingU9SubBomPort.U9Node source,
      ElectronicNode anchor,
      String parentKey,
      String parentPath,
      BigDecimal parentQtyToTop,
      Set<String> ancestorMaterialCodes,
      List<Node> output,
      ComposeStats stats) {
    String code = required(source.materialCode(), "U9 子 BOM 料号");
    String normalizedCode = normalizeCode(code);
    if (ancestorMaterialCodes.contains(normalizedCode)) {
      throw invalid(STRUCTURE_INVALID, "U9 子 BOM 存在循环料号：" + code);
    }
    BigDecimal sourceQty = positive(source.qtyPerParent(), "U9 子 BOM 数量");
    BigDecimal baseQty = source.parentBaseQty() == null
        ? ONE : positive(source.parentBaseQty(), "U9 子 BOM 母件底数");
    BigDecimal relative = sourceQty.divide(baseQty, DIVISION_SCALE, RoundingMode.HALF_UP)
        .stripTrailingZeros();
    BigDecimal toTop = parentQtyToTop.multiply(relative);
    String sourceIdentity = sourceIdentity(source);
    String nodeKey = "ED:" + anchor.sourceNodeId() + "/" + sourceIdentity;
    String path = checkedPath(parentPath + sourceIdentity + "/");
    List<ElectronicDrawingU9SubBomPort.U9Node> children =
        tree.children().getOrDefault(source.nodeKey(), List.of());
    Nature nature = Nature.parse(source.shapeAttr());
    if (nature == Nature.PURCHASE && !children.isEmpty()) {
      throw invalid(STRUCTURE_INVALID, "U9 采购件不能有子级：" + code);
    }
    if (nature != Nature.PURCHASE && children.isEmpty()) {
      throw invalid(BOM_GAP, "U9 制造/委外/虚拟件没有子级：" + code);
    }
    output.add(new Node(
        nodeKey, parentKey, level(path), code, text(source.materialName()),
        text(source.materialSpec()), text(source.materialModel()), anchor.drawingCode(),
        required(source.shapeAttr(), "U9 子 BOM 物料形态"), text(source.mainCategoryCode()),
        text(source.sourceCategory()), text(source.costElementCode()),
        text(source.bomPurpose()), text(source.bomVersion()), sourceQty, toTop, baseQty,
        required(source.unit(), "U9 子 BOM 单位"), path,
        source.sortSeq() == null ? Integer.MAX_VALUE : source.sortSeq(),
        SOURCE_U9_EXPANDED, anchor.sourceNodeId(), source.sourceRawHierarchyId(),
        source.sourceU9BomId(), MAPPING_U9_EXPANDED));
    if (nature == Nature.PURCHASE) {
      stats.u9PurchaseLeaves++;
      return;
    }
    Set<String> nextAncestors = withAncestor(ancestorMaterialCodes, normalizedCode);
    for (ElectronicDrawingU9SubBomPort.U9Node child : children) {
      attachU9Node(tree, child, anchor, nodeKey, path, toTop,
          nextAncestors, output, stats);
    }
  }

  private ValidCommand validate(AssembleCommand command) {
    if (command == null || command.oaFormItemId() == null || command.oaFormItemId() <= 0) {
      throw invalid(COMMAND_INVALID, "报价产品行不能为空");
    }
    MaterialSnapshot root = validateMaterial(command.rootMaterial(), "顶层产品");
    Nature rootNature = Nature.parse(root.shapeAttr());
    if (rootNature == Nature.PURCHASE) {
      throw invalid(STRUCTURE_INVALID, "电子图库顶层产品不能是采购件");
    }
    List<ElectronicNode> nodes = command.electronicNodes() == null
        ? List.of() : List.copyOf(command.electronicNodes());
    if (nodes.isEmpty()) throw invalid(COMMAND_INVALID, "电子图库源节点不能为空");
    for (ElectronicNode node : nodes) {
      if (node == null || node.sourceNodeId() == null || node.sourceNodeId() <= 0
          || node.sourceRowNo() == null || node.sourceRowNo() <= 0
          || text(node.sourceSequence()) == null || text(node.drawingCode()) == null) {
        throw invalid(COMMAND_INVALID, "电子图库源节点身份、源行和图号不能为空");
      }
      if (!ElectronicDrawingSourceNode.MATCH_AUTO.equals(node.matchStatus())
          && !ElectronicDrawingSourceNode.MATCH_MANUAL.equals(node.matchStatus())) {
        throw invalid(MAPPING_INCOMPLETE,
            "电子图库仍有未完成料号映射：" + node.drawingCode());
      }
      validateMaterial(node.material(), "电子图库节点");
      positive(node.quantity(), "电子图库节点数量");
    }
    Map<Long, ManufacturingRawNode> rawByParent = new LinkedHashMap<>();
    for (var raw : command.manufacturingRawNodes()) {
      if (raw == null || raw.technicalVersionId() == null || raw.technicalVersionId() <= 0
          || text(raw.itemKey()) == null || raw.itemKey().contains("/") || raw.parentSourceNodeId() == null
          || rawByParent.putIfAbsent(raw.parentSourceNodeId(), raw) != null) {
        throw invalid(COMMAND_INVALID, "补录原材料缺少稳定身份或同一制造件存在多条原料");
      }
      var parent = nodes.stream().filter(node -> Objects.equals(node.sourceNodeId(), raw.parentSourceNodeId())).findFirst()
          .orElseThrow(() -> invalid(STRUCTURE_INVALID, "补录原材料不属于当前图库源节点"));
      if (!same(parent.material().materialCode(), raw.parentMaterialCode())) throw invalid(STRUCTURE_INVALID, "补录制造件料号已变化");
      validateMaterial(raw.material(), "补录原材料");
      if (Nature.parse(raw.material().shapeAttr()) != Nature.PURCHASE) throw invalid(STRUCTURE_INVALID, "补录原材料必须为采购件");
      positive(raw.quantityPerParent(), "补录原材料用量");
    }
    return new ValidCommand(
        required(command.oaNo(), "报价单号"), command.oaFormItemId(), root,
        required(command.periodMonth(), "核算月份"), required(command.priceOrgCode(), "报价组织"),
        required(command.materialOrganizationCode(), "物料组织"),
        required(command.businessUnitType(), "业务单元"), text(command.bomPurpose()),
        Objects.requireNonNull(command.effectiveDate(), "BOM 生效日期不能为空"), nodes, Map.copyOf(rawByParent));
  }

  private EdTree validateElectronicTree(List<ElectronicNode> nodes) {
    Map<Long, ElectronicNode> byId = new LinkedHashMap<>();
    Map<String, ElectronicNode> bySequence = new LinkedHashMap<>();
    for (ElectronicNode node : nodes) {
      if (byId.put(node.sourceNodeId(), node) != null) {
        throw invalid(STRUCTURE_INVALID, "电子图库存在重复源节点ID：" + node.sourceNodeId());
      }
      if (bySequence.put(node.sourceSequence(), node) != null) {
        throw invalid(STRUCTURE_INVALID, "电子图库存在重复序号：" + node.sourceSequence());
      }
    }
    Map<String, List<ElectronicNode>> children = new LinkedHashMap<>();
    List<ElectronicNode> roots = new ArrayList<>();
    for (ElectronicNode node : nodes) {
      String parent = text(node.parentSourceSequence());
      if (parent == null) {
        roots.add(node);
      } else {
        if (!bySequence.containsKey(parent)) {
          throw invalid(STRUCTURE_INVALID, "电子图库存在孤儿节点：" + node.sourceSequence());
        }
        children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node);
      }
    }
    if (roots.isEmpty()) throw invalid(STRUCTURE_INVALID, "电子图库没有一级节点");
    Comparator<ElectronicNode> comparator = Comparator
        .comparing(ElectronicNode::sourceRowNo)
        .thenComparing(ElectronicNode::sourceSequence);
    roots.sort(comparator);
    children.values().forEach(rows -> rows.sort(comparator));
    Set<String> visited = new HashSet<>();
    Set<String> visiting = new HashSet<>();
    for (ElectronicNode root : roots) detectEdCycle(root, children, visiting, visited);
    if (visited.size() != nodes.size()) {
      throw invalid(STRUCTURE_INVALID, "电子图库存在循环或未连通节点");
    }
    return new EdTree(List.copyOf(roots), immutableChildren(children));
  }

  private U9Tree validateU9Tree(List<ElectronicDrawingU9SubBomPort.U9Node> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      throw invalid(BOM_GAP, "U9 返回 AVAILABLE 但子 BOM 为空");
    }
    Map<String, ElectronicDrawingU9SubBomPort.U9Node> byKey = new LinkedHashMap<>();
    Set<String> sourceIdentities = new HashSet<>();
    for (ElectronicDrawingU9SubBomPort.U9Node node : nodes) {
      if (node == null || text(node.nodeKey()) == null) {
        throw invalid(STRUCTURE_INVALID, "U9 子 BOM 缺少稳定节点键");
      }
      String sourceIdentity = sourceIdentity(node);
      if (byKey.put(node.nodeKey(), node) != null || !sourceIdentities.add(sourceIdentity)) {
        throw invalid(STRUCTURE_INVALID, "U9 子 BOM 存在重复节点");
      }
      positive(node.qtyPerParent(), "U9 子 BOM 数量");
      if (node.parentBaseQty() != null) positive(node.parentBaseQty(), "U9 子 BOM 母件底数");
      required(node.unit(), "U9 子 BOM 单位");
      Nature.parse(node.shapeAttr());
    }
    Map<String, List<ElectronicDrawingU9SubBomPort.U9Node>> children = new LinkedHashMap<>();
    List<ElectronicDrawingU9SubBomPort.U9Node> roots = new ArrayList<>();
    for (ElectronicDrawingU9SubBomPort.U9Node node : nodes) {
      String parent = text(node.parentNodeKey());
      if (parent == null) {
        roots.add(node);
      } else {
        if (!byKey.containsKey(parent)) {
          throw invalid(STRUCTURE_INVALID, "U9 子 BOM 存在孤儿节点：" + node.nodeKey());
        }
        children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node);
      }
    }
    if (roots.isEmpty()) throw invalid(STRUCTURE_INVALID, "U9 子 BOM 没有直接子件");
    Comparator<ElectronicDrawingU9SubBomPort.U9Node> comparator = Comparator
        .comparing((ElectronicDrawingU9SubBomPort.U9Node node) ->
            node.sortSeq() == null ? Integer.MAX_VALUE : node.sortSeq())
        .thenComparing(ElectronicDrawingU9SubBomPort.U9Node::nodeKey);
    roots.sort(comparator);
    children.values().forEach(rows -> rows.sort(comparator));
    Set<String> visited = new HashSet<>();
    Set<String> visiting = new HashSet<>();
    for (ElectronicDrawingU9SubBomPort.U9Node root : roots) {
      detectU9Cycle(root, children, visiting, visited);
    }
    if (visited.size() != nodes.size()) {
      throw invalid(STRUCTURE_INVALID, "U9 子 BOM 存在循环或未连通节点");
    }
    for (var entry : children.entrySet()) {
      Set<String> childCodes = new HashSet<>();
      for (ElectronicDrawingU9SubBomPort.U9Node child : entry.getValue()) {
        if (!childCodes.add(normalizeCode(child.materialCode()))) {
          throw invalid(STRUCTURE_INVALID, "U9 同一父件下存在重复物料：" + child.materialCode());
        }
      }
    }
    return new U9Tree(List.copyOf(roots), immutableU9Children(children));
  }

  private static String sourceIdentity(ElectronicDrawingU9SubBomPort.U9Node node) {
    String key = node.nodeKey();
    if (key != null && !key.isBlank() && !key.contains("/")) {
      return key;
    }
    throw invalid(STRUCTURE_INVALID, "U9 子 BOM 缺少稳定业务行键");
  }

  private void validateAvailableResult(
      ValidCommand command,
      String materialCode,
      ElectronicDrawingU9SubBomPort.SubBomResult result) {
    if (!same(materialCode, result.parentMaterialCode())) {
      throw invalid(U9_QUERY_BLOCKED, "U9 子 BOM 返回了其他父件");
    }
    if (!same(command.priceOrgCode(), result.priceOrgCode())
        || !same(command.materialOrganizationCode(), result.materialOrganizationCode())) {
      throw invalid(U9_QUERY_BLOCKED, "U9 子 BOM 返回组织与任务不一致");
    }
  }

  private void validateFinalTree(List<Node> nodes) {
    Map<String, Node> byKey = nodes.stream().collect(Collectors.toMap(Node::nodeKey,
        Function.identity(), (first, ignored) -> {
          throw invalid(STRUCTURE_INVALID, "混合 BOM 存在重复节点键");
        }, LinkedHashMap::new));
    Map<String, List<Node>> children = nodes.stream().filter(node -> node.parentNodeKey() != null)
        .collect(Collectors.groupingBy(Node::parentNodeKey));
    for (Node node : nodes) {
      if (node.parentNodeKey() != null && !byKey.containsKey(node.parentNodeKey())) {
        throw invalid(STRUCTURE_INVALID, "混合 BOM 存在孤儿节点：" + node.nodeKey());
      }
      List<Node> childRows = children.getOrDefault(node.nodeKey(), List.of());
      Nature nature = Nature.parse(node.shapeAttr());
      if (node.level() > 0 && nature == Nature.PURCHASE && !childRows.isEmpty()) {
        throw invalid(STRUCTURE_INVALID, "混合 BOM 采购件存在子级：" + node.materialCode());
      }
      if (nature != Nature.PURCHASE && childRows.isEmpty()) {
        throw invalid(BOM_GAP, "混合 BOM 制造/委外/虚拟件没有子级：" + node.materialCode());
      }
      positive(node.qtyPerParent(), "混合 BOM 相对用量");
      positive(node.qtyPerTop(), "混合 BOM 累计用量");
      required(node.unit(), "混合 BOM 单位");
    }
    // 同一父件的不同来源节点可以使用同一料号，数量和路径各自保留。
    // 重复由 nodeKey 校验，循环由祖先料号校验，不能按兄弟料号去重。
  }

  private static MaterialSnapshot validateMaterial(MaterialSnapshot material, String label) {
    if (material == null) throw invalid(COMMAND_INVALID, label + "料品档案不能为空");
    MaterialSnapshot normalized = new MaterialSnapshot(
        required(material.materialCode(), label + "料号"), text(material.materialName()),
        text(material.materialSpec()), text(material.materialModel()), text(material.drawingNo()),
        required(material.shapeAttr(), label + "物料形态"), text(material.mainCategoryCode()),
        text(material.sourceCategory()), text(material.costElementCode()),
        required(material.unit(), label + "单位"));
    Nature.parse(normalized.shapeAttr());
    return normalized;
  }

  private static void detectEdCycle(
      ElectronicNode node,
      Map<String, List<ElectronicNode>> children,
      Set<String> visiting,
      Set<String> visited) {
    if (!visiting.add(node.sourceSequence())) {
      throw invalid(STRUCTURE_INVALID, "电子图库存在循环序号：" + node.sourceSequence());
    }
    for (ElectronicNode child : children.getOrDefault(node.sourceSequence(), List.of())) {
      detectEdCycle(child, children, visiting, visited);
    }
    visiting.remove(node.sourceSequence());
    visited.add(node.sourceSequence());
  }

  private static void detectU9Cycle(
      ElectronicDrawingU9SubBomPort.U9Node node,
      Map<String, List<ElectronicDrawingU9SubBomPort.U9Node>> children,
      Set<String> visiting,
      Set<String> visited) {
    if (!visiting.add(node.nodeKey())) {
      throw invalid(STRUCTURE_INVALID, "U9 子 BOM 存在循环节点：" + node.nodeKey());
    }
    for (ElectronicDrawingU9SubBomPort.U9Node child
        : children.getOrDefault(node.nodeKey(), List.of())) {
      detectU9Cycle(child, children, visiting, visited);
    }
    visiting.remove(node.nodeKey());
    visited.add(node.nodeKey());
  }

  private static int countDescendants(
      String sequence, Map<String, List<ElectronicNode>> children) {
    int count = 0;
    Deque<ElectronicNode> queue = new ArrayDeque<>(children.getOrDefault(sequence, List.of()));
    while (!queue.isEmpty()) {
      ElectronicNode node = queue.removeFirst();
      count++;
      queue.addAll(children.getOrDefault(node.sourceSequence(), List.of()));
    }
    return count;
  }

  private static Set<String> withAncestor(Set<String> ancestors, String code) {
    Set<String> result = new LinkedHashSet<>(ancestors);
    result.add(code);
    return result;
  }

  private static Map<String, List<ElectronicNode>> immutableChildren(
      Map<String, List<ElectronicNode>> source) {
    Map<String, List<ElectronicNode>> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, List.copyOf(value)));
    return Map.copyOf(result);
  }

  private static Map<String, List<ElectronicDrawingU9SubBomPort.U9Node>> immutableU9Children(
      Map<String, List<ElectronicDrawingU9SubBomPort.U9Node>> source) {
    Map<String, List<ElectronicDrawingU9SubBomPort.U9Node>> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, List.copyOf(value)));
    return Map.copyOf(result);
  }

  private static String fingerprint(List<Node> nodes) {
    String canonical = nodes.stream().map(node -> String.join("|",
        node.nodeKey(), nullText(node.parentNodeKey()), String.valueOf(node.level()),
        node.materialCode(), nullText(node.materialName()), nullText(node.materialSpec()),
        nullText(node.materialModel()), nullText(node.drawingNo()), node.shapeAttr(),
        nullText(node.mainCategoryCode()), nullText(node.sourceCategory()),
        nullText(node.costElementCode()), nullText(node.bomPurpose()), nullText(node.bomVersion()),
        decimal(node.qtyPerParent()), decimal(node.qtyPerTop()), decimal(node.parentBaseQty()),
        node.unit(), node.path(), String.valueOf(node.sortSeq()), node.nodeSourceType(),
        String.valueOf(node.sourceElectronicNodeId()),
        nullText(node.mappingStatus()))).collect(Collectors.joining("\n"));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 不支持 SHA-256", exception);
    }
  }

  private static String checkedPath(String path) {
    if (path.length() > 1024) throw invalid(STRUCTURE_INVALID, "混合 BOM 路径超过 1024 字符");
    return path;
  }

  private static int level(String path) {
    int separators = 0;
    for (int index = 0; index < path.length(); index++) {
      if (path.charAt(index) == '/') separators++;
    }
    return Math.max(0, separators - 2);
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw invalid(STRUCTURE_INVALID, label + "必须大于0");
    }
    return value.stripTrailingZeros();
  }

  private static boolean same(String left, String right) {
    String a = text(left);
    String b = text(right);
    return a != null && b != null && a.equalsIgnoreCase(b);
  }

  private static String required(String value, String label) {
    String normalized = text(value);
    if (normalized == null) throw invalid(COMMAND_INVALID, label + "不能为空");
    return normalized;
  }

  private static String normalizeCode(String value) {
    return required(value, "料号").toUpperCase(Locale.ROOT);
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullText(String value) {
    return value == null ? "" : value;
  }

  private static String decimal(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static ElectronicDrawingHybridBomException invalid(String code, String message) {
    return new ElectronicDrawingHybridBomValidationException(code, message);
  }

  public enum Nature {
    PURCHASE,
    MANUFACTURE,
    OUTSOURCE,
    VIRTUAL;

    public static Nature parse(String value) {
      String normalized = required(value, "物料形态");
      String upper = normalized.toUpperCase(Locale.ROOT);
      if (upper.contains("PURCHASE") || normalized.contains("采购")) return PURCHASE;
      if (upper.contains("OUTSOURCE") || normalized.contains("委外")) return OUTSOURCE;
      if (upper.contains("VIRTUAL") || upper.contains("PACKAGE")
          || normalized.contains("虚拟") || normalized.contains("包装")) return VIRTUAL;
      if (upper.contains("MANUFACT") || normalized.contains("制造")
          || normalized.contains("自制")) return MANUFACTURE;
      throw invalid(STRUCTURE_INVALID, "无法识别物料形态：" + normalized);
    }
  }

  public record AssembleCommand(
      String oaNo,
      Long oaFormItemId,
      MaterialSnapshot rootMaterial,
      String periodMonth,
      String priceOrgCode,
      String materialOrganizationCode,
      String businessUnitType,
      String bomPurpose,
      LocalDate effectiveDate,
      List<ElectronicNode> electronicNodes,
      List<ManufacturingRawNode> manufacturingRawNodes) {

    public AssembleCommand {
      electronicNodes = electronicNodes == null ? List.of() : List.copyOf(electronicNodes);
      manufacturingRawNodes = manufacturingRawNodes == null ? List.of() : List.copyOf(manufacturingRawNodes);
    }
  }

  public record ManufacturingRawNode(Long technicalVersionId, String itemKey, Long parentSourceNodeId,
      String parentMaterialCode, MaterialSnapshot material, BigDecimal quantityPerParent) {}

  public record ElectronicNode(
      Long sourceNodeId,
      Integer sourceRowNo,
      String sourceSequence,
      String parentSourceSequence,
      String drawingCode,
      String sourceName,
      BigDecimal quantity,
      String matchStatus,
      MaterialSnapshot material) {}

  public record MaterialSnapshot(
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String shapeAttr,
      String mainCategoryCode,
      String sourceCategory,
      String costElementCode,
      String unit) {}

  public record Node(
      String nodeKey,
      String parentNodeKey,
      int level,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String shapeAttr,
      String mainCategoryCode,
      String sourceCategory,
      String costElementCode,
      String bomPurpose,
      String bomVersion,
      BigDecimal qtyPerParent,
      BigDecimal qtyPerTop,
      BigDecimal parentBaseQty,
      String unit,
      String path,
      Integer sortSeq,
      String nodeSourceType,
      Long sourceElectronicNodeId,
      Long sourceRawHierarchyId,
      Long sourceU9BomId,
      String mappingStatus) {}

  public record HybridBom(
      List<Node> nodes,
      String compositionFingerprint,
      int u9PurchaseLeafCount,
      int electronicDrawingPurchaseLeafCount,
      int replacedElectronicDescendantCount,
      int u9QueryCount) {

    public HybridBom {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public int quotationLeafCount() {
      return (int) nodes.stream().filter(node -> Nature.parse(node.shapeAttr()) == Nature.PURCHASE).count();
    }
  }

  private record ValidCommand(
      String oaNo,
      Long oaFormItemId,
      MaterialSnapshot rootMaterial,
      String periodMonth,
      String priceOrgCode,
      String materialOrganizationCode,
      String businessUnitType,
      String bomPurpose,
      LocalDate effectiveDate,
      List<ElectronicNode> electronicNodes,
      Map<Long, ManufacturingRawNode> manufacturingRawNodes) {}

  private record EdTree(
      List<ElectronicNode> roots, Map<String, List<ElectronicNode>> children) {}

  private record U9Tree(
      List<ElectronicDrawingU9SubBomPort.U9Node> roots,
      Map<String, List<ElectronicDrawingU9SubBomPort.U9Node>> children) {}

  private static final class ComposeStats {
    private int u9PurchaseLeaves;
    private int electronicPurchaseLeaves;
    private int replacedElectronicDescendants;
    private int u9QueryCount;
  }
}
