package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Disposable MySQL and real application HTTP; captured provider evidence stays outside the repository. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15196",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false"})
class DrawingUnknownBrowserSession extends DrawingResultConsumptionIntegrationTest {
  @LocalServerPort int port;
  @Autowired JwtUtils jwt;

  @Test
  @EnabledIfSystemProperty(named = "drawing.unknown.browser.state", matches = ".+")
  void capturedResponseCanBeReadAndContinuedWithoutAnotherProviderCall() throws Exception {
    var json = new ObjectMapper();
    var captured = json.readTree(Files.readString(Path.of(System.getProperty("drawing.unknown.capture"))));
    var incoming = captured.path("results").get(0);
    String raw = incoming.path("raw_summary_json").asText();
    var response = codec.parse(raw);
    String drawing = response.summary().plmData().get(0).path("drawingNo").asText();
    jdbc.update("UPDATE lp_material_master_raw SET drawing_no=? WHERE material_code=?", drawing, key);
    doReturn(response).when(client).verify(any());
    var scope = contexts.load(itemId, "COMMERCIAL", "210", month);
    var task = verifications.start(scope, null).orElseThrow();
    tasks.failConsumption(task.getId(), task.getCurrentResultId(), "旧版本未能关联本产品图号", java.time.LocalDateTime.now());
    assertThat(results.findById(task.getCurrentResultId()).getRawSummaryJson()).isEqualTo(raw);
    Path state = Path.of(System.getProperty("drawing.unknown.browser.state"));
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "token", jwt.generateToken("admin", "COMMERCIAL"),
        "oaNo", key, "itemId", itemId, "month", month, "drawingNo", drawing, "taskId", task.getId())));
    Path release = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).as("Browser completion signal").isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(tasks.selectById(task.getId()).getConsumptionState()).isEqualTo("COMPLETE");
    assertThat(results.findById(task.getCurrentResultId()).getRawSummaryJson()).isEqualTo(raw);
    var current = contexts.load(itemId, "COMMERCIAL", "210", month);
    var imported = sourceNodes.findByVersionId(current.sourceVersionId());
    assertThat(imported).hasSize(29);
    assertThat(imported.stream().filter(row -> row.getParentSourceSequence() != null).count()).isEqualTo(13);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_supplement_version WHERE drawing_result_id=?", Integer.class,
        task.getCurrentResultId())).isEqualTo(1);
    verify(client, times(1)).verify(any());
    Files.writeString(Path.of(state + ".db-verified"), json.writeValueAsString(Map.of("passed", true,
        "sourceNodeCount", imported.size(), "parentEdges", 13, "providerCalls", 1,
        "rawEvidencePreserved", true, "workflowStage", current.workflowStage())));
  }
}
