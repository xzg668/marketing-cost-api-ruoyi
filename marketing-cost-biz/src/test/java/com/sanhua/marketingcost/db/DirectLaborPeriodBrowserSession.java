package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceStorage;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalarySourceQuery;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliarySourceQuery;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15198",
    "integration.oa-person-directory.enabled=false",
    "integration.oa-person-directory.run-at-startup=false", "cms.sync-publish.enabled=false",
    "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false", "price.material-sources.enabled=false"})
class DirectLaborPeriodBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper bom;
  @Autowired BomSettlementRuleMapper rules;
  @Autowired TechnicalDataSalarySourceQuery references;
  @Autowired TechnicalDataAuxiliarySourceQuery auxiliaryReferences;
  @SpyBean MonthlyPriceStorage storage;

  @DynamicPropertySource
  static void quoteMonth(DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL", CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test
  void previewSaveAndRecalculateThroughBrowser() throws Exception {
    Path state = Path.of(System.getProperty("direct.labor.browser.state"));
    boolean legacy = Boolean.getBoolean("direct.labor.browser.legacy");
    boolean extended = Boolean.getBoolean("cms.cost.browser.extended");
    var fixture = MaterialShapeSwitchTestData.create(jdbc, bom, rules);
    String month = fixture.month();
    int year = YearMonth.parse(month).getYear();
    String key = fixture.product();
    long item = fixture.itemId();

    jdbc.execute("ALTER TABLE cms_plan_cost_raw ADD COLUMN period VARCHAR(20) NULL");
    long january = addRaw(key, year + "-01", "100", 1);
    addRaw(key, year + "-02", "990", 2);
    long march1 = addRaw(key, year + "-03", "250", 3);
    long march2 = addRaw(key, year + "-03", "200", 4);
    addRaw(key, year + "-04", "800", 5);
    addPlan(key, year + "-01", year + "-06", null);
    addPlan(key, year + "-02", year + "-07", "工时");
    addPlan(key, year + "-03", year + "-08", "辅料");
    jdbc.update("UPDATE cms_cost_source_effective SET period=?,subject_code='0301',subject_name='直接人工工资',source_table='cms_workshop_labor_raw',source_row_ids=?,amount_yuan=1,default_flag=1 WHERE parent_code=? AND source_type='SALARY_DIRECT'", year + "-01", Long.toString(january), key);
    jdbc.update("UPDATE cms_cost_source_effective SET subject_name='辅助人员工资' WHERE parent_code=? AND source_type='SALARY_INDIRECT'", key);
    if (extended) {
      jdbc.update("INSERT INTO cms_subject_setting_raw(import_batch_id,row_no,first_subject_code,first_subject_name,second_subject_code,second_subject_name,business_unit_type) VALUES(1,1,'03','工资','0205','辅助人员工资','COMMERCIAL'),(1,2,'02','辅助材料','0201','工艺辅料','COMMERCIAL')");
      addSubjectRaw(key, year + "-02", "0205", "350");
      addSubjectRaw(key, year + "-02", "0201", "600");
      addSubjectRaw(key, year + "-03", "0205", "990");
      addSubjectRaw(key, year + "-03", "0201", "990");
      long other = addSubjectRaw(key, month, "0203", "100");
      jdbc.update("INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,source_table,source_row_ids,amount_yuan,default_flag,business_unit_type) VALUES(?,'AUX_SUBJECT',?,?,'0203','气体类','cms_product_subject_cost_raw',?,1,1,'COMMERCIAL')",
          year, key, month, Long.toString(other));
    }
    var task = new QuoteTechTask(); task.setApplicableOrgCode("210"); task.setBusinessUnitType("COMMERCIAL");
    var product = new QuoteTechProduct(); product.setAccountingMonth(month);
    var initialReference = references.searchReferences(task, product, key, "REFERENCE").getFirst();
    var initialAux = auxiliaryReferences.search(task, product, key);
    assertThat(initialReference.direct().sourcePeriod()).isEqualTo(year + "-01");
    String username = "salary_" + UUID.randomUUID().toString().substring(0, 8);
    String password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,employee_no,business_unit_type,status,del_flag,create_time,update_time) VALUES(?,'工资期间验收',?,'TEST-SALARY','COMMERCIAL','0','0',NOW(),NOW())", username, new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?", username);
    var json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var legacySnapshot = new AtomicReference<String>();
    if (legacy) {
      // Emulate the older snapshot writer on the first cost. The normal immutability trigger remains active.
      doAnswer(invocation -> {
        if (legacySnapshot.get() != null) return invocation.callRealMethod();
        String oldFormat = removeEarlyVersionEvidence(json, invocation.getArgument(4));
        legacySnapshot.set(oldFormat);
        storage.saveVersionSnapshot(invocation.getArgument(0), invocation.getArgument(1),
            invocation.getArgument(2), invocation.getArgument(3), oldFormat);
        return null;
      }).when(storage).saveVersionSnapshot(anyString(), anyLong(), anyString(), anyInt(), anyString());
    }
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("port", port, "username", username,
        "password", password, "product", key, "part", fixture.parent(), "itemId", item, "month", month,
        "year", year, "legacy", legacy, "extended", extended)));

    Path release = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(9).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForMap("SELECT period,amount_yuan,source_row_ids,default_flag FROM cms_cost_source_effective WHERE parent_code=? AND source_type='SALARY_DIRECT'", key))
        .containsEntry("period", year + "-03").containsEntry("amount_yuan", new BigDecimal("4.500000"))
        .containsEntry("source_row_ids", march1 + "," + march2).containsEntry("default_flag", 0);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log WHERE parent_code=? AND old_period=? AND new_period=? AND action_type='REFRESH'", Long.class, key, year + "-01", year + "-03")).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log WHERE parent_code=?", Long.class, key)).isEqualTo(extended ? 3L : 1L);
    var savedCosts = jdbc.queryForList("""
        SELECT v.status,c.amount,v.total_cost FROM lp_quote_cost_run_version v JOIN lp_cost_run_cost_item c ON c.cost_run_version_id=v.id
         WHERE v.oa_form_item_id=? AND c.cost_code='DIRECT_LABOR' ORDER BY v.id
        """, item);
    assertThat(savedCosts).hasSize(extended ? 4 : 2);
    assertThat(savedCosts.getFirst()).containsEntry("status", "HISTORY").containsEntry("amount", new BigDecimal("1.000000"));
    assertThat(savedCosts.getLast()).containsEntry("status", "SUCCESS").containsEntry("amount", new BigDecimal("4.500000"));
    if (legacy) {
      assertThat(legacySnapshot.get()).isNotNull();
      String history = jdbc.queryForObject("SELECT jianji_snapshot_json FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='HISTORY'", String.class, item);
      assertThat(json.readTree(history).required("document").asText()).isEqualTo(legacySnapshot.get());
    }
    assertThatThrownBy(() -> references.requireReference(task, product, key, initialReference.fingerprint(), "REFERENCE"))
        .hasMessageContaining("工资来源已变化");
    var updated = references.searchReferences(task, product, key, "REFERENCE").getFirst();
    assertThat(updated.direct().sourcePeriod()).isEqualTo(year + "-03");
    if (extended) {
      assertThat(updated.indirect().sourcePeriod()).isEqualTo(year + "-02");
      assertThat(updated.indirect().amountYuan()).isEqualByComparingTo("3.5");
      assertThatThrownBy(() -> auxiliaryReferences.require(task, product, key, initialAux.getFirst().fingerprint()))
          .hasMessageContaining("CMS 辅料来源已变化");
      var aux = auxiliaryReferences.search(task, product, key).getFirst().items();
      assertThat(aux).anySatisfy(row -> {
        assertThat(row.subjectCode()).isEqualTo("0201");
        assertThat(row.sourcePeriod()).isEqualTo(year + "-02");
        assertThat(row.sourceAmount()).isEqualByComparingTo("6");
      }).anySatisfy(row -> {
        assertThat(row.subjectCode()).isEqualTo("0203");
        assertThat(row.sourcePeriod()).isEqualTo(month);
        assertThat(row.sourceAmount()).isEqualByComparingTo("1");
      });
    } else {
      assertThat(updated.indirect()).isEqualTo(initialReference.indirect());
    }
    Files.writeString(Path.of(state + ".verified"), json.writeValueAsString(Map.of("passed", true,
        "currentPeriod", updated.direct().sourcePeriod(), "historicalAmount", savedCosts.getFirst().get("amount"),
        "newAmount", savedCosts.getLast().get("amount"), "referenceFingerprintChanged", true, "legacy", legacy)));
  }

  private static String removeEarlyVersionEvidence(ObjectMapper json, String snapshot) throws Exception {
    var document = json.readTree(snapshot);
    int removed = 0;
    for (var detail : document.at("/calculation/ordinaryInputs/details")) {
      String code = detail.required("costCode").asText();
      if ("DIRECT_LABOR".equals(code) || "OVERHAUL".equals(code)) {
        assertThat(((ObjectNode) detail).remove("composition")).isNotNull();
        removed++;
      }
    }
    assertThat(removed).isEqualTo(2);
    return json.writeValueAsString(document);
  }

  private long addRaw(String key, String month, String cents, int row) {
    jdbc.update("INSERT INTO cms_workshop_labor_raw(import_batch_id,row_no,period,parent_code,last_unit_name,working_cost_cent,working_cost_yuan,source_row_id,business_unit_type) VALUES(1,?,?,?,'验收车间',?,?,?,'COMMERCIAL')",
        row, month, key, new BigDecimal(cents), new BigDecimal(cents).movePointLeft(2), key + "-" + row);
    return jdbc.queryForObject("SELECT id FROM cms_workshop_labor_raw WHERE parent_code=? AND period=? AND row_no=?", Long.class, key, month, row);
  }

  private long addSubjectRaw(String key, String month, String subject, String cents) {
    var cent = new BigDecimal(cents);
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw(import_batch_id,row_no,period,parent_code,second_subject_code,
          material_price,material_price_yuan,source_row_id,business_unit_type)
        VALUES(1,1,?,?,?,?,?,UUID(),'COMMERCIAL')
        """, month, key, subject, cent, cent.movePointLeft(2));
    return jdbc.queryForObject("SELECT id FROM cms_product_subject_cost_raw WHERE parent_code=? AND period=? AND second_subject_code=?",
        Long.class, key, month, subject);
  }

  private void addPlan(String key, String period, String effective, String pending) {
    jdbc.update("INSERT INTO cms_plan_cost_raw(import_batch_id,row_no,parent_code,period,effective_period,unapproved_items,business_unit_type) VALUES(1,1,?,?,?,?,'COMMERCIAL')",
        key, period, effective, pending);
  }
}
