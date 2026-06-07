package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.service.MakePartSourceDataService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartSourceDataServiceImpl implements MakePartSourceDataService {

  private static final String MANUFACTURED_SHAPE = "制造件";
  private static final String MAIN_BOM_PURPOSE = "主制造";
  private final BomCostingRowMapper bomCostingRowMapper;
  private final BomU9SourceMapper bomU9SourceMapper;

  public MakePartSourceDataServiceImpl(
      BomCostingRowMapper bomCostingRowMapper,
      BomU9SourceMapper bomU9SourceMapper) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomU9SourceMapper = bomU9SourceMapper;
  }

  @Override
  public List<BomCostingRow> listManufacturedParents(
      String oaNo, String businessUnitType, String buildBatchId) {
    var query =
        Wrappers.lambdaQuery(BomCostingRow.class)
            .eq(BomCostingRow::getShapeAttr, MANUFACTURED_SHAPE)
            .orderByAsc(BomCostingRow::getMaterialCode)
            .orderByDesc(BomCostingRow::getId);
    if (StringUtils.hasText(oaNo)) {
      query.eq(BomCostingRow::getOaNo, oaNo.trim());
    }
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(BomCostingRow::getBusinessUnitType, businessUnitType.trim());
    }
    if (StringUtils.hasText(buildBatchId)) {
      query.eq(BomCostingRow::getBuildBatchId, buildBatchId.trim());
    }
    List<BomCostingRow> rows = bomCostingRowMapper.selectList(query);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .filter(row -> MANUFACTURED_SHAPE.equals(trim(row.getShapeAttr())))
        .toList();
  }

  @Override
  public List<BomU9Source> listDedupedChildren(String parentMaterialNo, LocalDate asOfDate) {
    if (!StringUtils.hasText(parentMaterialNo)) {
      return List.of();
    }
    var query = Wrappers.lambdaQuery(BomU9Source.class)
        .eq(BomU9Source::getParentMaterialNo, parentMaterialNo.trim())
        .eq(BomU9Source::getBomPurpose, MAIN_BOM_PURPOSE);
    if (asOfDate != null) {
      query.and(q -> q.le(BomU9Source::getEffectiveFrom, asOfDate)
          .or()
          .isNull(BomU9Source::getEffectiveFrom));
      query.and(q -> q.ge(BomU9Source::getEffectiveTo, asOfDate)
          .or()
          .isNull(BomU9Source::getEffectiveTo));
    }
    List<BomU9Source> rows =
        bomU9SourceMapper.selectList(
            query.orderByAsc(BomU9Source::getChildSeq)
                .orderByDesc(BomU9Source::getId));
    return dedupeChildren(rows);
  }

  List<BomU9Source> dedupeChildren(List<BomU9Source> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<BomU9Source> ordered = new ArrayList<>(rows);
    ordered.sort(this::compareChildPriority);
    Map<String, BomU9Source> distinct = new LinkedHashMap<>();
    for (BomU9Source row : ordered) {
      String parent = trim(row.getParentMaterialNo());
      String child = trim(row.getChildMaterialNo());
      if (parent == null || child == null) {
        continue;
      }
      // 去重只合并同一个 parent + child 的重复行；去重后多个不同 child 必须全部保留生成多行。
      distinct.putIfAbsent(parent + "\u0001" + child, row);
    }
    return new ArrayList<>(distinct.values());
  }

  private int compareChildPriority(BomU9Source left, BomU9Source right) {
    int seqCompare = nullLast(left.getChildSeq(), right.getChildSeq());
    if (seqCompare != 0) {
      return seqCompare;
    }
    return nullLastDesc(left.getId(), right.getId());
  }

  private int nullLast(Integer left, Integer right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    return left.compareTo(right);
  }

  private int nullLastDesc(Long left, Long right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    return right.compareTo(left);
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
