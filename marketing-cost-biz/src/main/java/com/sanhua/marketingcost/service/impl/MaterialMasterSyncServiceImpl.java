package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.SyncMaterialMasterRow;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * T15：主档同步实现。Python sync_material_master.py 的 Java 版。
 *
 * <p>事务策略：{@code Propagation.REQUIRES_NEW} —— 跑一个独立子事务。即使外层试算后续步骤
 * （部品/费用）失败回滚，主档增量也已落地。这是符合直觉的：sync 是 idempotent，next 试算重跑
 * 就直接命中 ON DUPLICATE KEY UPDATE 刷字段，不需要重做。
 *
 * <p>批量大小：典型 OA 涉及 30-100 料号，1 个 SQL 全装下，不需要分批。
 */
@Service
public class MaterialMasterSyncServiceImpl implements MaterialMasterSyncService {

  private static final Logger log = LoggerFactory.getLogger(MaterialMasterSyncServiceImpl.class);
  private static final BigDecimal G_TO_KG = new BigDecimal("1000");

  private final BomCostingRowMapper bomCostingRowMapper;
  private final MaterialMasterRawMapper rawMapper;
  private final MaterialMasterMapper masterMapper;

  public MaterialMasterSyncServiceImpl(
      BomCostingRowMapper bomCostingRowMapper,
      MaterialMasterRawMapper rawMapper,
      MaterialMasterMapper masterMapper) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.rawMapper = rawMapper;
    this.masterMapper = masterMapper;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public SyncResult syncByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      throw new RuntimeException("oaNo 为空");
    }
    String oa = oaNo.trim();

    // 1) OA 涉及的去重料号
    List<String> codes = bomCostingRowMapper.selectDistinctMaterialCodesByOaNo(oa);
    if (codes == null || codes.isEmpty()) {
      log.warn("OA {} 在 lp_bom_costing_row 无 BOM 行，跳过同步", oa);
      return new SyncResult(0, 0, 0, null);
    }

    // 2) staging 最新批次
    String batchId = rawMapper.selectLatestBatchId();
    if (!StringUtils.hasText(batchId)) {
      throw new RuntimeException("staging 表 lp_material_master_raw 无数据");
    }

    // 3) 拉这些料号在该批次的 staging 行
    List<MaterialMasterRaw> raws =
        rawMapper.selectList(
            Wrappers.lambdaQuery(MaterialMasterRaw.class)
                .in(MaterialMasterRaw::getMaterialCode, codes)
                .eq(MaterialMasterRaw::getImportBatchId, batchId));
    if (raws == null || raws.isEmpty()) {
      log.warn("OA {} 涉及 {} 料号，staging 命中 0 行（批次 {}）", oa, codes.size(), batchId);
      return new SyncResult(codes.size(), 0, 0, batchId);
    }

    // 4) 类型转换 + BU 推断 → SyncMaterialMasterRow
    List<SyncMaterialMasterRow> rows = new ArrayList<>(raws.size());
    for (MaterialMasterRaw r : raws) {
      rows.add(toSyncRow(r));
    }

    // 5) 批量 UPSERT
    int affected = masterMapper.upsertBatch(rows);
    log.info(
        "T15 主档同步: oa={} codes={} stagingHits={} affected={} batch={}",
        oa, codes.size(), raws.size(), affected, batchId);
    return new SyncResult(codes.size(), raws.size(), affected, batchId);
  }

  /** staging 行 → 同步行：类型转换（VARCHAR → DECIMAL/INT，失败 null）+ g→kg + BU 推断 */
  private SyncMaterialMasterRow toSyncRow(MaterialMasterRaw r) {
    SyncMaterialMasterRow row = new SyncMaterialMasterRow();
    row.setMaterialCode(r.getMaterialCode());
    row.setMaterialName(r.getMaterialName());
    row.setItemSpec(r.getMaterialSpec());
    row.setItemModel(r.getMaterialModel());
    row.setDrawingNo(r.getDrawingNo());
    row.setShapeAttr(r.getShapeAttr());
    row.setMaterial(r.getGlobalSeg4Material());

    BigDecimal netG = parseDecimal(r.getGlobalSeg5NetWeight());
    row.setNetWeightKg(netG == null ? null : netG.divide(G_TO_KG, 6, RoundingMode.HALF_UP));
    row.setGrossWeightG(parseDecimal(r.getGlobalSeg9GrossWeight()));

    row.setBusinessUnitType(inferBu(r.getProductionDivision()));
    row.setBizUnit(r.getProductionDivision());
    row.setProductionDept(r.getDepartmentName());
    row.setProductionWorkshop(r.getDepartmentName());

    row.setCostElement(r.getCostElement());
    row.setFinanceCategory(r.getFinanceCategory());
    row.setPurchaseCategory(r.getPurchaseCategory());
    row.setProductionCategory(r.getProductionCategory());
    row.setSalesCategory(r.getSalesCategory());
    row.setMainCategoryCode(r.getMainCategoryCode());
    row.setMainCategoryName(r.getMainCategoryName());

    row.setProductPropertyClass(r.getGlobalSeg7ProductPropertyClass());
    row.setProductProperty(parseDecimal(r.getPrivateSeg24ProductProperty()));
    row.setLossRate(parseDecimal(r.getGlobalSeg8LossRate()));
    row.setDailyCapacity(parseDecimal(r.getPrivateSeg25DailyCapacity()));
    row.setLeadTimeDays(parseInt(r.getPrivateSeg26LeadTime()));
    row.setPackageSize(r.getGlobalSeg15PackageSize());

    row.setDefaultSupplier(r.getDefaultSupplier());
    row.setDefaultBuyer(r.getDefaultBuyer());
    row.setDefaultPlanner(r.getDefaultPlanner());

    row.setLegacyU9Code(r.getLegacyU9Code());
    row.setImportBatchId(r.getImportBatchId());
    row.setSource("u9_master");
    return row;
  }

  /** "0.05" / "5%" / "1,234.56" / null / "" / "abc" → BigDecimal or null */
  static BigDecimal parseDecimal(String value) {
    if (!StringUtils.hasText(value)) return null;
    String s = value.trim().replace(",", "");
    if (s.endsWith("%")) s = s.substring(0, s.length() - 1);
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** "30" / "30.0" → 30；非数字 / null → null */
  static Integer parseInt(String value) {
    BigDecimal d = parseDecimal(value);
    return d == null ? null : d.intValue();
  }

  /** production_division 含"家用"→HOUSEHOLD，其他默认 COMMERCIAL */
  static String inferBu(String productionDivision) {
    if (!StringUtils.hasText(productionDivision)) return "COMMERCIAL";
    return productionDivision.contains("家用") ? "HOUSEHOLD" : "COMMERCIAL";
  }
}
