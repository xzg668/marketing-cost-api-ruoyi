package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Disposable database and controlled drawing transport; the browser uses real application HTTP. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15196",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false"})
class DrawingBomReuseBrowserSession extends DrawingResultConsumptionIntegrationTest {
  @LocalServerPort int port;
  @Autowired JwtUtils jwt;

  @Test
  @EnabledIfSystemProperty(named = "drawing.reuse.browser.state", matches = ".+")
  void browserSession() throws Exception {
    freshMonthAndQuoteReuseCompleteApprovedBomAfterLatestRawDrawingComparison();
    String oa = key;
    Long item = jdbc.queryForObject("SELECT i.id FROM oa_form_item i JOIN oa_form f ON f.id=i.oa_form_id WHERE f.oa_no=?", Long.class, oa);
    String currentMonth = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var initial = contexts.load(item, "COMMERCIAL", "210", month);
    var initialDetails = jdbc.queryForList("SELECT * FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? ORDER BY id", initial.sourceVersionId());
    var initialNodes = jdbc.queryForList("SELECT * FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? ORDER BY id", initial.sourceVersionId());
    assertThat(verifications.current(contexts.load(item, "COMMERCIAL", "210", currentMonth))).isEmpty();
    assertThat(effectiveBom.getEffectiveBom(oa, item).nodes()).isNotEmpty();
    assertThat(verifications.current(contexts.load(item, "COMMERCIAL", "210", currentMonth))).isEmpty();
    verify(client, times(2)).verify(any());
    String actor = "reuse_" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'图库复用验收','unused','COMMERCIAL','0','0','REUSE',NOW(),'REUSE',NOW())", actor);
    Long user = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)", user);
    Path state = Path.of(System.getProperty("drawing.reuse.browser.state"));
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    var json = new ObjectMapper();
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "token", jwt.generateToken(actor, "COMMERCIAL"),
        "oaNo", oa, "itemId", item, "materialNo", key, "child", key + "-PART", "month", currentMonth)));
    Path release = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(12).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).as("Browser completed before fixture timeout").isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    var current = contexts.load(item, "COMMERCIAL", "210", currentMonth);
    assertThat(current.sourceVersionId()).isNotEqualTo(initial.sourceVersionId());
    assertThat(current.published()).isTrue();
    assertThat(jdbc.queryForObject("SELECT qty_per_top FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND path=?", java.math.BigDecimal.class,
        current.sourceVersionId(), "/" + key + "/SUPPLEMENT/")).isEqualByComparingTo("7");
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? ORDER BY id", initial.sourceVersionId())).isEqualTo(initialDetails);
    assertThat(jdbc.queryForList("SELECT * FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? ORDER BY id", initial.sourceVersionId())).isEqualTo(initialNodes);
    verify(client, times(3)).verify(any()); // original + new month + explicit browser launch; GET never calls it.
    Files.writeString(Path.of(state + ".db-verified"), json.writeValueAsString(Map.of("passed", true,
        "itemId", item, "month", currentMonth, "oldSource", initial.sourceVersionId(), "newSource", current.sourceVersionId(),
        "completeBomRetained", true, "oldDetailsAndNodesPreserved", true, "externalCalls", 3)));
  }
}
