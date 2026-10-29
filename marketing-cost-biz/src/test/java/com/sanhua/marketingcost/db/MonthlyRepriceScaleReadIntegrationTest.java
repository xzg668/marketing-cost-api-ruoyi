package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.reprice.*;
import com.sanhua.marketingcost.security.JwtUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;

/** Read recovery against already completed, owned volume; never regenerates or edits business rows. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false","oa.person-directory.run-at-startup=false","monthly.reprice.enabled=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false","logging.level.com.sanhua.marketingcost.config.RequestLoggingAspect=OFF"})
class MonthlyRepriceScaleReadIntegrationTest {
 static final Map<String,Object> runtime;
 static {try {runtime=new ObjectMapper().readValue(Files.readString(Path.of(System.getProperty("mrp12.retained.state"))),Map.class);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void source(DynamicPropertyRegistry registry) {
  registry.add("spring.datasource.url",()->runtime.get("jdbcUrl"));registry.add("spring.datasource.username",()->"root");registry.add("spring.datasource.password",()->"root123");
 }
 @Autowired JdbcTemplate jdbc;
 @Autowired MonthlyCostComparisonService comparison;
 @Autowired JwtUtils jwt;
 @Autowired ObjectMapper json;
 @LocalServerPort int port;
 @Test void retainedThousandProductVolumeUsesIndexedPagingAndActualStreamingHttp() throws Exception {
  assertThat(Runtime.getRuntime().maxMemory()).as("Bounded export JVM heap").isLessThanOrEqualTo(512L*1024*1024);
  String unit=System.getProperty("mrp12.scale.unit","COMMERCIAL");int count=Integer.getInteger("mrp12.scale.objects",1000);
  var identity=jdbc.queryForMap("SELECT @@version version,@@server_uuid uuid,DATABASE() db");assertThat(identity.get("uuid")).isEqualTo(runtime.get("databaseUuid"));assertThat(identity.get("uuid")).isNotEqualTo("5336c19d-fffb-11f0-bb7b-3690156b7f2a");
  var actor=jdbc.queryForMap("SELECT user_name,user_id FROM sys_user WHERE user_name LIKE ? ORDER BY user_id LIMIT 1","mrp12_"+unit.charAt(0)+"_%");String username=(String)actor.get("user_name");
  var auth=new UsernamePasswordAuthenticationToken(username,null,List.of(new SimpleGrantedAuthority("ROLE_BU_DIRECTOR")));auth.setDetails(Map.of("businessUnitType",unit,"userId",actor.get("user_id")));SecurityContextHolder.getContext().setAuthentication(auth);
  Path output=Path.of(System.getProperty("mrp12.scale.output"));Files.createDirectories(output);var report=new LinkedHashMap<String,Object>();report.put("objectsPerUnit",count);report.put("partsPerProduct",100);report.put("months",3);report.put("businessUnits",1);report.put("unit",unit);report.put("jvmMaxHeap",Runtime.getRuntime().maxMemory());report.put("database",identity);report.put("fixtureReplayed",false);report.put("originalFixture",runtime.getOrDefault("originRun","mysql95-scale-native-04"));
  var months=jdbc.queryForList("SELECT DISTINCT pricing_month FROM lp_quote_cost_run_version WHERE business_unit_type=? ORDER BY pricing_month",String.class,unit);assertThat(months).hasSize(3);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE business_unit_type=?",Long.class,unit)).isEqualTo(3L*count);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item p JOIN lp_quote_cost_run_version v ON v.id=p.cost_run_version_id WHERE p.business_unit_type=?",Long.class,unit)).isEqualTo(300L*count);
  for(String table:List.of("lp_cost_run_part_item","lp_cost_run_cost_item")){var plan=jdbc.queryForMap("EXPLAIN FORMAT=TRADITIONAL SELECT COUNT(*) FROM "+table+" WHERE cost_run_version_id=1");assertThat(plan.get("key")).as(table).isIn("idx_cost_part_version","idx_cost_item_version");}
  var request=new MonthlyCostComparisonService.Request(months.getFirst(),months.getLast(),List.of(MonthlyCostComparison.Metric.MATERIAL,MonthlyCostComparison.Metric.MANUFACTURE_COST),null,null,null,1,2);var times=new ArrayList<Long>();
  for(int n=0;n<20;n++){long at=System.nanoTime();var page=comparison.compare(request);assertThat(page.totalProducts()).isEqualTo(count);assertThat(page.comparison().rows()).hasSize(200);times.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}
  times.sort(Long::compareTo);report.put("querySamplesMs",times);report.put("query200RowsMsP95",times.get(18));Files.writeString(output.resolve("progress.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));assertThat(times.get(18)).isLessThan(2000);
  System.gc();Thread.sleep(100);var memory=java.lang.management.ManagementFactory.getMemoryMXBean();long initial=memory.getHeapMemoryUsage().getUsed();var peak=new AtomicLong(initial);var pool=Executors.newSingleThreadScheduledExecutor();var timer=pool.scheduleAtFixedRate(()->peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max),0,20,TimeUnit.MILLISECONDS);long at=System.nanoTime();Path file=output.resolve(unit+"-actual.xlsx");
  try {
   String url="http://127.0.0.1:"+port+"/api/v1/monthly-reprice/1130/comparison/export?baselineMonth="+months.getFirst()+"&comparisonMonth="+months.getLast()+"&metrics=MATERIAL,MANUFACTURE_COST";
   var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("Authorization","Bearer "+jwt.generateToken(username,unit)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofFile(file));report.put("httpStatus",response.statusCode());assertThat(response.statusCode()).isEqualTo(200);
  } finally {timer.cancel(true);pool.shutdownNow();SecurityContextHolder.clearContext();}
  long duration=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at),extra=peak.get()-initial;report.put(unit+"Report",Map.of("query200RowsMsP95",times.get(18),"exportMs",duration,"extraPeakHeapBytes",extra,"actualHttpBytes",Files.size(file),"workbook",file.getFileName().toString()));Files.writeString(output.resolve("progress.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));assertThat(duration).isLessThan(60000);assertThat(extra).isLessThan(512L*1024*1024);report.put("status","PASSED");Files.writeString(output.resolve("verification.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
 }
}
