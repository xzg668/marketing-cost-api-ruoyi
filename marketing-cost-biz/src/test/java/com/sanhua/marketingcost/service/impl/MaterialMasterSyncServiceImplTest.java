package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.SyncMaterialMasterRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService.SyncResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T15 单测：MaterialMasterSyncServiceImpl 核心路径 + 类型转换/BU 推断辅助 */
class MaterialMasterSyncServiceImplTest {

  // ============== 业务流程 ==============

  @Test
  @DisplayName("syncByOaNo 正常路径：BOM 有码 + staging 命中 → upsertBatch 被调用")
  void sync_happyPath() {
    BomCostingRowMapper bomMapper = mock(BomCostingRowMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(bomMapper.selectDistinctMaterialCodesByOaNo("OA-1"))
        .thenReturn(List.of("M-001", "M-002"));
    when(rawMapper.selectLatestActiveBatchId(null)).thenReturn("u9-batch-x");

    MaterialMasterRaw r1 = newRaw("M-001", "净重克=1500", "5%", "30", "商用部品");
    MaterialMasterRaw r2 = newRaw("M-002", "1500", "0.05", "30", "家用部品");
    when(rawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of(r1, r2));

    when(masterMapper.upsertBatch(anyList())).thenReturn(2);

    MaterialMasterSyncServiceImpl svc =
        new MaterialMasterSyncServiceImpl(bomMapper, rawMapper, masterMapper);
    SyncResult res = svc.syncByOaNo("OA-1");

    assertThat(res.distinctCodes()).isEqualTo(2);
    assertThat(res.stagingHits()).isEqualTo(2);
    assertThat(res.affectedRows()).isEqualTo(2);
    assertThat(res.batchId()).isEqualTo("u9-batch-x");
    verify(rawMapper).selectLatestActiveBatchId(null);
    verify(rawMapper).selectByLatestBatchAndCodes(any(), any());

    // 验证 upsertBatch 拿到的 row 字段映射正确
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<SyncMaterialMasterRow>> captor = ArgumentCaptor.forClass(List.class);
    verify(masterMapper).upsertBatch(captor.capture());
    List<SyncMaterialMasterRow> rows = captor.getValue();
    assertThat(rows).hasSize(2);
    // r1: net_weight 字段非数字 "净重克=1500" → null
    assertThat(rows.get(0).getNetWeightKg()).isNull();
    assertThat(rows.get(0).getBusinessUnitType()).isEqualTo("COMMERCIAL");
    // r2: 当前 U9 文件净重按 kg 口径，不再除以 1000；BU = HOUSEHOLD（含"家用"）
    assertThat(rows.get(1).getNetWeightKg()).isEqualByComparingTo(new BigDecimal("1500"));
    assertThat(rows.get(1).getBusinessUnitType()).isEqualTo("HOUSEHOLD");
  }

  @Test
  @DisplayName("U9 空值、主表非空值 → 同步保留主表历史值")
  void sync_preservesExistingWhenU9Blank() {
    BomCostingRowMapper bomMapper = mock(BomCostingRowMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(bomMapper.selectDistinctMaterialCodesByOaNo("OA-KEEP")).thenReturn(List.of("M-KEEP"));
    when(rawMapper.selectLatestActiveBatchId(null)).thenReturn("u9-batch-x");
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode("M-KEEP");
    raw.setMaterialName("");
    raw.setGlobalSeg5NetWeight("");
    raw.setProductionDivision("");
    raw.setImportBatchId("u9-batch-x");
    when(rawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of(raw));

    MaterialMaster existing = new MaterialMaster();
    existing.setMaterialCode("M-KEEP");
    existing.setMaterialName("历史名称");
    existing.setNetWeightKg(new BigDecimal("3.25"));
    existing.setBusinessUnitType("HOUSEHOLD");
    when(masterMapper.selectList(any())).thenReturn(List.of(existing));
    when(masterMapper.upsertBatch(anyList())).thenReturn(1);

    MaterialMasterSyncServiceImpl svc =
        new MaterialMasterSyncServiceImpl(bomMapper, rawMapper, masterMapper);
    svc.syncByOaNo("OA-KEEP");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<SyncMaterialMasterRow>> captor = ArgumentCaptor.forClass(List.class);
    verify(masterMapper).upsertBatch(captor.capture());
    SyncMaterialMasterRow row = captor.getValue().get(0);
    assertThat(row.getMaterialName()).isEqualTo("历史名称");
    assertThat(row.getNetWeightKg()).isEqualByComparingTo(new BigDecimal("3.25"));
    assertThat(row.getBusinessUnitType()).isEqualTo("HOUSEHOLD");
  }

  @Test
  @DisplayName("U9 非空、主表空值 → 同步写入 U9 值")
  void sync_writesU9WhenMasterEmpty() {
    BomCostingRowMapper bomMapper = mock(BomCostingRowMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(bomMapper.selectDistinctMaterialCodesByOaNo("OA-FILL")).thenReturn(List.of("M-FILL"));
    when(rawMapper.selectLatestActiveBatchId(null)).thenReturn("u9-batch-x");
    MaterialMasterRaw raw = newRaw("M-FILL", "2.75", null, null, "商用部品");
    raw.setMainCategoryCode("101001001");
    when(rawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of(raw));
    when(masterMapper.selectList(any())).thenReturn(List.of());
    when(masterMapper.upsertBatch(anyList())).thenReturn(1);

    MaterialMasterSyncServiceImpl svc =
        new MaterialMasterSyncServiceImpl(bomMapper, rawMapper, masterMapper);
    svc.syncByOaNo("OA-FILL");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<SyncMaterialMasterRow>> captor = ArgumentCaptor.forClass(List.class);
    verify(masterMapper).upsertBatch(captor.capture());
    SyncMaterialMasterRow row = captor.getValue().get(0);
    assertThat(row.getMaterialName()).isEqualTo("name-M-FILL");
    assertThat(row.getNetWeightKg()).isEqualByComparingTo(new BigDecimal("2.75"));
    assertThat(row.getMainCategoryCode()).isEqualTo("101001001");
  }

  @Test
  @DisplayName("OA 无 BOM 行 → 返回空 SyncResult，不调 upsert")
  void sync_noBomRows() {
    BomCostingRowMapper bomMapper = mock(BomCostingRowMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(bomMapper.selectDistinctMaterialCodesByOaNo("OA-EMPTY"))
        .thenReturn(List.of());

    MaterialMasterSyncServiceImpl svc =
        new MaterialMasterSyncServiceImpl(bomMapper, rawMapper, masterMapper);
    SyncResult res = svc.syncByOaNo("OA-EMPTY");

    assertThat(res.distinctCodes()).isZero();
    assertThat(res.stagingHits()).isZero();
    assertThat(res.affectedRows()).isZero();
    verify(masterMapper, never()).upsertBatch(anyList());
  }

  @Test
  @DisplayName("staging 表无数据 → 抛 'staging 表 lp_material_master_raw 无数据'")
  void sync_stagingEmpty() {
    BomCostingRowMapper bomMapper = mock(BomCostingRowMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(bomMapper.selectDistinctMaterialCodesByOaNo("OA-X"))
        .thenReturn(List.of("M-001"));
    when(rawMapper.selectLatestActiveBatchId(null)).thenReturn(null);

    MaterialMasterSyncServiceImpl svc =
        new MaterialMasterSyncServiceImpl(bomMapper, rawMapper, masterMapper);
    assertThatThrownBy(() -> svc.syncByOaNo("OA-X"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("staging");
  }

  // ============== 工具方法 ==============

  @Test
  @DisplayName("parseDecimal: 数字 / 百分号 / 千分逗号 / 非数字 / 空")
  void parseDecimal_variants() {
    assertThat(MaterialMasterSyncServiceImpl.parseDecimal("0.05"))
        .isEqualByComparingTo(new BigDecimal("0.05"));
    assertThat(MaterialMasterSyncServiceImpl.parseDecimal("5%"))
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(MaterialMasterSyncServiceImpl.parseDecimal("1,234.56"))
        .isEqualByComparingTo(new BigDecimal("1234.56"));
    assertThat(MaterialMasterSyncServiceImpl.parseDecimal("abc")).isNull();
    assertThat(MaterialMasterSyncServiceImpl.parseDecimal("")).isNull();
    assertThat(MaterialMasterSyncServiceImpl.parseDecimal(null)).isNull();
  }

  @Test
  @DisplayName("inferBu: 含'家用' → HOUSEHOLD，其他默认 COMMERCIAL")
  void inferBu_rules() {
    assertThat(MaterialMasterSyncServiceImpl.inferBu("家用电器事业部")).isEqualTo("HOUSEHOLD");
    assertThat(MaterialMasterSyncServiceImpl.inferBu("商用部品事业部")).isEqualTo("COMMERCIAL");
    assertThat(MaterialMasterSyncServiceImpl.inferBu(null)).isEqualTo("COMMERCIAL");
    assertThat(MaterialMasterSyncServiceImpl.inferBu("")).isEqualTo("COMMERCIAL");
  }

  // ============== 辅助 ==============

  private static MaterialMasterRaw newRaw(
      String code, String netWeight, String lossRate, String leadTime, String division) {
    MaterialMasterRaw r = new MaterialMasterRaw();
    r.setMaterialCode(code);
    r.setMaterialName("name-" + code);
    r.setGlobalSeg5NetWeight(netWeight);
    r.setGlobalSeg8LossRate(lossRate);
    r.setPrivateSeg26LeadTime(leadTime);
    r.setProductionDivision(division);
    r.setImportBatchId("u9-batch-x");
    return r;
  }
}
