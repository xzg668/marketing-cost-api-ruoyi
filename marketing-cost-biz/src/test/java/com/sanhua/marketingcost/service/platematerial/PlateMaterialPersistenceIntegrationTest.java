package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class PlateMaterialPersistenceIntegrationTest extends BomMapperTestBase {
  private static final AtomicLong IDS = new AtomicLong(98_700_000);
  @Autowired private QuoteTechDataVersionMapper versions;
  @Autowired private PlateMaterialMappingMapper mappings;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private JdbcTemplate jdbc;

  @Test void mappingUsesRealMapperAndRetainsIndependentBusinessIdentity() {
    var mapping = new PlateMaterialMapping();
    mapping.setBusinessUnitType("COMMERCIAL"); mapping.setNormalizedModel("B12-8H-V20");
    mapping.setPlateMaterial("水水316L"); mapping.setSourceType("MANUAL");
    mapping.setCreatedBy(101L); mapping.setUpdatedBy(101L);
    assertThat(mappings.insert(mapping)).isOne();
    var loaded = mappings.selectById(mapping.getId());
    assertThat(loaded.getNormalizedModel()).isEqualTo("B12-8H-V20");
    assertThat(loaded.getPlateMaterial()).isEqualTo("水水316L");
    assertThat(loaded.getRowVersion()).isZero();
    assertThat(loaded.getSourceFileName()).isNull();
  }

  @Test void explicitDraftSqlRoundTripsMaterialWithoutLosingOtherFieldsAndRejectsStaleUpdate() {
    var version = draft();
    assertThat(codec.plateMaterial(version)).isNull();
    version.setPlateMaterialJson(codec.plateMaterialJson(PlateMaterialContractTest.locked()));
    assertThat(versions.updateDraftWithVersion(version, 0, LocalDateTime.now())).isOne();
    var loaded = versions.selectById(version.getId());
    assertThat(codec.plateMaterial(loaded)).isEqualTo(PlateMaterialContractTest.locked());
    assertThat(loaded.getProductProperty()).isEqualTo("非标品");
    assertThat(codec.productFees(loaded).unitToolingFee()).isEqualByComparingTo("0.25");
    assertThat(loaded.getRowVersion()).isOne();
    assertThat(versions.updateDraftWithVersion(version, 0, LocalDateTime.now())).isZero();
    assertThat(codec.plateMaterial(versions.selectById(version.getId()))).isEqualTo(PlateMaterialContractTest.locked());
  }

  @Test void savedMaterialChangesFingerprintAndFrozenSnapshotRejectsSqlMutation() {
    var version = draft();
    String previous = codec.fingerprint(version, List.of(), List.of(), List.of(), List.of());
    version.setPlateMaterialJson(codec.plateMaterialJson(PlateMaterialContractTest.locked()));
    assertThat(versions.updateDraftWithVersion(version, 0, LocalDateTime.now())).isOne();
    var loaded = versions.selectById(version.getId());
    String fingerprint = codec.fingerprint(loaded, List.of(), List.of(), List.of(), List.of());
    assertThat(fingerprint).isNotEqualTo(previous);
    assertThat(versions.transitionStatus(version.getId(), "DRAFT", "FROZEN", 1, fingerprint,
        codec.referenceSnapshotJson(List.of()), 101L, LocalDateTime.now())).isOne();
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_tech_data_version SET plate_material_json=NULL WHERE id=?", version.getId()))
        .hasMessageContaining("IMMUTABLE");
    assertThat(versions.selectById(version.getId()).getContentFingerprint()).isEqualTo(fingerprint);
    assertThat(codec.plateMaterial(versions.selectById(version.getId()))).isEqualTo(PlateMaterialContractTest.locked());
  }

  private QuoteTechDataVersion draft() {
    long item = IDS.incrementAndGet(); String key = "PMM-" + UUID.randomUUID();
    String activeKey = "ITEM:" + item + ":MONTH:2026-10";
    jdbc.update("""
        INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,
          business_unit_type,applicable_org_code,assignee_user_id,assignee_name,active_flag,active_lock_key)
        VALUES (?,987001,?,?,'2026-10','COMMERCIAL','210',101,'PMM技术员',1,?)
        """, key, item, key, activeKey);
    Long task = jdbc.queryForObject("SELECT id FROM lp_quote_tech_task WHERE task_no=?", Long.class, key);
    jdbc.update("""
        INSERT INTO lp_quote_tech_product(task_id,oa_form_item_id,level_no,source_model,quote_no,accounting_month,
          source_snapshot_json,source_fingerprint,active_flag,active_lock_key,content_schema_version)
        VALUES (?,?,1,'B12-8H-V20',?,'2026-10','{"sourceModel":"B12-8H-V20"}',REPEAT('a',64),1,?,2)
        """, task, item, key, activeKey);
    Long product = jdbc.queryForObject("SELECT id FROM lp_quote_tech_product WHERE task_id=?", Long.class, task);
    var version = new QuoteTechDataVersion();
    version.setProductId(product); version.setVersionNo(1); version.setVersionStatus("DRAFT");
    version.setContentSchemaVersion(2); version.setProductModel("B12-8H-V20");
    version.setProductProperty("非标品"); version.setNewProductFlag(0);
    version.setProductFeesJson("{\"includesNewToolingMouldCertificationFee\":false,\"unitToolingFee\":0.25,\"currency\":\"CNY\"}");
    version.setSourceFactsJson("{\"productSourceJson\":\"{}\",\"modules\":[]}");
    version.setRowVersion(0); version.setCreatedBy(101L); version.setUpdatedBy(101L);
    assertThat(versions.insert(version)).isOne();
    return versions.selectById(version.getId());
  }
}
