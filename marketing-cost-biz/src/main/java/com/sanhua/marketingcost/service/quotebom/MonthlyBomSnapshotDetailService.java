package com.sanhua.marketingcost.service.quotebom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.impl.BomEffectiveTreePruner;
import com.sanhua.marketingcost.service.impl.PlateCommercialMakeBomExpansionService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Service;

/** Owns the immutable hierarchy rows belonging to one product/organization/accounting month. */
@Service
public class MonthlyBomSnapshotDetailService {

  private static final String DEFAULT_BOM_PURPOSE = "主制造";

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final BomRawHierarchyMapper hierarchyMapper;
  private final PlateCommercialMakeBomExpansionService expansionService;

  public MonthlyBomSnapshotDetailService(JdbcTemplate jdbc, ObjectMapper json,
      BomRawHierarchyMapper hierarchyMapper,
      PlateCommercialMakeBomExpansionService expansionService) {
    this.jdbc = jdbc;
    this.json = json;
    this.hierarchyMapper = hierarchyMapper;
    this.expansionService = expansionService;
  }

  public List<BomRawHierarchy> load(Long snapshotId) {
    if (snapshotId == null) return List.of();
    return jdbc.query("SELECT raw_node_json FROM lp_quote_bom_monthly_snapshot_detail "
            + "WHERE monthly_snapshot_id = ? ORDER BY line_no",
        (rs, rowNum) -> decode(rs.getString(1)), snapshotId);
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

  public void save(Long snapshotId, List<BomRawHierarchy> rows) {
    if (snapshotId == null || rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("月度BOM明细不能为空");
    }
    if (!load(snapshotId).isEmpty()) return;
    jdbc.batchUpdate("INSERT INTO lp_quote_bom_monthly_snapshot_detail "
        + "(monthly_snapshot_id, line_no, raw_node_json) VALUES (?, ?, ?)",
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(java.sql.PreparedStatement statement, int index) throws SQLException {
            statement.setLong(1, snapshotId);
            statement.setInt(2, index);
            statement.setString(3, encode(rows.get(index)));
          }

          @Override
          public int getBatchSize() {
            return rows.size();
          }
        });
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
