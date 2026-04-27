package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomStopDrillRuleMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomFlattenService;
import com.sanhua.marketingcost.service.rule.BomNodeContext;
import com.sanhua.marketingcost.service.rule.StopDrillRuleMatcher;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * BOM 阶段 C 拍平实现。
 *
 * <p>算法（按 path 升序自顶向下扫）：
 * <ol>
 *   <li>按 asOfDate + topProductCode + (可选) bomPurpose 拉 raw_hierarchy 快照</li>
 *   <li>建 path 索引 + 父子邻接表，供 T8 复合规则评估父/子节点上下文</li>
 *   <li>维护 {@code stoppedPaths}：凡被 STOP / EXCLUDE / ROLLUP 命中的节点（或 ROLLUP 的父节点）path 加入</li>
 *   <li>遍历每行：若 path 在某 stoppedPath 的子树下（strict 前缀匹配）→ 跳过</li>
 *   <li>规则匹配：
 *     <ul>
 *       <li>EXCLUDE → 不入 costing，path 加入 stoppedPaths（子树也不处理）</li>
 *       <li>STOP_AND_COST_ROW → 本节点入 costing，按规则打 subtree_cost_required；path 加入 stoppedPaths</li>
 *       <li>ROLLUP_TO_PARENT (T8) → 父节点入 costing 并去重，本节点不入；父 path 加入 stoppedPaths；每个命中子件记 sub_ref</li>
 *       <li>REPLACE → 本期抛 UnsupportedOperationException</li>
 *       <li>未命中：叶子入 costing（is_costing_row=1 默认），中间节点跳过</li>
 *     </ul>
 *   </li>
 *   <li>写库前 DELETE WHERE oa+top+asOfDate（hotfix-2026-04-27），再 batch upsert lp_bom_costing_row：
 *       让该 (oa, top, asOfDate) 下唯一权威快照，避免规则配置变更后老 path 残留"幽灵行"；
 *       其他 asOfDate 的历史月度快照不受影响</li>
 *   <li>T8：反查 ROLLUP 父件的 costing_row.id，批量写 lp_bom_costing_row_sub_ref</li>
 * </ol>
 *
 * <p>本期只实现 {@code mode=BY_OA}（oaNo + topProductCode + asOfDate），其他模式抛
 * {@link UnsupportedOperationException}（见 Handoff Note）。
 */
@Service
public class BomFlattenServiceImpl implements BomFlattenService {

  private static final Logger log = LoggerFactory.getLogger(BomFlattenServiceImpl.class);

  /** 批量 upsert 单批次大小，防止 SQL 超 max_allowed_packet */
  private static final int UPSERT_BATCH_SIZE = 500;

  private final BomRawHierarchyMapper rawMapper;
  private final BomCostingRowMapper costingMapper;
  private final BomCostingRowSubRefMapper subRefMapper;
  private final BomStopDrillRuleMapper ruleMapper;
  /** T8：直接注入具体类而不是 RuleMatcher 接口 —— 要用它的 4 参数 match 方法 */
  private final StopDrillRuleMatcher ruleMatcher;

  public BomFlattenServiceImpl(
      BomRawHierarchyMapper rawMapper,
      BomCostingRowMapper costingMapper,
      BomCostingRowSubRefMapper subRefMapper,
      BomStopDrillRuleMapper ruleMapper,
      StopDrillRuleMatcher ruleMatcher) {
    this.rawMapper = rawMapper;
    this.costingMapper = costingMapper;
    this.subRefMapper = subRefMapper;
    this.ruleMapper = ruleMapper;
    this.ruleMatcher = ruleMatcher;
  }

  // ============================ public API ============================

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FlattenResult flatten(FlattenRequest request) {
    validate(request);

    String mode = request.getMode() == null ? "BY_OA" : request.getMode().toUpperCase();
    if (!"BY_OA".equals(mode)) {
      throw new UnsupportedOperationException(
          "flatten 本期只支持 mode=BY_OA；ALL / BY_PRODUCT 预留给 T5.5/后续");
    }

    FlattenResult result = new FlattenResult();

    // 1) 拉取 asOfDate 有效的 raw_hierarchy 快照
    LocalDate asOf = request.getAsOfDate();
    String purpose = request.getBomPurpose();
    List<BomRawHierarchy> rawRows =
        rawMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, request.getTopProductCode())
                .eq(BomRawHierarchy::getSourceType, "U9")
                .eq(StringUtils.hasText(purpose), BomRawHierarchy::getBomPurpose, purpose)
                .le(BomRawHierarchy::getEffectiveFrom, asOf)
                .and(w -> w.ge(BomRawHierarchy::getEffectiveTo, asOf)
                    .or()
                    .isNull(BomRawHierarchy::getEffectiveTo)));

    if (rawRows.isEmpty()) {
      result.getWarnings().add(
          "产品 " + request.getTopProductCode() + " 在 asOfDate=" + asOf + " 无 BOM 版本");
      return result;
    }

    // 2) 按 path 升序排序（自顶向下）
    rawRows.sort(Comparator.comparing(BomRawHierarchy::getPath));

    // 3) 建路径索引 + 父子邻接表（T8：matcher 复合条件要看父 + 子）
    Map<String, BomRawHierarchy> rawByPath = new HashMap<>();
    Map<String, List<BomRawHierarchy>> childrenByParentPath = new HashMap<>();
    for (BomRawHierarchy r : rawRows) {
      rawByPath.put(r.getPath(), r);
    }
    for (BomRawHierarchy r : rawRows) {
      if (r.getLevel() != null && r.getLevel() > 0) {
        String pp = parentPathOf(r.getPath());
        if (pp != null) {
          childrenByParentPath.computeIfAbsent(pp, k -> new ArrayList<>()).add(r);
        }
      }
    }

    // 4) 加载所有启用规则（按 priority 升序由 matcher 自己处理）
    List<BomStopDrillRule> rules =
        ruleMapper.selectList(
            Wrappers.<BomStopDrillRule>lambdaQuery().eq(BomStopDrillRule::getEnabled, 1));

    // 5) 单次构建批次 ID + 时间戳（所有行共用，便于追溯）
    String buildBatchId = generateFlattenBatchId();
    LocalDateTime builtAt = LocalDateTime.now();
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();

    // 6) 扫一遍 + stoppedPaths 前缀剔除
    //    ROLLUP 分支用 LinkedHashMap 保序 + 去重：key = 父件 path
    List<BomCostingRow> nonRolledOutput = new ArrayList<>();
    Map<String, BomCostingRow> rolledUpByParentPath = new LinkedHashMap<>();
    List<PendingSubRef> pendingSubRefs = new ArrayList<>();
    // T11：LEAF_ROLLUP_TO_PARENT 专用 pending 容器
    //   - 主循环里命中叶子先攒到 leafRollupBuckets（按"父 path"去重）+ 命中叶子 path 加 stoppedPaths 单点屏蔽
    //   - 主循环结束后扫 buckets：每个父 path 入 1 行 costing（subtree_cost_required=1）+ 命中叶子写 sub_ref
    //   - 关键：父 path **不**进 stoppedPaths（设计文档 §3.3 (a)），父的非命中兄弟叶子要继续作独立结算行
    Map<String, LeafRollupBucket> leafRollupBuckets = new LinkedHashMap<>();
    List<String> stoppedPaths = new ArrayList<>();
    // T11：单点屏蔽（exact match）—— 命中叶子 path 加这里，避免主循环把它当默认叶子重复处理；
    //   不能加 stoppedPaths（那是子树前缀屏蔽，命中叶子的"子树"其实是它自己；
    //   按 isUnderStoppedSubtree 的 strict 子树判定，加 stoppedPaths 不会影响该叶子本身的处理顺序）。
    //   实际：因为我们在命中分支里 continue，加哪个集合都行；为可读性单独维护单点屏蔽集合。
    java.util.Set<String> consumedLeafPaths = new java.util.HashSet<>();
    int subtreeRequiredCount = 0;

    for (BomRawHierarchy row : rawRows) {
      if (isUnderStoppedSubtree(row.getPath(), stoppedPaths)) {
        continue;
      }
      // T11：被某个 LEAF_ROLLUP 命中的叶子 path 单点屏蔽 —— 防止下面的"未命中默认叶子"分支重复入 costing
      if (consumedLeafPaths.contains(row.getPath())) {
        continue;
      }

      // 构造当前节点 / 父 / 直接子节点 context（给复合条件用）
      BomNodeContext ctx = buildContext(row, buType);
      BomRawHierarchy parentRaw = row.getLevel() != null && row.getLevel() > 0
          ? rawByPath.get(parentPathOf(row.getPath()))
          : null;
      BomNodeContext parentCtx = parentRaw != null ? buildContext(parentRaw, buType) : null;
      List<BomRawHierarchy> directChildren =
          childrenByParentPath.getOrDefault(row.getPath(), List.of());
      List<BomNodeContext> childCtxs = directChildren.stream()
          .map(c -> buildContext(c, buType))
          .toList();

      Optional<BomStopDrillRule> hit =
          ruleMatcher.match(ctx, parentCtx, childCtxs, rules);

      if (hit.isPresent()) {
        BomStopDrillRule rule = hit.get();
        String action = rule.getDrillAction();

        if ("EXCLUDE".equalsIgnoreCase(action)) {
          stoppedPaths.add(row.getPath());
          continue;
        }
        if ("REPLACE".equalsIgnoreCase(action)) {
          throw new UnsupportedOperationException(
              "REPLACE 动作暂未实现（规则 id=" + rule.getId() + "）");
        }
        if ("ROLLUP_TO_PARENT".equalsIgnoreCase(action)) {
          // T8 语义订正（2026-04-24）：
          // "ROLLUP_TO_PARENT" = 命中节点（它本身在 BOM 里就是父件身份）作结算行 +
          //   其直接子件写进 sub_ref + 整棵子树 stopped。
          // 不是"上卷到命中节点的父"。
          //
          // 业务场景：铜管组件（父件）自身 cost_element='主要材料-原材料' 被规则命中，
          //   该组件入 costing_row，其子件（紫铜盘管/紫铜直管）进 sub_ref。
          //   T9 取价时读 sub_ref 对每个子件按公式算，加总 = 组件价。
          String hitPathKey = row.getPath();
          if (!rolledUpByParentPath.containsKey(hitPathKey)) {
            boolean markSubtree = rule.getMarkSubtreeCostRequired() != null
                && rule.getMarkSubtreeCostRequired() == 1;
            BomCostingRow parentCost = buildCostingRow(
                row, request, true, markSubtree, rule.getId(),
                buildBatchId, builtAt, buType);
            rolledUpByParentPath.put(hitPathKey, parentCost);
            // 整棵命中节点子树进 stoppedPaths（所有子件不再单独产出结算行）
            stoppedPaths.add(hitPathKey);
            if (markSubtree) subtreeRequiredCount++;
          }
          // 把"本次命中节点下所有符合规则 childConditions 的直接子件"记为 sub_ref。
          // 规则可能只限定部分主分类（例：紫铜盘管/直管）；只有命中 childConditions 的子件才算"需要取价的子件"。
          for (BomRawHierarchy child : directChildren) {
            BomNodeContext childCtx = buildContext(child, buType);
            // 用 matcher 重新判断这个规则对该子件是否命中 childConditions（复用 evaluator 逻辑）
            // 简化：只要命中节点已命中规则，就把所有直接子件都记进去，
            //   由后续 T9 取价阶段根据规则条件再过滤（取价时反查规则条件也方便）。
            // 这样简化不丢语义，因为子件列表里"非命中类目"在 T9 时如果没价会警报。
            pendingSubRefs.add(new PendingSubRef(hitPathKey, child, buType));
          }
          continue;
        }
        if ("LEAF_ROLLUP_TO_PARENT".equalsIgnoreCase(action)) {
          // T11 新分支语义（设计文档 §3.2 / §3.3）：
          //   "命中叶子 → 叶子的直接父作结算行（subtree_cost_required=1）+ 仅命中叶子写 sub_ref"
          // 与老 ROLLUP_TO_PARENT 的本质区别：
          //   - 父 path **不**加 stoppedPaths（父的非命中兄弟叶子要照常作独立结算）
          //   - sub_ref 仅写"命中叶子"（不写父的全部子件）
          //   - 同一料号在不同 path 下可能多次出现 → 按"父 path"去重，不是按 material_code

          // 强校验 is_leaf=1（即使规则 nodeConditions 没显式写 is_leaf=1）
          if (row.getIsLeaf() == null || row.getIsLeaf() != 1) {
            result.getWarnings().add(
                "LEAF_ROLLUP_NOT_LEAF: 规则 id=" + rule.getId()
                    + " 命中非叶子节点 " + row.getMaterialCode()
                    + " path=" + row.getPath() + "，跳过该次命中（中间节点不入 costing，下钻照常）");
            // 不加 stoppedPaths，主循环正常往下走（中间节点未命中分支会跳过它本身、其子继续）
            continue;
          }

          // 顶层叶子（level=0 且 is_leaf=1）罕见但要兜：无父可上卷
          // 注：变量名加 leaf 前缀，避免与上面 line 179 的 parentRaw 同名冲突
          String leafParentPath = parentPathOf(row.getPath());
          BomRawHierarchy leafParentRaw = leafParentPath == null ? null : rawByPath.get(leafParentPath);
          if (leafParentRaw == null) {
            result.getWarnings().add(
                "LEAF_ROLLUP_TOP_LEAF: 规则 id=" + rule.getId()
                    + " 命中顶层叶子 " + row.getMaterialCode()
                    + " 无父可上卷，按默认叶子结算入 costing");
            nonRolledOutput.add(
                buildCostingRow(row, request, true, false, null,
                    buildBatchId, builtAt, buType));
            continue;
          }

          // 父若已被其他规则命中（STOP / EXCLUDE / ROLLUP）—— 父 path 已在 stoppedPaths
          //   因为 stoppedPaths 是"严格子树前缀"判定，父 path 加进 stoppedPaths 后
          //   它的子件（含本叶子）会在 isUnderStoppedSubtree 那里被剔除，
          //   所以走到这里的叶子，父 path 一定不在 stoppedPaths。
          //   但保险起见在这里再做一道兜底（设计文档 §3.3 (c)）。
          if (stoppedPaths.contains(leafParentPath)) {
            result.getWarnings().add(
                "LEAF_ROLLUP_PARENT_STOPPED: 规则 id=" + rule.getId()
                    + " 命中叶子 " + row.getMaterialCode()
                    + " 但父 " + leafParentRaw.getMaterialCode() + " 已被其他规则停用，跳过");
            consumedLeafPaths.add(row.getPath());
            continue;
          }

          // 收集到 bucket（按"父 path"去重；同父多个铜管叶子只入 1 行父 costing）
          leafRollupBuckets
              .computeIfAbsent(leafParentPath, k -> new LeafRollupBucket(leafParentRaw, rule.getId()))
              .addLeaf(row);
          // 单点屏蔽该叶子，主循环不会再把它当默认叶子重复入 costing
          consumedLeafPaths.add(row.getPath());
          continue;
        }

        // STOP_AND_COST_ROW（默认）
        boolean markSubtree = rule.getMarkSubtreeCostRequired() != null
            && rule.getMarkSubtreeCostRequired() == 1;
        nonRolledOutput.add(
            buildCostingRow(row, request, true, markSubtree, rule.getId(),
                buildBatchId, builtAt, buType));
        stoppedPaths.add(row.getPath());
        if (markSubtree) subtreeRequiredCount++;
        continue;
      }

      // 未命中规则：叶子打结算行，中间节点跳过
      if (row.getIsLeaf() != null && row.getIsLeaf() == 1) {
        nonRolledOutput.add(
            buildCostingRow(row, request, true, false, null, buildBatchId, builtAt, buType));
      }
    }

    // 7) T11：处理 LEAF_ROLLUP buckets —— 每个父 path 入 1 行 costing（subtree_cost_required=1）
    //    + 把命中叶子塞进 pendingSubRefs（与老 ROLLUP 共用 sub_ref 容器：写到同一张表，
    //      下游通过 costing_row.matched_drill_rule_id 反查规则的 drill_action 区分两类
    //      —— 见任务文档 §6 "常见坑" 末条；YAGNI，未来加 rollup_kind 列再分流）
    Map<String, BomCostingRow> leafRolledParentByPath = new LinkedHashMap<>();
    for (Map.Entry<String, LeafRollupBucket> e : leafRollupBuckets.entrySet()) {
      LeafRollupBucket bucket = e.getValue();
      // 安全：父 path 在主循环 LEAF_ROLLUP 分支已检查不在 stoppedPaths；这里再兜一道
      if (stoppedPaths.contains(bucket.parentRaw.getPath())) {
        result.getWarnings().add(
            "LEAF_ROLLUP_PARENT_STOPPED: 父 " + bucket.parentRaw.getMaterialCode()
                + " 在二次扫描时已被其他规则停用，跳过该 bucket（"
                + bucket.leaves.size() + " 个叶子）");
        continue;
      }
      // 父 path 若已被老 ROLLUP_TO_PARENT 命中（同时入 rolledUpByParentPath），优先老规则
      //   —— 老 ROLLUP 父行是"它本身作结算"，新 LEAF_ROLLUP 也想让父作结算，二者目标同；
      //   但老 ROLLUP 已经把整棵子树 stoppedPaths 了，叶子根本进不到 LEAF_ROLLUP 分支。
      //   所以这种冲突理论上不会发生 —— 兜底处理：跳过 + warn。
      if (rolledUpByParentPath.containsKey(bucket.parentRaw.getPath())) {
        result.getWarnings().add(
            "LEAF_ROLLUP_PARENT_STOPPED: 父 " + bucket.parentRaw.getMaterialCode()
                + " 同时被老 ROLLUP_TO_PARENT 命中，跳过 LEAF_ROLLUP bucket");
        continue;
      }
      BomCostingRow parentCosting = buildCostingRow(
          bucket.parentRaw, request,
          /* isCostingRow */ true,
          /* subtreeRequired */ true,
          bucket.matchedRuleId,
          buildBatchId, builtAt, buType);
      leafRolledParentByPath.put(bucket.parentRaw.getPath(), parentCosting);
      subtreeRequiredCount++;
      // 命中叶子写 sub_ref（仅命中叶子，不写父其他子件）
      for (BomRawHierarchy leaf : bucket.leaves) {
        pendingSubRefs.add(new PendingSubRef(bucket.parentRaw.getPath(), leaf, buType));
      }
    }

    // 8) 合并：先老 ROLLUP 父件行 + LEAF_ROLLUP 父件行 + 后非 ROLLUP 行
    List<BomCostingRow> allCostingRows = new ArrayList<>(
        rolledUpByParentPath.size() + leafRolledParentByPath.size() + nonRolledOutput.size());
    allCostingRows.addAll(rolledUpByParentPath.values());
    allCostingRows.addAll(leafRolledParentByPath.values());
    allCostingRows.addAll(nonRolledOutput);

    // hotfix-2026-04-27：写之前先清掉本次 (oa+top+asOfDate) 已有的 costing_row。
    //
    // 原 batchUpsert（按 oa+top+asOfDate+path 五元组 UPSERT）只能覆盖"本次产生的同 path"
    // 行；如果某条老 path 因为规则配置变更（如 T11 停用规则 #4）后不再产生 → 老行
    // 既不会被覆盖也不会被删 → 残留为"幽灵行"。
    //
    // 改为 DELETE+INSERT：让本次拍平结果是该 (oa, top, asOfDate) 下唯一权威快照。
    // sub_ref 由 fk_sub_ref_costing ON DELETE CASCADE 自动级联清，无需手工清。
    //
    // 锁 as_of_date：DELETE 范围只到本次 as_of_date 这一份月度快照，绝不动其他历史月度。
    costingMapper.delete(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, request.getOaNo())
            .eq(BomCostingRow::getTopProductCode, request.getTopProductCode())
            .eq(BomCostingRow::getAsOfDate, asOf));
    int written = writeInBatches(allCostingRows);
    result.setCostingRowsWritten(written);
    result.setSubtreeRequiredCount(subtreeRequiredCount);

    // 9) T8 + T11：反查 ROLLUP/LEAF_ROLLUP 父件结算行的 id，批量写 sub_ref
    //    parentPaths 合集 = 老 ROLLUP 父 ∪ LEAF_ROLLUP 父
    if (!pendingSubRefs.isEmpty()) {
      java.util.Set<String> allParentPaths = new java.util.HashSet<>();
      allParentPaths.addAll(rolledUpByParentPath.keySet());
      allParentPaths.addAll(leafRolledParentByPath.keySet());
      int subRefCount = writeSubRefs(request, pendingSubRefs, allParentPaths);
      log.info("flatten 写入 sub_ref {} 条 for {} 老 ROLLUP 父 + {} LEAF_ROLLUP 父",
          subRefCount, rolledUpByParentPath.size(), leafRolledParentByPath.size());
    }

    log.info(
        "flatten 完成: oa={} top={} asOf={} written={} subtreeRequired={} rolledUpParents={} leafRollupParents={} warnings={}",
        request.getOaNo(), request.getTopProductCode(), asOf,
        written, subtreeRequiredCount,
        rolledUpByParentPath.size(), leafRolledParentByPath.size(),
        result.getWarnings().size());
    return result;
  }

  // ============================ 私有辅助 ============================

  private static void validate(FlattenRequest req) {
    if (req == null) {
      throw new IllegalArgumentException("request 必填");
    }
    if (req.getAsOfDate() == null) {
      throw new IllegalArgumentException("asOfDate 必填（锁月复现核心）");
    }
    if (!StringUtils.hasText(req.getOaNo())) {
      throw new IllegalArgumentException("oaNo 必填");
    }
    if (!StringUtils.hasText(req.getTopProductCode())) {
      throw new IllegalArgumentException("topProductCode 必填");
    }
  }

  /** 从 path 算出父 path；根 path "/top/" 的父返 null（顶层无父）。 */
  private static String parentPathOf(String path) {
    if (path == null || path.length() < 2) return null;
    // path 形如 "/top/a/b/"，去掉末尾 "/" 后再去掉最后一段，然后补回 "/"
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    int lastSlash = trimmed.lastIndexOf('/');
    if (lastSlash <= 0) return null; // 没有上一级
    return trimmed.substring(0, lastSlash + 1);
  }

  /**
   * 判断 path 是否在 stoppedPaths 里某 path 的<b>严格</b>子树下。
   *
   * <p>严格子树 = startsWith(sp) 且长度 > sp.length()；stopped 节点自己不算在子树下。
   * 注意：T8 ROLLUP 分支把父 path 加入 stoppedPaths 后，**命中的子件也会被判为"在子树下"**
   * —— 这正是我们要的（子件不再单独产结算行）。
   */
  private static boolean isUnderStoppedSubtree(String path, List<String> stoppedPaths) {
    if (stoppedPaths.isEmpty() || path == null) return false;
    for (String sp : stoppedPaths) {
      if (path.startsWith(sp) && path.length() > sp.length()) {
        return true;
      }
    }
    return false;
  }

  private static BomNodeContext buildContext(BomRawHierarchy row, String buType) {
    // T8：materialCategory 回退到 material_category_1（老 MATERIAL_TYPE 规则保持兼容）；
    // 同时暴露 costElementCode / materialCategory1 / materialCategory2 供复合条件使用。
    return new BomNodeContext(
        row.getMaterialCode(),
        row.getMaterialName(),
        /* materialCategory= */ row.getMaterialCategory1(),
        row.getShapeAttr(),
        row.getSourceCategory(),
        buType,
        row.getCostElementCode(),
        row.getMaterialCategory1(),
        row.getMaterialCategory2());
  }

  private static BomCostingRow buildCostingRow(
      BomRawHierarchy raw,
      FlattenRequest req,
      boolean isCostingRow,
      boolean subtreeRequired,
      Long matchedRuleId,
      String buildBatchId,
      LocalDateTime builtAt,
      String buType) {
    BomCostingRow c = new BomCostingRow();
    c.setOaNo(req.getOaNo());
    c.setTopProductCode(raw.getTopProductCode());
    // 顶层行 raw.parentCode = 自己；costing_row 按 DDL 约定顶层 parent_code=NULL
    if (raw.getLevel() != null && raw.getLevel() == 0) {
      c.setParentCode(null);
    } else {
      c.setParentCode(raw.getParentCode());
    }
    c.setMaterialCode(raw.getMaterialCode());
    c.setLevel(raw.getLevel());
    c.setPath(raw.getPath());
    c.setQtyPerParent(raw.getQtyPerParent());
    c.setQtyPerTop(raw.getQtyPerTop());
    c.setIsCostingRow(isCostingRow ? 1 : 0);
    c.setSubtreeCostRequired(subtreeRequired ? 1 : 0);
    c.setRawHierarchyNodeId(raw.getId());
    c.setMatchedDrillRuleId(matchedRuleId);
    c.setMaterialName(raw.getMaterialName());
    c.setMaterialSpec(raw.getMaterialSpec());
    c.setShapeAttr(raw.getShapeAttr());
    c.setSourceCategory(raw.getSourceCategory());
    c.setCostElementCode(raw.getCostElementCode());
    c.setBomPurpose(raw.getBomPurpose());
    c.setBomVersion(raw.getBomVersion());
    c.setU9IsCostFlag(raw.getU9IsCostFlag());
    c.setEffectiveFrom(raw.getEffectiveFrom());
    c.setEffectiveTo(raw.getEffectiveTo());
    c.setBuildBatchId(buildBatchId);
    c.setBuiltAt(builtAt);
    c.setAsOfDate(req.getAsOfDate());
    // 冻住 raw 行的 effective_from 作为版本锁定（即便 raw effective_from=NULL 也冻它）
    c.setRawVersionEffectiveFrom(
        raw.getEffectiveFrom() != null ? raw.getEffectiveFrom() : LocalDate.of(1970, 1, 1));
    c.setBusinessUnitType(buType);
    return c;
  }

  private int writeInBatches(List<BomCostingRow> rows) {
    if (rows.isEmpty()) return 0;
    int total = 0;
    for (int start = 0; start < rows.size(); start += UPSERT_BATCH_SIZE) {
      int end = Math.min(start + UPSERT_BATCH_SIZE, rows.size());
      total += costingMapper.batchUpsert(rows.subList(start, end));
    }
    return total;
  }

  /**
   * T8：把 pendingSubRefs 写成 sub_ref 行。
   *
   * <p>流程：
   * <ol>
   *   <li>先把本次所有 ROLLUP 父件清掉已有的 sub_ref（按 costing_row_id 级联删除，
   *       避免同一父件重复 flatten 时 sub_ref 堆积）—— 通过 CASCADE 自动完成（costing_row upsert 不 DELETE 旧行，
   *       所以这里要手工清：按 oa_no + top + as_of_date 反查 costing_row.id 清相关 sub_ref）</li>
   *   <li>反查每个父件 costing_row 的 id</li>
   *   <li>批量 insert sub_ref</li>
   * </ol>
   */
  private int writeSubRefs(
      FlattenRequest req,
      List<PendingSubRef> pendings,
      java.util.Set<String> parentPaths) {
    if (pendings.isEmpty()) return 0;

    // 反查父件 costing_row 的 id 映射
    List<BomCostingRow> parentRows = costingMapper.selectList(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, req.getOaNo())
            .eq(BomCostingRow::getTopProductCode, req.getTopProductCode())
            .eq(BomCostingRow::getAsOfDate, req.getAsOfDate())
            .in(BomCostingRow::getPath, parentPaths));
    Map<String, Long> parentPathToCostingId = new HashMap<>();
    for (BomCostingRow p : parentRows) {
      parentPathToCostingId.put(p.getPath(), p.getId());
    }

    // 先清掉这些父件下的旧 sub_ref（幂等重跑语义：重算一次 sub_ref 就是一份新快照）
    List<Long> parentCostingIds = new ArrayList<>(parentPathToCostingId.values());
    if (!parentCostingIds.isEmpty()) {
      subRefMapper.delete(
          Wrappers.<BomCostingRowSubRef>lambdaQuery()
              .in(BomCostingRowSubRef::getCostingRowId, parentCostingIds));
    }

    // 批量插入新 sub_ref
    int inserted = 0;
    for (PendingSubRef p : pendings) {
      Long parentId = parentPathToCostingId.get(p.parentPath);
      if (parentId == null) {
        log.warn("sub_ref 写入跳过：父件 costing_row 未反查到 id，parentPath={}", p.parentPath);
        continue;
      }
      BomCostingRowSubRef ref = new BomCostingRowSubRef();
      ref.setCostingRowId(parentId);
      ref.setSubMaterialCode(p.child.getMaterialCode());
      ref.setSubMaterialName(p.child.getMaterialName());
      ref.setSubMaterialCategory(p.child.getMaterialCategory1());
      ref.setSubQtyPerParent(p.child.getQtyPerParent());
      ref.setSubQtyPerTop(p.child.getQtyPerTop());
      ref.setSubRawHierarchyId(p.child.getId());
      ref.setSubPath(p.child.getPath());
      // businessUnitType 走 MetaObjectHandler 自动回填
      subRefMapper.insert(ref);
      inserted++;
    }
    return inserted;
  }

  private static String generateFlattenBatchId() {
    return "f_" + java.time.LocalDate.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
  }

  /** 暂存本次 flatten 里一条 ROLLUP 命中子件的信息，等父件 costing_row 落库后回填 id。 */
  private record PendingSubRef(String parentPath, BomRawHierarchy child, String buType) {}

  /**
   * T11 · LEAF_ROLLUP_TO_PARENT 命中桶。
   *
   * <p>主循环按 path 升序遍历时，命中铜管类叶子先攒到 bucket（按"父 path"去重），
   * 主循环结束后扫 bucket 一次性写：
   * <ul>
   *   <li>父 path 入 1 行 costing_row（subtree_cost_required=1，matched_drill_rule_id=该规则 id）</li>
   *   <li>所有命中叶子写 sub_ref（仅命中叶子，不含父其他兄弟子件）</li>
   * </ul>
   *
   * <p>matchedRuleId 取首个命中该 bucket 的规则 id；同一父下多个叶子被不同规则命中
   * 的场景在当前 bucket 模型里不区分（YAGNI；实际只有 1 条 LEAF_ROLLUP 规则）。
   */
  private static class LeafRollupBucket {
    final BomRawHierarchy parentRaw;
    final Long matchedRuleId;
    final List<BomRawHierarchy> leaves = new ArrayList<>();

    LeafRollupBucket(BomRawHierarchy parentRaw, Long matchedRuleId) {
      this.parentRaw = parentRaw;
      this.matchedRuleId = matchedRuleId;
    }

    void addLeaf(BomRawHierarchy leaf) {
      leaves.add(leaf);
    }
  }
}
