package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MakePartNoScrapConfirmation;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link MakePartNoScrapConfirmationMapper} 真实 DB CRUD 验证。 */
@Tag("integration")
@DisplayName("MakePartNoScrapConfirmationMapper · lp_make_part_no_scrap_confirmation CRUD")
class MakePartNoScrapConfirmationMapperTest extends BomMapperTestBase {

  private final String materialNo = "301300339-" + UUID.randomUUID();

  @Autowired private MakePartNoScrapConfirmationMapper mapper;

  @BeforeAll
  static void initNoScrapTable() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS lp_make_part_no_scrap_confirmation (
            id BIGINT NOT NULL AUTO_INCREMENT,
            business_unit_type VARCHAR(32) NOT NULL DEFAULT '',
            material_no VARCHAR(64) NOT NULL,
            material_name VARCHAR(180) DEFAULT NULL,
            effective_from_month VARCHAR(7) NOT NULL,
            effective_to_month VARCHAR(7) DEFAULT NULL,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            confirm_reason VARCHAR(500) NOT NULL,
            source_oa_no VARCHAR(64) DEFAULT NULL,
            source_gap_id BIGINT DEFAULT NULL,
            confirmed_by VARCHAR(64) DEFAULT NULL,
            confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            revoked_by VARCHAR(64) DEFAULT NULL,
            revoked_at DATETIME DEFAULT NULL,
            revoke_reason VARCHAR(500) DEFAULT NULL,
            active_effective_from_month VARCHAR(7)
              GENERATED ALWAYS AS (
                CASE WHEN status = 'ACTIVE' THEN effective_from_month ELSE NULL END
              ) STORED,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_no_scrap_active_month (
              business_unit_type,
              material_no,
              active_effective_from_month
            ),
            KEY idx_no_scrap_material_period (
              business_unit_type,
              material_no,
              effective_from_month,
              effective_to_month,
              status
            )
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM lp_make_part_no_scrap_confirmation WHERE material_no = '" + materialNo + "'");
    }
  }

  @Test
  @DisplayName("insert/selectById：可保存 ACTIVE 无废料人工确认")
  void insertAndSelectActiveConfirmation() {
    MakePartNoScrapConfirmation confirmation = newConfirmation("2026-06", null);

    int affected = mapper.insert(confirmation);

    assertThat(affected).isEqualTo(1);
    assertThat(confirmation.getId()).isNotNull().isPositive();
    MakePartNoScrapConfirmation loaded = mapper.selectById(confirmation.getId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(loaded.getMaterialNo()).isEqualTo(materialNo);
    assertThat(loaded.getStatus()).isEqualTo(MakePartNoScrapConfirmation.STATUS_ACTIVE);
    assertThat(loaded.getConfirmReason()).isEqualTo("确认该料号当前期间确实无废料产生");
    assertThat(loaded.getSourceOaNo()).isEqualTo("FI-SC-006-20260108-109");
    assertThat(loaded.getSourceGapId()).isEqualTo(301300339L);
  }

  @Test
  @DisplayName("当前月份有效查询：开始月命中，结束月后不命中")
  void queryEffectiveConfirmationByMonth() {
    mapper.insert(newConfirmation("2026-05", "2026-07"));

    List<MakePartNoScrapConfirmation> matched = selectEffective("2026-06");
    List<MakePartNoScrapConfirmation> expired = selectEffective("2026-08");

    assertThat(matched).hasSize(1);
    assertThat(matched.get(0).getEffectiveFromMonth()).isEqualTo("2026-05");
    assertThat(expired).isEmpty();
  }

  @Test
  @DisplayName("撤销后不再命中有效查询")
  void revokedConfirmationIsNotEffective() {
    MakePartNoScrapConfirmation confirmation = newConfirmation("2026-06", null);
    mapper.insert(confirmation);
    confirmation.setStatus(MakePartNoScrapConfirmation.STATUS_REVOKED);
    confirmation.setRevokedBy("qa");
    confirmation.setRevokedAt(LocalDateTime.of(2026, 6, 2, 11, 0));
    confirmation.setRevokeReason("业务确认后撤销");

    int affected = mapper.updateById(confirmation);

    assertThat(affected).isEqualTo(1);
    assertThat(selectEffective("2026-06")).isEmpty();
  }

  @Test
  @DisplayName("同一事业部同一料号同一开始月不能重复 ACTIVE")
  void duplicateActiveConfirmationRejected() {
    mapper.insert(newConfirmation("2026-06", null));

    assertThatThrownBy(() -> mapper.insert(newConfirmation("2026-06", "2026-12")))
        .hasMessageContaining("uk_no_scrap_active_month");
  }

  private List<MakePartNoScrapConfirmation> selectEffective(String periodMonth) {
    return mapper.selectList(
        Wrappers.lambdaQuery(MakePartNoScrapConfirmation.class)
            .eq(MakePartNoScrapConfirmation::getBusinessUnitType, "COMMERCIAL")
            .eq(MakePartNoScrapConfirmation::getMaterialNo, materialNo)
            .eq(MakePartNoScrapConfirmation::getStatus, MakePartNoScrapConfirmation.STATUS_ACTIVE)
            .le(MakePartNoScrapConfirmation::getEffectiveFromMonth, periodMonth)
            .and(
                q -> q.isNull(MakePartNoScrapConfirmation::getEffectiveToMonth)
                    .or()
                    .ge(MakePartNoScrapConfirmation::getEffectiveToMonth, periodMonth)));
  }

  private MakePartNoScrapConfirmation newConfirmation(
      String effectiveFromMonth, String effectiveToMonth) {
    MakePartNoScrapConfirmation confirmation = new MakePartNoScrapConfirmation();
    confirmation.setBusinessUnitType("COMMERCIAL");
    confirmation.setMaterialNo(materialNo);
    confirmation.setMaterialName("301300339测试料号");
    confirmation.setEffectiveFromMonth(effectiveFromMonth);
    confirmation.setEffectiveToMonth(effectiveToMonth);
    confirmation.setStatus(MakePartNoScrapConfirmation.STATUS_ACTIVE);
    confirmation.setConfirmReason("确认该料号当前期间确实无废料产生");
    confirmation.setSourceOaNo("FI-SC-006-20260108-109");
    confirmation.setSourceGapId(301300339L);
    confirmation.setConfirmedBy("qa");
    confirmation.setConfirmedAt(LocalDateTime.of(2026, 6, 2, 10, 0));
    return confirmation;
  }
}
