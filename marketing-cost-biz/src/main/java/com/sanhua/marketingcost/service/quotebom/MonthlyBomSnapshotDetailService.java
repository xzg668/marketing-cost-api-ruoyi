package com.sanhua.marketingcost.service.quotebom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.bomalternative.U9BomAlternativeMetadata;
import com.sanhua.marketingcost.service.impl.BomEffectiveTreePruner;
import com.sanhua.marketingcost.service.impl.PlateCommercialMakeBomExpansionService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the immutable hierarchy rows belonging to one product/organization/accounting month. */
@Service
public class MonthlyBomSnapshotDetailService {

  private static final String DEFAULT_BOM_PURPOSE = "主制造";
  private static final int READ_CHUNK = 128;

  private final ThreadLocal<Map<Long, List<BomRawHierarchy>>> prepared = new ThreadLocal<>();
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final BomRawHierarchyMapper hierarchyMapper;
  private final PlateCommercialMakeBomExpansionService expansionService;
  private final U9BomAlternativeMetadata alternativeMetadata;

  public MonthlyBomSnapshotDetailService(JdbcTemplate jdbc, ObjectMapper json,
      BomRawHierarchyMapper hierarchyMapper,
      PlateCommercialMakeBomExpansionService expansionService,
      U9BomAlternativeMetadata alternativeMetadata) {
    this.jdbc = jdbc;
    this.json = json;
    this.hierarchyMapper = hierarchyMapper;
    this.expansionService = expansionService;
    this.alternativeMetadata = alternativeMetadata;
  }

  public List<BomRawHierarchy> load(Long snapshotId) {
    if (snapshotId == null) return List.of();
    var loaded = prepared.get();
    if (loaded != null && loaded.containsKey(snapshotId)) return detached(loaded.get(snapshotId));
    return loadBatch(List.of(snapshotId)).get(snapshotId);
  }

  /** Scoped immutable snapshot reads; shared input cannot be changed by a consumer's BOM merger. */
  public <T> T useLoaded(Map<Long, List<BomRawHierarchy>> loaded, java.util.function.Supplier<T> work) {
    if (prepared.get() != null) throw new IllegalStateException("批量BOM读取范围不能嵌套");
    if (loaded.size() > 64) throw new IllegalArgumentException("批量BOM执行每次最多64个快照");
    var owned = new LinkedHashMap<Long, List<BomRawHierarchy>>();
    // Empty snapshots may be legitimately filled by the existing approved drawing path.
    loaded.forEach((id, rows) -> { if (!rows.isEmpty()) owned.put(id, detached(rows)); });
    prepared.set(owned);
    try { return work.get(); }
    finally { prepared.remove(); }
  }

  private List<BomRawHierarchy> detached(List<BomRawHierarchy> rows) {
    return rows.stream().map(row -> {
      var copy = new BomRawHierarchy(); org.springframework.beans.BeanUtils.copyProperties(row, copy); return copy;
    }).toList();
  }

  /**
   * Reads only the snapshots already selected by each consumer's BOM scope. No source acquisition
   * or snapshot creation occurs here. SQL is bounded; duplicate occurrences and saved order remain.
   * Decoded nodes belong to this call and are never kept in a process-wide cache.
   */
  public Map<Long, List<BomRawHierarchy>> loadBatch(Collection<Long> snapshotIds) {
    var ids = new ArrayList<>(new LinkedHashSet<>(snapshotIds));
    if (ids.stream().anyMatch(id -> id == null || id <= 0))
      throw new IllegalArgumentException("月度BOM快照ID必须大于0");
    var rows = new LinkedHashMap<Long, List<BomRawHierarchy>>();
    ids.forEach(id -> rows.put(id, new ArrayList<>()));
    for (int start = 0; start < ids.size(); start += READ_CHUNK) {
      var chunk = ids.subList(start, Math.min(start + READ_CHUNK, ids.size()));
      var marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
      jdbc.query("SELECT monthly_snapshot_id,raw_node_json FROM lp_quote_bom_monthly_snapshot_detail "
              + "WHERE monthly_snapshot_id IN (" + marks + ") ORDER BY monthly_snapshot_id,line_no",
          (org.springframework.jdbc.core.RowCallbackHandler) rs ->
              rows.get(rs.getLong(1)).add(decode(rs.getString(2))), chunk.toArray());
    }
    rows.replaceAll((id, values) -> List.copyOf(values));
    return Collections.unmodifiableMap(rows);
  }

  /** Called within the same transaction that claims/completes the monthly header. */
  public void captureU9(Long snapshotId, QuoteBomReadContext context, String buildBatchId) {
    if (buildBatchId == null || buildBatchId.isBlank()) {
      throw new SourceUnavailableException("月度BOM没有来源批次");
    }
    LocalDate effectiveDate = context.bomEffectiveDate();
    List<BomRawHierarchy> source = hierarchyMapper.selectList(
        Wrappers.<BomRawHierarchy>lambdaQuery()
            .eq(BomRawHierarchy::getPriceOrgCode, context.priceOrgCode())
            .eq(BomRawHierarchy::getTopProductCode, context.productCode())
            .eq(BomRawHierarchy::getSourceType, "U9")
            .eq(BomRawHierarchy::getBomPurpose, DEFAULT_BOM_PURPOSE)
            .eq(BomRawHierarchy::getBuildBatchId, buildBatchId)
            .and(w -> w.isNull(BomRawHierarchy::getEffectiveFrom)
                .or().le(BomRawHierarchy::getEffectiveFrom, effectiveDate))
            .and(w -> w.isNull(BomRawHierarchy::getEffectiveTo)
                .or().ge(BomRawHierarchy::getEffectiveTo, effectiveDate))
            .orderByAsc(BomRawHierarchy::getLevel)
            .orderByAsc(BomRawHierarchy::getPath)
            .orderByAsc(BomRawHierarchy::getSortSeq)
            .orderByAsc(BomRawHierarchy::getId));
    List<BomRawHierarchy> pruned = BomEffectiveTreePruner.prune(source, context.productCode());
    if (pruned.isEmpty()) {
      throw new SourceUnavailableException("月度BOM来源层级已不存在，批次=" + buildBatchId);
    }
    var expanded = expansionService.expand(pruned, context.productCode(), effectiveDate,
        DEFAULT_BOM_PURPOSE, "U9", context.organization());
    if (expanded.hasGaps()) {
      throw new IllegalStateException("本月首次报价的跨组织BOM展开失败：" + expanded.gaps());
    }
    save(snapshotId, expanded.rows());
  }

  @Transactional(rollbackFor = Exception.class)
  public void save(Long snapshotId, List<BomRawHierarchy> rows) {
    if (snapshotId == null || rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("月度BOM明细不能为空");
    }
    // Serialize only writers of this immutable snapshot. Locking an empty detail range
    // also blocks unrelated snapshots being inserted into the same index gap.
    jdbc.queryForObject(
        "SELECT id FROM lp_quote_bom_monthly_snapshot WHERE id=? FOR UPDATE",
        Long.class,
        snapshotId);
    if (!load(snapshotId).isEmpty()) return;
    var frozenRows = detached(rows);
    alternativeMetadata.complete(frozenRows);
    try {
      jdbc.batchUpdate(
          "INSERT INTO lp_quote_bom_monthly_snapshot_detail "
              + "(monthly_snapshot_id, line_no, raw_node_json) VALUES (?, ?, ?)",
          new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index)
                throws SQLException {
              statement.setLong(1, snapshotId);
              statement.setInt(2, index);
              statement.setString(3, encode(frozenRows.get(index)));
            }

            @Override
            public int getBatchSize() {
              return rows.size();
            }
          });
    } catch (DuplicateKeyException concurrentBackfill) {
      // An old REPEATABLE READ view may predate another writer's backfill. Restart the
      // owning transaction to read header and details together, never overwrite them.
      throw new ConcurrencyFailureException("月度BOM明细已由并发任务保存，请重新读取", concurrentBackfill);
    }
  }

  private String encode(BomRawHierarchy row) {
    try {
      return json.writeValueAsString(row);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("无法冻结月度BOM明细", exception);
    }
  }

  private BomRawHierarchy decode(String value) throws SQLException {
    try {
      return json.readValue(value, BomRawHierarchy.class);
    } catch (JsonProcessingException exception) {
      throw new SQLException("月度BOM明细无法解析", exception);
    }
  }

  public static final class SourceUnavailableException extends RuntimeException {
    public SourceUnavailableException(String message) {
      super(message);
    }
  }
}
