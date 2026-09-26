package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("电子图库源节点仓储真实 MySQL")
class ElectronicDrawingSourceNodeRepositoryIntegrationTest extends BomMapperTestBase {

  private static final LocalDateTime SHANGHAI_ACQUIRED_AT =
      LocalDateTime.of(2026, 8, 30, 8, 30, 0);
  private static final LocalDateTime SHANGHAI_RESOLVED_AT =
      LocalDateTime.of(2026, 8, 30, 9, 15, 0);

  @Autowired private ElectronicDrawingSourceNodeRepository repository;
  @Autowired private QuoteBomSupplementVersionMapper versionMapper;
  @Autowired private QuoteBomSupplementDetailMapper detailMapper;
  @Autowired private JdbcTemplate jdbc;

  private Long versionId;
  private Long detailId;

  @BeforeAll
  static void ensureSchemas() throws Exception {
    int index = 0;
    for (String resource : List.of(
        "/db/V142__quote_bom_preparation_schema.sql",
        "/db/V242__electronic_drawing_source_node.sql",
        "/db/V269__electronic_drawing_weight_unit.sql")) {
      String target = "/tmp/EDSOURCE-" + (++index) + ".sql";
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource(resource), target);
      ExecResult result = MYSQL.execInContainer(
          "sh", "-c",
          "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
              + " " + MYSQL.getDatabaseName() + " < " + target);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(resource + " 执行失败：" + result.getStderr());
      }
    }
  }

  @AfterEach
  void clean() {
    if (versionId != null) {
      jdbc.update(
          "DELETE FROM lp_electronic_drawing_source_node WHERE supplement_version_id=?",
          versionId);
      if (detailId != null) {
        jdbc.update("DELETE FROM lp_quote_bom_supplement_detail WHERE id=?", detailId);
      }
      jdbc.update("DELETE FROM lp_quote_bom_supplement_version WHERE id=?", versionId);
    }
  }

  @Test
  void persistsOriginalUnitsAndMigrationDoesNotBackfillFrozenHistory() throws Exception {
    insertVersion("DRAFT", true);
    var grams = sourceNode(1);
    grams.setReferenceWeight(new BigDecimal("12"));
    var kilograms = sourceNode(2);
    kilograms.setReferenceWeight(new BigDecimal("0.012"));
    kilograms.setReferenceWeightUnit("kg");
    var historical = sourceNode(3);
    historical.setReferenceWeight(new BigDecimal("182.3"));
    historical.setReferenceWeightUnit(null);
    repository.insertAll(versionId, List.of(grams, kilograms, historical));
    jdbc.update("UPDATE lp_quote_bom_supplement_version SET version_status='APPROVED' WHERE id=?", versionId);

    ensureSchemas();

    var stored = repository.findByVersionId(versionId);
    assertThat(stored).extracting(ElectronicDrawingSourceNode::getReferenceWeightUnit)
        .containsExactly("g", "kg", null);
    assertThat(stored.get(0).getReferenceWeight()).isEqualByComparingTo("12");
    assertThat(stored.get(1).getReferenceWeight()).isEqualByComparingTo("0.012");
    assertThat(stored.get(2).getReferenceWeight()).isEqualByComparingTo("182.3");
    assertThat(versionMapper.selectById(versionId).getVersionStatus()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("40行写入读取、待处理查询、唯一键和已发布不可覆盖同时成立")
  void persistsFortyRowsAndProtectsPublishedVersion() {
    QuoteBomSupplementVersion version = insertVersion("DRAFT", true);
    List<ElectronicDrawingSourceNode> nodes = fortyNodes();

    repository.insertAll(versionId, nodes);

    List<ElectronicDrawingSourceNode> stored = repository.findByVersionId(versionId);
    assertThat(stored).hasSize(40);
    assertThat(stored).extracting(ElectronicDrawingSourceNode::getSourceSequence)
        .containsExactlyElementsOf(nodes.stream().map(
            ElectronicDrawingSourceNode::getSourceSequence).toList());
    assertThat(stored.getFirst().getSourceRowNo()).isEqualTo(8);
    assertThat(stored.getFirst().getQty()).isEqualByComparingTo("1.01");
    assertThat(stored.getFirst().getReferenceWeight()).isEqualByComparingTo("0.1");
    assertThat(stored.getFirst().getReferenceWeightUnit()).isEqualTo("g");
    assertThat(stored.getFirst().getResolvedBy()).isEqualTo("SYSTEM");
    assertThat(repository.findPendingByVersionId(versionId))
        .extracting(ElectronicDrawingSourceNode::getSourceSequence)
        .containsExactly("39", "40");

    ElectronicDrawingSourceNode duplicate = sourceNode(41);
    duplicate.setSourceSequence("1");
    assertThatThrownBy(() -> repository.insertAll(versionId, List.of(duplicate)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(repository.findByVersionId(versionId)).hasSize(40);

    ElectronicDrawingSourceNode manual = stored.get(38);
    String originalDrawing = manual.getDrawingCode();
    String originalName = manual.getSourceName();
    BigDecimal originalQty = manual.getQty();
    assertThat(repository.updateResolution(
        manual.getId(),
        ElectronicDrawingSourceNode.MATCH_UNMATCHED,
        ElectronicDrawingSourceNode.MATCH_MANUAL,
        "9990000053986",
        "USER:701",
        SHANGHAI_RESOLVED_AT)).isTrue();
    ElectronicDrawingSourceNode resolved = repository.findByVersionId(versionId).get(38);
    assertThat(resolved.getDrawingCode()).isEqualTo(originalDrawing);
    assertThat(resolved.getSourceName()).isEqualTo(originalName);
    assertThat(resolved.getQty()).isEqualByComparingTo(originalQty);
    assertThat(resolved.getResolvedMaterialCode()).isEqualTo("9990000053986");
    assertThat(resolved.getResolvedBy()).isEqualTo("USER:701");
    assertThat(resolved.getResolvedAt()).isEqualTo(SHANGHAI_RESOLVED_AT);

    QuoteBomSupplementVersion approval = new QuoteBomSupplementVersion();
    approval.setId(versionId);
    approval.setVersionStatus("APPROVED");
    assertThat(versionMapper.updateById(approval)).isOne();
    ElectronicDrawingSourceNode ambiguous = repository.findByVersionId(versionId).get(39);
    assertThat(repository.updateResolution(
        ambiguous.getId(),
        ElectronicDrawingSourceNode.MATCH_AMBIGUOUS,
        ElectronicDrawingSourceNode.MATCH_MANUAL,
        "M-LOCKED",
        "USER:701",
        SHANGHAI_RESOLVED_AT.plusMinutes(1))).isFalse();
    ElectronicDrawingSourceNode locked = repository.findByVersionId(versionId).get(39);
    assertThat(locked.getMatchStatus()).isEqualTo(ElectronicDrawingSourceNode.MATCH_AMBIGUOUS);
    assertThat(locked.getResolvedMaterialCode()).isNull();

    assertThat(versionMapper.selectById(versionId).getSourceAcquiredAt())
        .isEqualTo(SHANGHAI_ACQUIRED_AT);
    assertThat(versionMapper.selectById(versionId).getSourceFileSha256())
        .isEqualTo("d".repeat(64));
  }

  @Test
  @DisplayName("旧补录版本和明细可继续读写且新来源字段可为空")
  void keepsLegacyVersionAndDetailReadWriteCompatible() {
    insertVersion("DRAFT", false);
    QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
    detail.setSupplementVersionId(versionId);
    detail.setPreparationId(positive("PREP"));
    detail.setOaNo("OA-LEGACY");
    detail.setOaFormItemId(positive("ITEM"));
    detail.setQuoteProductCode("P-LEGACY");
    detail.setSupplementScope("NON_BARE_FULL_BOM");
    detail.setLineNo(1);
    detail.setLevel(0);
    detail.setMaterialCode("P-LEGACY");
    detail.setQtyPerParent(BigDecimal.ONE);
    detail.setQtyPerTop(BigDecimal.ONE);
    detail.setManualFlag(1);
    assertThat(detailMapper.insert(detail)).isOne();
    detailId = detail.getId();

    QuoteBomSupplementDetail stored = detailMapper.selectById(detailId);
    assertThat(stored.getMaterialCode()).isEqualTo("P-LEGACY");
    assertThat(stored.getNodeSourceType()).isNull();
    assertThat(stored.getSourceElectronicNodeId()).isNull();
    assertThat(stored.getMappingStatus()).isNull();

    QuoteBomSupplementVersion legacy = versionMapper.selectById(versionId);
    assertThat(legacy.getBomSource()).isEqualTo("TECH_SUPPLEMENT");
    assertThat(legacy.getElectronicDrawingNo()).isNull();
    assertThat(legacy.getCompositionFingerprint()).isNull();
  }

  @Test
  @DisplayName("解析字段必须成组写入且仓储拒绝跨版本节点")
  void validatesRepositoryCommands() {
    insertVersion("DRAFT", true);
    assertThatThrownBy(() -> repository.updateResolution(
        1L,
        ElectronicDrawingSourceNode.MATCH_UNMATCHED,
        ElectronicDrawingSourceNode.MATCH_MANUAL,
        "M-1",
        null,
        SHANGHAI_RESOLVED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须同时有值或同时为空");

    ElectronicDrawingSourceNode wrongVersion = sourceNode(1);
    wrongVersion.setSupplementVersionId(versionId + 1);
    assertThatThrownBy(() -> repository.insertAll(versionId, List.of(wrongVersion)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不属于指定补录版本");
    assertThat(repository.findByVersionId(versionId)).isEmpty();
  }

  private QuoteBomSupplementVersion insertVersion(
      String versionStatus, boolean electronicDrawing) {
    String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setPreparationId(positive("PREP-" + marker));
    version.setOaNo("OA-" + marker);
    version.setOaFormItemId(positive("ITEM-" + marker));
    version.setQuoteProductCode("P-" + marker);
    version.setProductType("NON_BARE");
    version.setSupplementScope("NON_BARE_FULL_BOM");
    version.setBomSource(electronicDrawing
        ? "ELECTRONIC_DRAWING_EXCEL" : "TECH_SUPPLEMENT");
    if (electronicDrawing) {
      version.setElectronicDrawingNo("J40AH-40HY-03");
      version.setSourceFileName("电子图库明细 J40AH-40HY-03-MX-电子表.xlsx");
      version.setSourceFileSha256("d".repeat(64));
      version.setSourceFileSize(8698L);
      version.setSourceSheetName("J40AH-40HY-03");
      version.setSourceAcquiredAt(SHANGHAI_ACQUIRED_AT);
      version.setSourceRequestId("mock-request-1");
      version.setMaterialOrgCode("COMMERCIAL");
    }
    version.setVersionNo(1);
    version.setVersionStatus(versionStatus);
    version.setActiveFlag(1);
    version.setPeriodMonth("2026-08");
    assertThat(versionMapper.insert(version)).isOne();
    versionId = version.getId();
    return version;
  }

  private List<ElectronicDrawingSourceNode> fortyNodes() {
    List<ElectronicDrawingSourceNode> nodes = new ArrayList<>();
    for (int index = 1; index <= 40; index++) {
      ElectronicDrawingSourceNode node = sourceNode(index);
      if (index <= 38) {
        node.setMatchStatus(ElectronicDrawingSourceNode.MATCH_AUTO);
        node.setResolvedMaterialCode("M-" + index);
        node.setResolvedBy("SYSTEM");
        node.setResolvedAt(SHANGHAI_RESOLVED_AT);
      } else if (index == 40) {
        node.setMatchStatus(ElectronicDrawingSourceNode.MATCH_AMBIGUOUS);
      }
      nodes.add(node);
    }
    return nodes;
  }

  private ElectronicDrawingSourceNode sourceNode(int index) {
    ElectronicDrawingSourceNode node = new ElectronicDrawingSourceNode();
    node.setSupplementVersionId(versionId);
    node.setSourceRowNo(index + 7);
    node.setSourceSequence(Integer.toString(index));
    node.setDrawingCode("D-" + index);
    node.setSourceName("电子图库零件" + index);
    node.setQty(new BigDecimal("1." + String.format("%02d", index)));
    node.setMaterial("铜");
    node.setImportanceClass("B");
    node.setHsfRiskClass("B");
    node.setReferenceWeight(new BigDecimal("0.10000000"));
    node.setReferenceWeightUnit("g");
    node.setSourceRemark("原始备注" + index);
    node.setMatchStatus(ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    return node;
  }

  private static long positive(String value) {
    long hash = value.hashCode();
    return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash) + 1L;
  }
}
