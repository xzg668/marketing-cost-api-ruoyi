package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.dto.BuildHierarchyResult;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomHierarchyBuildService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * U9 源的层级构建器 —— BOM 三层架构阶段 B 的唯一实现（本期仅 U9，手工 / 电子图库推迟）。
 *
 * <p>算法：
 * <ol>
 *   <li>按 importBatchId + bomPurpose 过滤 lp_bom_u9_source，一次性加载到内存</li>
 *   <li>按 (parent_material_no, bom_purpose, effective_from) 分组作为邻接表</li>
 *   <li>识别顶层：作为 parent 但不作为任何 child 的料号集合</li>
 *   <li>对每个顶层做 DFS：递归算 path / level / qty_per_top / is_leaf；环检测用
 *       LinkedHashSet visitingPath（保留顺序打错误消息）</li>
 *   <li>批量 {@code ON DUPLICATE KEY UPDATE} 写 lp_bom_raw_hierarchy（永不 DELETE 历史）</li>
 * </ol>
 *
 * <p>不下游：不打 is_costing_row / subtree_cost_required，不应用过滤规则（那是 T5 的事）。
 */
@Service("u9SourceBuilder")
public class U9SourceBuilder implements BomHierarchyBuildService {

  private static final Logger log = LoggerFactory.getLogger(U9SourceBuilder.class);

  /** batch upsert 单次批次大小，防止 SQL 超 max_allowed_packet */
  private static final int UPSERT_BATCH_SIZE = 500;

  /**
   * 当 U9 给出的 {@code effective_from = NULL} 时用于填充的占位日期。
   *
   * <p>DDL 里 effective_from 列允许 NULL，但它是 UK 的一部分；MySQL 允许多行 NULL 共存
   * 但那会让幂等重跑生成重复历史行。用 1970-01-01 作为"远古起点"占位以保证 UK 语义。
   *
   * <p>T4 摸底确认真实 34 万行里 effective_from 全部有值（null=0），这里代码留作兜底；
   * 若业务将来出现 NULL，Handoff Note 里已提示需要让业务侧确认是否改用 1900-01-01。
   */
  private static final LocalDate EPOCH_PLACEHOLDER = LocalDate.of(1970, 1, 1);

  private final BomU9SourceMapper bomU9SourceMapper;
  private final BomRawHierarchyMapper bomRawHierarchyMapper;

  public U9SourceBuilder(
      BomU9SourceMapper bomU9SourceMapper, BomRawHierarchyMapper bomRawHierarchyMapper) {
    this.bomU9SourceMapper = bomU9SourceMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
  }

  // ============================ public API ============================

  @Override
  @Transactional(rollbackFor = Exception.class)
  public BuildHierarchyResult build(BuildHierarchyRequest request) {
    if (request == null || !StringUtils.hasText(request.getImportBatchId())) {
      throw new IllegalArgumentException("importBatchId 必填");
    }
    String mode = StringUtils.hasText(request.getMode()) ? request.getMode().toUpperCase() : "ALL";
    if ("BY_PRODUCT".equals(mode) && !StringUtils.hasText(request.getTopProductCode())) {
      throw new IllegalArgumentException("mode=BY_PRODUCT 时 topProductCode 必填");
    }

    BuildHierarchyResult result = new BuildHierarchyResult();
    String buildBatchId = generateBuildBatchId();
    result.setBuildBatchId(buildBatchId);

    // 1) 加载该 importBatch 下所有 U9 行（按可选 bomPurpose 过滤）
    List<BomU9Source> allRows = loadSourceRows(request.getImportBatchId(), request.getBomPurpose());
    if (allRows.isEmpty()) {
      log.warn("build-hierarchy 无 u9_source 行：importBatch={} purpose={}",
          request.getImportBatchId(), request.getBomPurpose());
      return result;
    }

    // 2) 识别本批次的顶层集合（parents - children）
    Set<String> topCandidates = collectTopProducts(allRows);
    List<String> targets;
    if ("BY_PRODUCT".equals(mode)) {
      String top = request.getTopProductCode();
      if (!topCandidates.contains(top)) {
        throw new IllegalArgumentException(
            "顶层产品 " + top + " 不在本批次里（或它作为子件出现在其他产品下）");
      }
      targets = List.of(top);
    } else {
      targets = new ArrayList<>(topCandidates);
    }

    // 3) 按 (bomPurpose, effectiveFrom) 分组构建多版本；每组独立 DFS
    //    UK 包含 bom_purpose 和 effective_from，不同 (purpose, from) 之间完全隔离
    LocalDateTime builtAt = LocalDateTime.now();
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();
    int totalWritten = 0;
    int successProducts = 0;

    Map<GroupKey, List<BomU9Source>> byGroup =
        allRows.stream().collect(Collectors.groupingBy(U9SourceBuilder::groupKeyOf));

    for (String topCode : targets) {
      boolean anyGroupSucceeded = false;
      for (Map.Entry<GroupKey, List<BomU9Source>> grp : byGroup.entrySet()) {
        List<BomU9Source> rowsInGroup = grp.getValue();
        // 跳过本组里不包含 topCode 作为 parent 的场景
        boolean topInGroup = rowsInGroup.stream()
            .anyMatch(r -> topCode.equals(r.getParentMaterialNo()));
        if (!topInGroup) {
          continue;
        }

        try {
          List<BomRawHierarchy> produced = buildOneGroup(
              topCode, grp.getKey(), rowsInGroup, request.getImportBatchId(),
              buildBatchId, builtAt, buType);
          int upserted = writeInBatches(produced);
          totalWritten += upserted;
          anyGroupSucceeded = true;
        } catch (CycleDetectedException e) {
          log.warn("BOM 环检测失败: top={} purpose={} from={} cycle={}",
              topCode, grp.getKey().bomPurpose, grp.getKey().effectiveFrom, e.getMessage());
          result.getFailedProducts().add(topCode);
        }
      }
      if (anyGroupSucceeded) {
        successProducts++;
      }
    }
    result.setProductsProcessed(successProducts);
    result.setRowsWritten(totalWritten);
    log.info("build-hierarchy 完成: buildBatch={} products={} rowsWritten={} failed={}",
        buildBatchId, successProducts, totalWritten, result.getFailedProducts().size());
    return result;
  }

  @Override
  public BomHierarchyTreeDto getHierarchyTree(
      String topProductCode, String bomPurpose, LocalDate asOfDate, String sourceType) {
    if (!StringUtils.hasText(topProductCode)) {
      throw new IllegalArgumentException("topProductCode 必填");
    }
    LocalDate d = asOfDate != null ? asOfDate : LocalDate.now();
    String st = StringUtils.hasText(sourceType) ? sourceType : "U9";

    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, topProductCode)
                .eq(BomRawHierarchy::getSourceType, st)
                .eq(StringUtils.hasText(bomPurpose), BomRawHierarchy::getBomPurpose, bomPurpose)
                .le(BomRawHierarchy::getEffectiveFrom, d)
                // effective_to >= d OR effective_to IS NULL（NULL = 当前生效）
                .and(w -> w.ge(BomRawHierarchy::getEffectiveTo, d)
                    .or()
                    .isNull(BomRawHierarchy::getEffectiveTo)));
    if (rows.isEmpty()) {
      return null;
    }
    return assembleTree(rows, topProductCode);
  }

  // ============================ 私有：DFS 构建 ============================

  /** 加载该 importBatch 的 U9 行；可选按 bomPurpose 过滤 */
  private List<BomU9Source> loadSourceRows(String importBatchId, String bomPurpose) {
    return bomU9SourceMapper.selectList(
        Wrappers.<BomU9Source>lambdaQuery()
            .eq(BomU9Source::getImportBatchId, importBatchId)
            .eq(StringUtils.hasText(bomPurpose), BomU9Source::getBomPurpose, bomPurpose));
  }

  /** 识别顶层：出现在 parent 集合但不出现在 child 集合里的料号 */
  private Set<String> collectTopProducts(List<BomU9Source> rows) {
    Set<String> parents = new HashSet<>();
    Set<String> children = new HashSet<>();
    for (BomU9Source r : rows) {
      if (StringUtils.hasText(r.getParentMaterialNo())) parents.add(r.getParentMaterialNo());
      if (StringUtils.hasText(r.getChildMaterialNo())) children.add(r.getChildMaterialNo());
    }
    parents.removeAll(children);
    return parents;
  }

  /**
   * 构建单个 (top, purpose, effective_from) 组的 DFS 结果。
   *
   * <p>顶层手动补一行（level=0, parent_code=自己, qty_per_top=1, is_leaf=false）；
   * 其余子节点由 DFS 写入。
   */
  private List<BomRawHierarchy> buildOneGroup(
      String topCode,
      GroupKey key,
      List<BomU9Source> rowsInGroup,
      String importBatchId,
      String buildBatchId,
      LocalDateTime builtAt,
      String buType) {
    // 邻接表：parent → 子行列表
    Map<String, List<BomU9Source>> childrenByParent = rowsInGroup.stream()
        .collect(Collectors.groupingBy(BomU9Source::getParentMaterialNo));

    List<BomRawHierarchy> output = new ArrayList<>();
    LinkedHashSet<String> visiting = new LinkedHashSet<>();

    // 顶层节点自己单独补一行（DFS 从顶层的子件开始）
    BomRawHierarchy topRow = buildTopRow(
        topCode, key, importBatchId, buildBatchId, builtAt, buType,
        childrenByParent.getOrDefault(topCode, List.of()).isEmpty() ? 1 : 0);
    output.add(topRow);

    dfs(topCode, topCode, 0, "/" + topCode + "/", BigDecimal.ONE,
        key, childrenByParent, visiting, output,
        importBatchId, buildBatchId, builtAt, buType);

    return output;
  }

  private BomRawHierarchy buildTopRow(
      String topCode,
      GroupKey key,
      String importBatchId,
      String buildBatchId,
      LocalDateTime builtAt,
      String buType,
      int isLeaf) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setTopProductCode(topCode);
    row.setParentCode(topCode); // 顶层 parent 填自己（DDL NOT NULL；设计文档 §附录 D）
    row.setMaterialCode(topCode);
    row.setLevel(0);
    row.setPath("/" + topCode + "/");
    row.setQtyPerParent(null);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setIsLeaf(isLeaf);
    row.setBomPurpose(key.bomPurpose);
    row.setEffectiveFrom(key.effectiveFrom);
    row.setEffectiveTo(key.effectiveTo);
    row.setSourceType("U9");
    row.setSourceImportBatchId(importBatchId);
    row.setBuildBatchId(buildBatchId);
    row.setBuiltAt(builtAt);
    row.setBusinessUnitType(buType);
    return row;
  }

  /**
   * DFS 递归展开。
   *
   * @param node 当前节点料号
   * @param parentCode 直接父（顶层首层进来时 parentCode=node 但不入输出）
   * @param level 当前层（顶层=0，顶层的直接子=1）
   * @param path 累计路径 {@code /top/.../node/}
   * @param qtyPerTop 累计到顶层用量
   * @param visiting 当前递归路径集合（环检测用）
   * @param output 输出 buffer
   */
  private void dfs(
      String node,
      String parentCode,
      int level,
      String path,
      BigDecimal qtyPerTop,
      GroupKey key,
      Map<String, List<BomU9Source>> childrenByParent,
      LinkedHashSet<String> visiting,
      List<BomRawHierarchy> output,
      String importBatchId,
      String buildBatchId,
      LocalDateTime builtAt,
      String buType) {
    if (visiting.contains(node)) {
      throw new CycleDetectedException(
          "BOM 环: " + String.join("→", visiting) + "→" + node);
    }
    visiting.add(node);
    try {
      List<BomU9Source> children = childrenByParent.getOrDefault(node, List.of());
      // 按 child_seq 排序，让 path 稳定可预测
      List<BomU9Source> sorted = new ArrayList<>(children);
      sorted.sort(Comparator.comparing(
          BomU9Source::getChildSeq, Comparator.nullsLast(Comparator.naturalOrder())));

      for (BomU9Source c : sorted) {
        BigDecimal childQtyPerParent = nvl(c.getQtyPerParent(), BigDecimal.ONE);
        BigDecimal childQtyPerTop = qtyPerTop.multiply(childQtyPerParent);
        String childPath = path + c.getChildMaterialNo() + "/";
        int childLevel = level + 1;

        // 先写自己（在递归进 c 的子件之前判断是否 leaf）
        boolean childIsLeaf =
            childrenByParent.getOrDefault(c.getChildMaterialNo(), List.of()).isEmpty();
        BomRawHierarchy row = fromU9Row(c, node, childLevel, childPath, childQtyPerTop,
            childIsLeaf, key, importBatchId, buildBatchId, builtAt, buType);
        output.add(row);

        // 再递归下去
        dfs(c.getChildMaterialNo(), node, childLevel, childPath, childQtyPerTop,
            key, childrenByParent, visiting, output,
            importBatchId, buildBatchId, builtAt, buType);
      }
    } finally {
      visiting.remove(node);
    }
  }

  /** 把 U9 原始行映射到一条 raw_hierarchy 行。
   *
   * <p>T7 修复：effective_from/to 来自边（u9 行）本身，不再从 GroupKey 读 ——
   * 原先因为按 (purpose, from, to) 分组，不同父子关系各自 effective 不同时跨层 DFS 被断开。
   * 现在按 bomPurpose 分组，每条 raw_hierarchy 行保留自己的 effective 期间。 */
  private BomRawHierarchy fromU9Row(
      BomU9Source src,
      String parentCode,
      int level,
      String path,
      BigDecimal qtyPerTop,
      boolean isLeaf,
      GroupKey key,
      String importBatchId,
      String buildBatchId,
      LocalDateTime builtAt,
      String buType) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setTopProductCode(pathTop(path));
    row.setParentCode(parentCode);
    row.setMaterialCode(src.getChildMaterialNo());
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(src.getChildSeq());
    row.setQtyPerParent(src.getQtyPerParent());
    row.setQtyPerTop(qtyPerTop);
    row.setMaterialName(src.getChildMaterialName());
    row.setMaterialSpec(src.getChildMaterialSpec());
    row.setShapeAttr(src.getShapeAttr());
    row.setSourceCategory(src.getProductionCategory());
    row.setCostElementCode(src.getCostElementCode());
    // T8：把 U9 主分类透传到 DWD 层；规则复合条件 childConditions 匹配这俩字段
    row.setMaterialCategory1(src.getMaterialCategory1());
    row.setMaterialCategory2(src.getMaterialCategory2());
    row.setBomPurpose(key.bomPurpose);
    row.setBomVersion(src.getBomVersion());
    row.setBomStatus(src.getBomStatus());
    row.setU9IsCostFlag(src.getU9IsCostFlag());
    row.setIsLeaf(isLeaf ? 1 : 0);
    // effective 从边本身取（T7 修复）；EPOCH 占位保证非空字段不炸
    row.setEffectiveFrom(src.getEffectiveFrom() == null ? EPOCH_PLACEHOLDER : src.getEffectiveFrom());
    row.setEffectiveTo(src.getEffectiveTo());
    row.setSourceType("U9");
    row.setSourceImportBatchId(importBatchId);
    row.setBuildBatchId(buildBatchId);
    row.setBuiltAt(builtAt);
    row.setBusinessUnitType(buType);
    return row;
  }

  /** 从 path {@code /top/.../} 取出顶层料号（第一个 {@code /} 后、第二个 {@code /} 前） */
  private static String pathTop(String path) {
    if (path == null || path.length() < 3) return null;
    int a = path.indexOf('/');
    int b = path.indexOf('/', a + 1);
    if (a < 0 || b < 0) return null;
    return path.substring(a + 1, b);
  }

  // ============================ 私有：DB 写入 ============================

  /** 分批 upsert；返回累计受影响行数 */
  private int writeInBatches(List<BomRawHierarchy> rows) {
    if (rows.isEmpty()) return 0;
    int total = 0;
    for (int start = 0; start < rows.size(); start += UPSERT_BATCH_SIZE) {
      int end = Math.min(start + UPSERT_BATCH_SIZE, rows.size());
      total += bomRawHierarchyMapper.batchUpsert(rows.subList(start, end));
    }
    return total;
  }

  // ============================ 私有：树组装（供 /hierarchy/{top}） ============================

  private BomHierarchyTreeDto assembleTree(List<BomRawHierarchy> rows, String topProductCode) {
    // materialCode → Dto（同一节点可能在不同路径下出现多次：走单个 materialCode 的第一个命中；
    // 层级树展示不强求多路径，按 parent_code 组装即可）
    Map<String, BomHierarchyTreeDto> byCode = new HashMap<>();
    for (BomRawHierarchy r : rows) {
      byCode.putIfAbsent(r.getMaterialCode(), toDto(r));
    }
    // 二次遍历：按 parent_code 挂接 children
    for (BomRawHierarchy r : rows) {
      // 顶层自己 parent_code 等于自己，别挂回自己
      if (r.getLevel() != null && r.getLevel() == 0) continue;
      BomHierarchyTreeDto child = byCode.get(r.getMaterialCode());
      BomHierarchyTreeDto parent = byCode.get(r.getParentCode());
      if (parent != null && child != null && !parent.getChildren().contains(child)) {
        parent.getChildren().add(child);
      }
    }
    return byCode.get(topProductCode);
  }

  private BomHierarchyTreeDto toDto(BomRawHierarchy r) {
    BomHierarchyTreeDto dto = new BomHierarchyTreeDto();
    dto.setMaterialCode(r.getMaterialCode());
    dto.setMaterialName(r.getMaterialName());
    dto.setMaterialSpec(r.getMaterialSpec());
    dto.setLevel(r.getLevel());
    dto.setPath(r.getPath());
    dto.setQtyPerParent(r.getQtyPerParent());
    dto.setQtyPerTop(r.getQtyPerTop());
    dto.setShapeAttr(r.getShapeAttr());
    dto.setSourceCategory(r.getSourceCategory());
    dto.setBomPurpose(r.getBomPurpose());
    dto.setBomVersion(r.getBomVersion());
    dto.setIsLeaf(r.getIsLeaf());
    dto.setEffectiveFrom(r.getEffectiveFrom());
    dto.setEffectiveTo(r.getEffectiveTo());
    return dto;
  }

  // ============================ 小工具 ============================

  private static String generateBuildBatchId() {
    return "h_" + java.time.LocalDate.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
  }

  private static BigDecimal nvl(BigDecimal v, BigDecimal dft) {
    return v == null ? dft : v;
  }

  /** 按 bomPurpose 分组的 key（T7 修复：原先按 purpose+from+to 三元组分组，
   *  导致不同 effective 的父子边被切成孤岛，DFS 走不通；
   *  effective_from/to 放到 fromU9Row 从边本身读，顶层行保留一个代表值）。 */
  private static GroupKey groupKeyOf(BomU9Source r) {
    return new GroupKey(r.getBomPurpose(), EPOCH_PLACEHOLDER, null);
  }

  /** 不可变 key，用于 groupingBy */
  private static final class GroupKey {
    final String bomPurpose;
    final LocalDate effectiveFrom;
    final LocalDate effectiveTo;

    GroupKey(String bomPurpose, LocalDate from, LocalDate to) {
      this.bomPurpose = bomPurpose;
      this.effectiveFrom = from;
      this.effectiveTo = to;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof GroupKey other)) return false;
      return java.util.Objects.equals(bomPurpose, other.bomPurpose)
          && java.util.Objects.equals(effectiveFrom, other.effectiveFrom)
          && java.util.Objects.equals(effectiveTo, other.effectiveTo);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(bomPurpose, effectiveFrom, effectiveTo);
    }
  }

  /** 环检测异常；上层按顶层产品捕获转 failedProducts，不阻塞其他产品 */
  static final class CycleDetectedException extends RuntimeException {
    CycleDetectedException(String message) {
      super(message);
    }
  }
}
