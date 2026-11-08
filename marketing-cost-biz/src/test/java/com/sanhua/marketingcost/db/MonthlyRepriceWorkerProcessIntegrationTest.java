package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Actual packaged worker processes against a newly created, owned database; never target-result seeds. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepriceWorkerProcessIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  record Source(String customer,String oa,long item,long version) {}
  record Child(Process process,Path log,String owner) {}
  private final List<Child> children=new ArrayList<>();
  private final Map<String,Object> proof=new LinkedHashMap<>();
  private final Path evidence=Path.of(Objects.requireNonNull(System.getProperty("reprice.test.evidence")));
  private final String scenario=Objects.requireNonNull(System.getProperty("reprice.test.process-scenario"));

  @Test void realProcessesPreserveAllCustomersAndRecoverTheirOwnedLeases() throws Exception {
    assertThat(scenario).isIn("normal","elastic","graceful","kill-before-publish","kill-after-publish");
    boolean elastic=scenario.equals("elastic");
    assertThat(MYSQL.getContainerInfo().getConfig().getLabels())
        .containsEntry("sales-cost.task","monthly-reprice-reuse-20261105-r02");
    proof.put("scenario",scenario); proof.put("container",MYSQL.getContainerId());
    proof.put("databaseUuid",jdbc.queryForObject("SELECT @@server_uuid",String.class));
    proof.put("startedUtc",Instant.now().toString());
    boolean defaultWait=Boolean.getBoolean("reprice.test.default-wait");
    int concurrency=Integer.getInteger("reprice.test.worker-concurrency",1);
    int customers=Integer.getInteger("reprice.test.customers",3);
    int processCount=elastic?4:scenario.equals("normal")?Integer.getInteger("reprice.test.worker-count",2):2;
    int globalLimit=Integer.getInteger("reprice.test.max-active",4);
    assertThat(processCount).isBetween(2,4);
    assertThat(concurrency).isBetween(1,4);assertThat(customers).isBetween(3,64);
    proof.put("concurrencyPerProcess",concurrency);
    proof.put("processCount",processCount);proof.put("globalLimit",globalLimit);
    proof.put("computeWaitMillis",defaultWait?5000:60000);
    unit="COMMERCIAL"; priceOrg="210"; materialOrg="COMMERCIAL";
    String target=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    String old=YearMonth.parse(target).minusMonths(1).toString();
    var date=YearMonth.parse(old).atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,old));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("recovery-acceptance",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
      String product="RECOVER-"+UUID.randomUUID().toString().substring(0,8), material=product+"-PART";
      var sources=new ArrayList<Source>();
      long template=0;
      int bomLines=Integer.getInteger("reprice.test.bom-lines",1);
      assertThat(bomLines).isBetween(1,100);
      for(int i=0;i<customers;i++) {
        String oa=product+"-OA-"+i, customer="CUSTOMER-"+i;
        if(i==0) jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,?,80000,?)",oa,unit,customer);
        else jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category) "
            +"SELECT ?,business_unit_type,copper_price,?,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category FROM oa_form WHERE id=?",oa,customer,template);
        long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
        jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,product,product,unit);
        long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
        if(i==0) {
          template=form; ordinarySources(product,material,form,date); comparisonFixedPrice(material,"2",date);
          for(int line=1;line<bomLines;line++) {
            String part=material+"-"+line;
            comparisonAdditionalPart(product,material,part,"1",date); comparisonFixedPrice(part,"2",date);
          }
        }
        buildOriginalFixture(form,item,product,old,date,material,Map.of());
        var original=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"recovery-acceptance",true));
        assertThat(original.getPipelineStatus()).as(original.getMessage()).isEqualTo("SUCCESS");
        sources.add(new Source(customer,oa,item,original.getCostVersionId()));
      }
      var originalRows=new LinkedHashMap<Long,Map<String,Object>>();
      for(var source:sources) originalRows.put(source.version(),versionRow(source.version()));

      jdbc.execute("CREATE TABLE reprice_process_claims(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,execution_no INT,worker_id VARCHAR(128),claimed_at DATETIME(6))");
      jdbc.execute("CREATE TABLE reprice_process_finishes(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,execution_no INT,worker_id VARCHAR(128),finished_at DATETIME(6))");
      jdbc.execute("CREATE TRIGGER reprice_process_finish AFTER UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN "
          +"IF OLD.scene='MONTHLY_REPRICE' AND OLD.status='RUNNING' AND NEW.status<>'RUNNING' THEN "
          +"INSERT INTO reprice_process_finishes(task_id,execution_no,worker_id,finished_at) VALUES(OLD.id,OLD.execution_no,OLD.worker_id,NOW(6)); END IF; END");
      jdbc.execute("CREATE TABLE reprice_process_retries(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,execution_no INT,worker_id VARCHAR(128),summary JSON,deferred_at DATETIME(6))");
      jdbc.execute("CREATE TRIGGER reprice_process_retry AFTER UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN "
          +"IF NEW.scene='MONTHLY_REPRICE' AND NEW.status='PENDING' AND OLD.status='RUNNING' THEN "
          +"INSERT INTO reprice_process_retries(task_id,execution_no,worker_id,summary,deferred_at) VALUES(NEW.id,NEW.execution_no,OLD.worker_id,NEW.result_summary_json,NOW(6)); END IF; END");
      jdbc.execute("CREATE TRIGGER reprice_process_claim AFTER UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN "
          +"IF NEW.scene='MONTHLY_REPRICE' AND NEW.status='RUNNING' AND NOT(NEW.worker_id <=> OLD.worker_id) THEN "
          +"INSERT INTO reprice_process_claims(task_id,execution_no,worker_id,claimed_at) VALUES(NEW.id,NEW.execution_no,NEW.worker_id,NOW(6)); END IF; END");
      long firstItem=sources.getFirst().item();
      if(elastic) {
        // Controlled customer-completion latency leaves real work in flight while JVMs start
        // and drain. This experiment verifies lifecycle correctness, not throughput.
        jdbc.execute("CREATE TRIGGER reprice_elastic_delay BEFORE UPDATE ON lp_monthly_reprice_result "
            +"FOR EACH ROW BEGIN IF NEW.compute_group_id IS NOT NULL AND OLD.compute_group_id IS NULL "
            +"THEN DO SLEEP(0.5); END IF; END");
      } else if(!scenario.equals("normal")) {
        String sql=scenario.equals("kill-before-publish")
            ? "CREATE TRIGGER reprice_process_pause BEFORE UPDATE ON lp_monthly_reprice_compute_group FOR EACH ROW BEGIN IF NEW.status='SUCCESS' THEN DO SLEEP(8); END IF; END"
            : "CREATE TRIGGER reprice_process_pause BEFORE UPDATE ON lp_monthly_reprice_result FOR EACH ROW BEGIN IF NEW.oa_form_item_id="+firstItem+" AND NEW.compute_group_id IS NOT NULL AND OLD.compute_group_id IS NULL THEN DO SLEEP(8); END IF; END";
        jdbc.execute(sql);
      }
      var firstWorker=startWorker("one",old);
      if(scenario.equals("normal")) for(int i=2;i<=processCount;i++)startWorker("worker-"+i,old);
      assertThat(children).allSatisfy(child->assertThat(child.process().isAlive()).isTrue());
      var batch=contracts.enqueue(unit,YearMonth.parse(target));
      String no=batch.getRepriceNo(); proof.put("repriceNo",no);
      assertThat(batch.getSourceCount()).isEqualTo(customers); assertThat(batch.getTotalCount()).isEqualTo(customers);
      if(elastic) exerciseElasticWorkers(no,old,customers);
      CostRunTask stale=null;
      if(!scenario.equals("normal") && !elastic) {
        await("first worker reached the controlled database checkpoint",Duration.ofSeconds(70),()->
            jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE STATE='User sleep'",Integer.class)>0);
        stale=repriceMapper.tasks(no).stream().filter(t->"RUNNING".equals(t.getStatus())).findFirst().orElseThrow();
        assertThat(stale.getWorkerId()).isEqualTo(firstWorker.owner());
        var checkpoint=jdbc.queryForMap("SELECT status,attempt_count,execution_no,worker_id,result_document IS NOT NULL AS published FROM lp_monthly_reprice_compute_group WHERE batch_id=?",batch.getId());
        assertThat(checkpoint.get("status")).isEqualTo(scenario.equals("kill-before-publish")?"RUNNING":"SUCCESS");
        if(scenario.equals("kill-before-publish"))
          assertThat(checkpoint.get("worker_id")).isEqualTo(firstWorker.owner());
        proof.put("checkpoint",checkpoint); proof.put("interruptedTask",stale.getId());
        proof.put("originalLeaseExpires",stale.getLockExpireTime());
        proof.put("firstExecution",stale.getExecutionNo()); proof.put("signalUtc",Instant.now().toString());
        if(scenario.equals("graceful")) firstWorker.process().destroy(); else firstWorker.process().destroyForcibly();
        assertThat(firstWorker.process().waitFor(45,TimeUnit.SECONDS)).as("owned worker stopped").isTrue();
        proof.put("interruptedExit",firstWorker.process().exitValue());
        jdbc.execute("DROP TRIGGER reprice_process_pause");
        if(scenario.equals("graceful")) {
          assertThat(repriceMapper.readTask(no,stale.getId()).getStatus()).as("normal stop drains the current customer").isEqualTo("SUCCESS");
        } else {
          assertThat(repriceMapper.readTask(no,stale.getId()).getStatus()).isEqualTo("RUNNING");
          assertThat(targetCount(no)).isZero();
        }
        startWorker("two",old);
      }
      await("all customer results plus automatic check",Duration.ofSeconds(220),()->{
        var state=repriceMapper.readBatch(no);
        assertThat(state.getStatus()).as("failed tasks: "+jdbc.queryForList("SELECT id,status,error_message FROM lp_cost_run_task WHERE source_no=?",no)).isNotEqualTo("FAILED");
        return state.getSuccessCount()==customers && "PASSED".equals(state.getCheckStatus());
      });
      assertThat(targetCount(no)).isEqualTo(customers);
      var publication=jdbc.queryForMap("SELECT id,status,attempt_count,execution_no,worker_id FROM lp_monthly_reprice_compute_group WHERE batch_id=?",batch.getId());
      int attempts=scenario.equals("kill-before-publish")?2:1;
      assertThat(publication).containsEntry("status","SUCCESS").containsEntry("attempt_count",attempts);
      long group=((Number)publication.get("id")).longValue();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group WHERE batch_id=?",Integer.class,batch.getId())).isEqualTo(1);
      var results=repriceMapper.results(no);
      assertThat(results).extracting(r->r.getCustomerIdentityValue()).containsExactlyInAnyOrderElementsOf(sources.stream().map(Source::customer).toList());
      for(var result:results) {
        var source=sources.stream().filter(s->s.customer().equals(result.getCustomerIdentityValue())).findFirst().orElseThrow();
        assertThat(result.getCalcStatus()).isEqualTo("SUCCESS");
        assertThat(result.getOaNo()).isEqualTo(source.oa()); assertThat(result.getOaFormItemId()).isEqualTo(source.item());
        assertThat(result.getSourceCostVersionId()).isEqualTo(source.version()); assertThat(result.getComputeGroupId()).isEqualTo(group);
        var actual=savedCosts.read(unit,result.getCostRunVersionId()); var expected=savedCosts.read(unit,source.version());
        assertThat(actual.snapshot().calculation().ordinary()).usingRecursiveComparison().isEqualTo(expected.snapshot().calculation().ordinary());
        assertThat(actual.snapshot().calculation().ordinaryInputs().details()).usingRecursiveComparison().isEqualTo(expected.snapshot().calculation().ordinaryInputs().details());
        assertThat(actual.snapshot().calculation().totalCost()).isEqualByComparingTo(expected.snapshot().calculation().totalCost());
        assertThat(actual.snapshot().calculation().cuMaterialAdjustment()).isEqualByComparingTo(expected.snapshot().calculation().cuMaterialAdjustment());
        assertThat(actual.snapshot().calculation().finalQuoteAmount()).isEqualByComparingTo(expected.snapshot().calculation().finalQuoteAmount());
        assertThat(actual.parts()).extracting(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount()))
            .containsExactlyElementsOf(expected.parts().stream().map(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount())).toList());
        assertThat(actual.parts()).allSatisfy(p->assertThat(p.getOaNo()).isEqualTo(source.oa()));
        assertThat(versionRow(source.version())).isEqualTo(originalRows.get(source.version()));
      }
      var links=jdbc.queryForList("SELECT s.source_cost_version_id,s.oa_no,s.oa_form_item_id,r.customer_identity_value,r.cost_run_version_id FROM lp_monthly_reprice_source s "
          +"JOIN lp_monthly_reprice_result r ON r.id=s.object_id AND r.reuse_batch_id=s.batch_id WHERE s.batch_id=?",batch.getId());
      assertThat(links).hasSize(customers);
      for(var source:sources) assertThat(links).filteredOn(row->Objects.equals(row.get("source_cost_version_id"),source.version()))
          .singleElement().satisfies(row->assertThat(row).containsEntry("oa_no",source.oa()).containsEntry("oa_form_item_id",source.item()).containsEntry("customer_identity_value",source.customer()));

      var report=checks.verify(repriceMapper.readBatch(no)); assertThat(report.passed()).as(report.problems().toString()).isTrue();
      var claims=jdbc.queryForList("SELECT task_id,execution_no,worker_id,claimed_at FROM reprice_process_claims ORDER BY id");
      var participating=claims.stream().map(row->row.get("worker_id")).distinct().toList();
      assertThat(participating.size()).isBetween(2,processCount);
      assertThat(participating).allSatisfy(owner->assertThat(children.stream().anyMatch(child->child.owner().equals(owner))).isTrue());
      proof.put("participatingInstances",participating);
      if(scenario.equals("normal") || elastic) {
        var finishes=jdbc.queryForList("SELECT worker_id,finished_at FROM reprice_process_finishes ORDER BY id");
        var peaks=new LinkedHashMap<String,Integer>();
        for(var child:children) {
          var events=new java.util.TreeMap<LocalDateTime,Integer>();
          claims.stream().filter(row->child.owner().equals(row.get("worker_id")))
              .forEach(row->events.merge(databaseTime(row.get("claimed_at")),1,Integer::sum));
          finishes.stream().filter(row->child.owner().equals(row.get("worker_id")))
              .forEach(row->events.merge(databaseTime(row.get("finished_at")),-1,Integer::sum));
          int live=0,maximum=0;
          for(var change:events.values()){live+=change;maximum=Math.max(maximum,live);}
          // More instances than global slots can legitimately leave a ready instance idle.
          // Admission guarantees a bounded shared budget, not round-robin distribution.
          assertThat(live).isZero();assertThat(maximum).isBetween(0,concurrency);peaks.put(child.owner(),maximum);
        }
        if(concurrency>1)assertThat(peaks.values()).anyMatch(value->value>1);
        proof.put("observedConcurrentLeases",peaks);
        var globalEvents=new java.util.TreeMap<LocalDateTime,Integer>();
        claims.forEach(row->globalEvents.merge(databaseTime(row.get("claimed_at")),1,Integer::sum));
        finishes.forEach(row->globalEvents.merge(databaseTime(row.get("finished_at")),-1,Integer::sum));
        int live=0,maximum=0;
        for(var change:globalEvents.values()){live+=change;maximum=Math.max(maximum,live);}
        assertThat(live).isZero();assertThat(maximum).isBetween(1,globalLimit);
        if(processCount>2)assertThat(maximum).as("actual overlapping work across independently started instances").isGreaterThan(1);
        proof.put("observedGlobalConcurrentLeases",maximum);
        if(elastic) assertThat(maximum).as("four independent instances held live work before shrink").isEqualTo(4);
      }
      var retries=jdbc.queryForList("SELECT task_id,execution_no,worker_id,JSON_UNQUOTE(JSON_EXTRACT(summary,'$.failure.code')) AS reason,"
          +"CAST(JSON_UNQUOTE(JSON_EXTRACT(summary,'$.retryNotBefore')) AS DATETIME(6)) AS ready_at,deferred_at FROM reprice_process_retries ORDER BY id");
      if(defaultWait && scenario.equals("kill-before-publish")) {
        assertThat(retries).isNotEmpty().anySatisfy(row->assertThat(row.get("reason")).isEqualTo("SHARED_GROUP_BUSY"));
        for(var retry:retries) {
          var claim=claims.stream().filter(row->Objects.equals(row.get("task_id"),retry.get("task_id"))
              && Objects.equals(row.get("execution_no"),retry.get("execution_no"))).findFirst().orElseThrow();
          assertThat(databaseTime(claim.get("claimed_at"))).isAfterOrEqualTo(databaseTime(retry.get("ready_at")));
        }
        assertThat(repriceMapper.tasks(no)).allSatisfy(task->{
          assertThat(task.getStatus()).isEqualTo("SUCCESS");assertThat(task.getRetryCount()).isBetween(0,3);
          assertThat(task.getErrorMessage()).isNull();
        });
      }
      proof.put("automaticRetries",retries);
      if(scenario.startsWith("kill-")) {
        var prior=Objects.requireNonNull(stale);
        var recovered=repriceMapper.readTask(no,prior.getId());
        assertThat(recovered.getExecutionNo()).isEqualTo(prior.getExecutionNo()+1);
        assertThatThrownBy(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(prior))).hasMessageContaining("执行轮次");
        var firstClaim=claims.stream().filter(row->Objects.equals(row.get("task_id"),prior.getId())).toList();
        assertThat(firstClaim).hasSize(2);
        var start=databaseTime(firstClaim.getFirst().get("claimed_at"));
        var end=databaseTime(firstClaim.getLast().get("claimed_at"));
        long elapsed=Duration.between(start,end).toMillis();
        assertThat(end).as("takeover occurs after the actual stored lease expires").isAfterOrEqualTo(prior.getLockExpireTime());
        // Existing task lease columns have whole-second precision; the audit has microseconds.
        assertThat(elapsed).as("natural two-minute lease, no test timestamp rewrite").isGreaterThanOrEqualTo(119000);
        proof.put("naturalTakeoverMillis",elapsed);
      }
      proof.put("claims",claims); proof.put("publication",publication);
      long automaticChecks=0;
      for(var child:children) automaticChecks+=java.nio.file.Files.readAllLines(child.log()).stream()
          .filter(line->line.contains("调价检查开始 batch="+no+" revision=")).count();
      assertThat(automaticChecks).as("only one actual worker performs the completed batch check").isEqualTo(1);
      proof.put("automaticChecks",automaticChecks);
      proof.put("M",customers);proof.put("U",customers);proof.put("G",1);proof.put("A",attempts);
      proof.put("missing",0);proof.put("amountMismatch",0);proof.put("status","PASSED");
      System.out.println("REPRICE_PROCESSES scenario="+scenario+" processes="+processCount+" M="+customers+" U="+customers+" G=1 A="+attempts+" missing=0 amount_mismatch=0");
    } finally {
      for(var child:children) if(child.process().isAlive()) {
        child.process().destroy(); if(!child.process().waitFor(35,TimeUnit.SECONDS)) child.process().destroyForcibly().waitFor();
      }
      if(elastic) jdbc.execute("DROP TRIGGER IF EXISTS reprice_elastic_delay");
      proof.put("finishedUtc",Instant.now().toString());
      proof.put("processes",children.stream().map(child->Map.of("pid",child.process().pid(),"owner",child.owner(),"log",child.log().toString(),"exit",child.process().exitValue())).toList());
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
          .writeValue(evidence.resolve("process-proof.json").toFile(),proof);
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  private void exerciseElasticWorkers(String no,String goLive,int customers) throws Exception {
    assertThat(customers).as("enough independent customer work for live resizing").isGreaterThanOrEqualTo(48);
    var stages=new ArrayList<Map<String,Object>>();
    await("one instance began customer work",Duration.ofSeconds(30),()->runningInstances(no)==1);
    stages.add(elasticCheckpoint(no,1));
    startWorker("elastic-two",goLive);
    await("two independent instances hold work",Duration.ofSeconds(30),()->runningInstances(no)==2);
    stages.add(elasticCheckpoint(no,2));
    startWorker("elastic-three",goLive);
    startWorker("elastic-four",goLive);
    await("four independent instances hold work",Duration.ofSeconds(30),()->runningInstances(no)==4);
    stages.add(elasticCheckpoint(no,4));
    var draining=children.subList(2,4).stream().toList();
    var owners=draining.stream().map(Child::owner).toList();
    await("both instances to shrink have current work",Duration.ofSeconds(20),()->
        repriceMapper.tasks(no).stream().filter(task->"RUNNING".equals(task.getStatus()) && owners.contains(task.getWorkerId())).count()==2);
    var active=repriceMapper.tasks(no).stream()
        .filter(task->"RUNNING".equals(task.getStatus()) && owners.contains(task.getWorkerId())).toList();
    var signalledAt=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",LocalDateTime.class);
    proof.put("shrinkSignalAt",signalledAt);proof.put("drainingInstances",owners);
    for(var child:draining)child.process().destroy();
    for(var child:draining)assertThat(child.process().waitFor(35,TimeUnit.SECONDS)).as("graceful instance drain").isTrue();
    for(var task:active) {
      var saved=repriceMapper.readTask(no,task.getId());
      assertThat(saved.getStatus()).as("in-flight customer commits before drain completes").isEqualTo("SUCCESS");
      assertThat(saved.getExecutionNo()).isEqualTo(task.getExecutionNo());
    }
    for(var owner:owners) assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM reprice_process_claims
        WHERE worker_id=? AND claimed_at>?
        """,Integer.class,owner,signalledAt.plusSeconds(1))).as("draining instance takes no new customer").isZero();
    assertThat(children.stream().filter(child->child.process().isAlive()).count()).isEqualTo(2);
    assertThat(repriceMapper.readBatch(no).getSuccessCount()).as("shrink happened before all work completed").isLessThan(customers);
    await("two remaining instances continue the same batch",Duration.ofSeconds(30),()->runningInstances(no)==2);
    stages.add(elasticCheckpoint(no,2));
    proof.put("elasticStages",stages);proof.put("elasticBatchUnchanged",no);
    proof.put("controlledCustomerDelayMillis",500);
  }

  private int runningInstances(String no) {
    return jdbc.queryForObject("""
        SELECT COUNT(DISTINCT worker_id) FROM lp_cost_run_task
        WHERE source_no=? AND scene='MONTHLY_REPRICE' AND status='RUNNING'
        """,Integer.class,no);
  }

  private Map<String,Object> elasticCheckpoint(String no,int instances) {
    var state=repriceMapper.readBatch(no);
    return Map.of("utc",Instant.now().toString(),"liveProcesses",children.stream().filter(child->child.process().isAlive()).count(),
        "runningInstances",instances,"success",state.getSuccessCount(),"total",state.getTotalCount(),"repriceNo",no);
  }

  private Child startWorker(String label,String goLive) throws Exception {
    Path jar=Path.of(Objects.requireNonNull(System.getProperty("reprice.test.worker-jar")));
    assertThat(jar).isRegularFile();
    Path directory=evidence.resolve("worker-"+label);Files.createDirectory(directory);
    Path log=directory.resolve("process.log");
    var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-Xmx640m","-XX:ActiveProcessorCount=2","-jar",jar.toString(),
        "--monthly.reprice.worker-enabled=true","--monthly.reprice.poll-interval-ms=1000",
        "--monthly.reprice.compute-lease-seconds=30",
        "--monthly.reprice.concurrency="+Integer.getInteger("reprice.test.worker-concurrency",1),
        "--monthly.reprice.claim-batch-size=8",
        "--monthly.reprice.capacity.max-active="+Integer.getInteger("reprice.test.max-active",4),
        "--monthly.reprice.go-live-months.COMMERCIAL="+goLive,
        "--srm.fixed-price.enabled=false","--oa.person-directory.enabled=false","--oa.person-directory.run-at-startup=false",
        "--cms.sync.publish.enabled=false","--quote.drawing-verification.enabled=false",
        "--spring.data.redis.repositories.enabled=false");
    if(!Boolean.getBoolean("reprice.test.default-wait")) builder.command().add("--monthly.reprice.compute-wait-millis=60000");
    var env=builder.environment();env.clear();env.put("PATH",System.getenv("PATH"));
    env.put("SPRING_DATASOURCE_URL",MYSQL.getJdbcUrl()+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
    env.put("SPRING_DATASOURCE_USERNAME",MYSQL.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",MYSQL.getPassword());
    env.put("COST_RUN_WORKER_ID","same-process-label");env.put("WORKER_DATASOURCE_MAX_POOL_SIZE","24");env.put("WORKER_DATASOURCE_MIN_IDLE","1");
    var process=builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    // Register immediately, so failed startup never leaks a process.
    var unready=new Child(process,log,"pending"); children.add(unready);
    await("packaged worker startup; inspect "+log,Duration.ofSeconds(60),()->{
      assertThat(process.isAlive()).as("worker exited; inspect "+log).isTrue();
      try {return Files.exists(log) && Files.readString(log).contains("调价实例容量 instance=");}
      catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    });
    var matcher=java.util.regex.Pattern.compile("调价worker实例 (\\S+) enabled=true").matcher(Files.readString(log));
    assertThat(matcher.find()).as("enabled monthly worker identity").isTrue();
    var ready=new Child(process,log,matcher.group(1));children.set(children.indexOf(unready),ready);return ready;
  }
  private void await(String condition,Duration timeout,BooleanSupplier ready) throws InterruptedException {
    long deadline=System.nanoTime()+timeout.toNanos();
    while(System.nanoTime()<deadline){
      assertThat(Files.exists(evidence.resolve("capacity-stop.json"))).as("owned fixture capacity guard").isFalse();
      if(ready.getAsBoolean())return;Thread.sleep(100);
    }
    fail("Timed out: "+condition);
  }
  private int targetCount(String no){return jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,no);}
  private LocalDateTime databaseTime(Object value) {
    return value instanceof LocalDateTime time ? time : ((java.sql.Timestamp)value).toLocalDateTime();
  }
  private Map<String,Object> versionRow(long id){return jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",id);}
}
