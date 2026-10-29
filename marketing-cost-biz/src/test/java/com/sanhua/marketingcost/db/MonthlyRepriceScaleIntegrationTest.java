package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.reprice.*;
import com.sanhua.marketingcost.security.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/** Synthetic volume only; real monthly facts/calculator/saver/read model and actual HTTP files. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false","oa.person-directory.run-at-startup=false","monthly.reprice.enabled=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false","logging.level.com.sanhua.marketingcost.config.RequestLoggingAspect=OFF"})
@Import(MonthlyRepriceScaleIntegrationTest.MeterConfiguration.class)
class MonthlyRepriceScaleIntegrationTest extends OriginalPricingIntegrationTestBase {
 @Autowired JdbcTemplate jdbc;
 @Autowired MonthlyMaterialPrices prices;
 @Autowired QuoteCostRunVersionService versions;
 @Autowired QuoteCostRunVersionMapper versionMapper;
 @Autowired PricePrepareItemMapper preparations;
 @Autowired BomCostingRowMapper boms;
 @Autowired BomRawHierarchyMapper rawRows;
 @Autowired QuoteEffectiveBomNodeMapper roots;
 @Autowired MonthlyCostVersionService costs;
 @Autowired MonthlyCostComparisonService comparison;
 @Autowired MonthlyPriceDocumentCodec codec;
 @Autowired ObjectMapper json;
 @Autowired JwtUtils jwt;
 @Autowired com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway bomGateway;
 @Autowired MonthlyRepriceContractService contracts;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @Autowired com.sanhua.marketingcost.config.MonthlyRepriceProperties properties;
 @org.springframework.boot.test.web.server.LocalServerPort int port;
 static final AtomicLong queries=new AtomicLong(),writes=new AtomicLong();
 static final ConcurrentHashMap<String,AtomicLong> byStatement=new ConcurrentHashMap<>();
 @TestConfiguration static class MeterConfiguration { @Bean Interceptor scaleSqlMeter(){return new Meter();} }
 @Intercepts({@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})})
 static class Meter implements Interceptor {
  public Object intercept(Invocation call)throws Throwable {var ms=(MappedStatement)call.getArgs()[0];(call.getMethod().getName().equals("query")?queries:writes).incrementAndGet();byStatement.computeIfAbsent(ms.getId(),k->new AtomicLong()).incrementAndGet();return call.proceed();}
 }
 Path output;String unit,org,actor;long actorId;int count;
 static BigDecimal d(String n){return new BigDecimal(n);}
 void auth(){var a=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_BU_DIRECTOR")));a.setDetails(Map.of("businessUnitType",unit,"userId",actorId));SecurityContextHolder.getContext().setAuthentication(a);}
 String product(int i){return "MRP12-"+unit.charAt(0)+"-"+String.format("%04d",i);}
 String material(int i){return "000MRP12-"+unit.charAt(0)+"-M"+String.format("%03d",i);}
 long objectId(int i){return 1000000L+(unit.equals("HOUSEHOLD")?100000:0)+i;}
 void save(String name,Object value)throws Exception{Files.writeString(output.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
 final Map<String,Bulk> bulk=new LinkedHashMap<>();
 final List<Long> pendingSuccess=new ArrayList<>();
 class Bulk {
  final String table;List<String> columns;final List<Object[]> pending=new ArrayList<>();final int limit;
  Bulk(String table,int limit){this.table=table;this.limit=limit;}
  void add(Map<String,Object> row){
   if(columns==null){var generated=new HashSet<>(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND EXTRA LIKE '%GENERATED%'",String.class,table));columns=row.keySet().stream().filter(k->!generated.contains(k)).toList();}
   pending.add(columns.stream().map(row::get).toArray());
  }
  void flush(){if(pending.isEmpty())return;String tuple="("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";for(int offset=0;offset<pending.size();offset+=limit){var rows=pending.subList(offset,Math.min(offset+limit,pending.size()));var args=new ArrayList<Object>();rows.forEach(r->Collections.addAll(args,r));jdbc.update("INSERT INTO `"+table+"` (`"+String.join("`,`",columns)+"`) VALUES "+String.join(",",Collections.nCopies(rows.size(),tuple)),args.toArray());}pending.clear();}
 }
 Bulk writer(String table){return bulk.computeIfAbsent(table,k->new Bulk(k,k.equals("lp_quote_cost_run_version")?5:200));}
 void flush(){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->flushRows());}
 void flushRows(){for(String table:List.of("lp_quote_cost_run_version","lp_quote_effective_bom_node","lp_bom_costing_row","lp_price_prepare_item","lp_cost_run_part_item","lp_cost_run_cost_item")){var writer=bulk.get(table);if(writer!=null)writer.flush();}if(!pendingSuccess.isEmpty()){jdbc.update("UPDATE lp_quote_cost_run_version SET status='SUCCESS' WHERE status='RUNNING' AND id IN ("+String.join(",",Collections.nCopies(pendingSuccess.size(),"?"))+")",pendingSuccess.toArray());pendingSuccess.clear();}}
 Map<String,Object> copy(Map<String,Object> source,String oldProduct,String newProduct){var row=new LinkedHashMap<String,Object>();source.forEach((k,v)->row.put(k,v instanceof String s?s.replace(oldProduct,newProduct):v));return row;}
 void context(Map<String,Object> row,long id,String product,long version,String month){row.put("id",id);for(String k:List.of("oa_no","product_code","top_product_code"))if(row.containsKey(k))row.put(k,product);for(String k:List.of("oa_form_item_id","oa_form_id"))if(row.containsKey(k))row.put(k,objectId(Integer.parseInt(product.substring(product.length()-4))));if(row.containsKey("cost_run_version_id"))row.put("cost_run_version_id",version);if(row.containsKey("cost_run_no"))row.put("cost_run_no","MRP12-R-"+version);if(row.containsKey("period_month"))row.put("period_month",month);if(row.containsKey("pricing_month"))row.put("pricing_month",month);if(row.containsKey("business_unit_type"))row.put("business_unit_type",unit);}
 MonthlyProductCost.Ordinary ordinary(List<MonthlyProductCost.MaterialLine> lines){return new MonthlyProductCost.Ordinary(lines,BigDecimal.ZERO,d("10"),d("5"),d("0.01"),d("0.1"),d("1.05"),d("0.1"),d("0.2"),d("0.03"),d("2.5"));}
 MonthlyAdoptionPlan plan(String product,Map<MonthlyPriceRequest,Selection> selected){var uses=new ArrayList<Use>();selected.keySet().stream().sorted(Comparator.comparing(r->r.family().materialCode())).forEach(r->uses.add(new Use(product+"/"+r.family().materialCode(),r,BigDecimal.ONE)));return new MonthlyAdoptionPlan(selected,uses,Set.of());}
 MonthlyProductCost.Ordinary input(MonthlyAdoptionPlan plan){return ordinary(plan.uses().stream().map(u->{var s=plan.selections().get(u.request());return new MonthlyProductCost.MaterialLine(u.path(),u.request().family().materialCode(),BigDecimal.ONE,List.of(s.monthlyPriceId()),s.fact().price(),s.fact().price(),null);}).toList());}
 void fixtureObjects() {
  for(int i=0;i<count;i++) {
   String p=product(i),name=(unit.equals("HOUSEHOLD")?"板换":"")+"压力父件"+i;long id=objectId(i);
   jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type,process_code,accounting_period_month) VALUES(?,?,?,'FI-SC-005',?)",id,p,unit,YearMonth.now().toString());
   jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?,?)",id,id,p,name,unit);
  }
  for(int i=0;i<100;i++)jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES(?,?,?,'件',?,0,'MRP12-S1','2020-01-01','2099-01-01',?,'PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",org,material(i),"压力部品"+i,BigDecimal.ONE.add(BigDecimal.valueOf(i,2)),unit);
 }
 void rawBomFixtures() {
  for(int n=0;n<100;n++)jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件',?,'MRP12',1,'15','采购件')",material(n),"压力部品"+n,org.equals("220")?"PLATE":"COMMERCIAL");
  for(int i=0;i<Math.min(100,count);i++) {
   String p=product(i);jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件',?,'MRP12',1,'11','制造件')",p,"压力父件"+p,org.equals("220")?"PLATE":"COMMERCIAL");
   for(int n=-1;n<100;n++) {
    var r=new BomRawHierarchy();r.setPriceOrgCode(org);r.setTopProductCode(p);r.setParentCode(p);r.setMaterialCode(n<0?p:material(n));r.setMaterialName(n<0?"压力父件"+p:"压力部品"+n);r.setPath("/"+p+"/"+(n<0?"":material(n)+"/"));r.setLevel(n<0?0:1);r.setSortSeq(n+1);r.setQtyPerParent(BigDecimal.ONE);r.setQtyPerTop(BigDecimal.ONE);r.setShapeAttr(n<0?"制造件":"采购件");r.setBomPurpose("主制造");r.setBomVersion("MRP12");r.setBomStatus("已核准");r.setU9IsCostFlag(1);r.setIsLeaf(n<0?0:1);r.setEffectiveFrom(LocalDate.of(2020,1,1));r.setEffectiveTo(LocalDate.of(2099,1,1));r.setSourceType("U9");r.setSourceLineKey(p+"/"+(n<0?"ROOT":material(n)));r.setSourceImportBatchId("MRP12");r.setBuildBatchId(p);r.setBuiltAt(LocalDateTime.now());r.setBusinessUnitType(unit);rawRows.insert(r);
   }
  }
 }
 QuoteEffectiveBomNode root(String p,String build,String month){var r=new QuoteEffectiveBomNode();r.setBuildBatchId(build);r.setOriginMonthlySnapshotId(0L);r.setEffectiveVariantHash("MRP12-"+build);r.setShapeResolutionSource("U9");r.setAlternativeSelectionSource("AUTO_STANDARD");r.setSourceBomType("U9");r.setTopProductCode(p);r.setCostPeriodMonth(month);r.setPriceOrgCode(org);r.setNodeKey(p);r.setNodePath(p);r.setNodeLevel(0);r.setMaterialCode(p);r.setMaterialName("压力父件"+p);r.setMaterialUnit("件");r.setEffectiveMaterialShape("MANUFACTURE");r.setQtyPerParent(BigDecimal.ONE);r.setQtyPerTop(BigDecimal.ONE);roots.insert(r);return r;}
 BomCostingRow bom(String p,long item,String build,String month,int n){var b=new BomCostingRow();b.setBuildBatchId(build);b.setBuiltAt(LocalDateTime.now());b.setOaNo(p);b.setOaFormItemId(item);b.setLevel(1);b.setParentCode(p);b.setTopProductCode(p);b.setPeriodMonth(month);b.setAsOfDate(YearMonth.parse(month).atDay(25));b.setRawVersionEffectiveFrom(LocalDate.of(2020,1,1));b.setPath(p+"/"+material(n));b.setMaterialCode(material(n));b.setMaterialName("压力部品"+n);b.setUnit("件");b.setQtyPerParent(BigDecimal.ONE);b.setQtyPerTop(BigDecimal.ONE);b.setPriceOrgCode(org);b.setBusinessUnitType(unit);boms.insert(b);return b;}
 PricePrepareItem prepare(QuoteCostRunVersion v,String no,String path,BomCostingRow b,BigDecimal price){var p=new PricePrepareItem();p.setPrepareNo(no);p.setPeriodMonth(v.getPricingMonth());p.setOaNo(v.getOaNo());p.setOaFormItemId(v.getOaFormItemId());p.setTopProductCode(v.getProductCode());p.setMaterialCode(b.getMaterialCode());p.setMaterialName(b.getMaterialName());p.setQuantity(BigDecimal.ONE);p.setUnitPrice(price.setScale(8));p.setAmount(price.setScale(8));p.setPriceOrgCode(org);p.setItemType("NORMAL");p.setStatus("READY");p.setCurrentFlag(1);p.setSettlementKey(path);p.setBomRowId(b.getId());p.setBusinessUnitType(unit);preparations.insert(p);return p;}
 record Template(MonthlyCostVersionService.Stored stored,Map<String,Object> version,List<Map<String,Object>> parts,List<Map<String,Object>> costs,List<Map<String,Object>> preps,List<Map<String,Object>> boms,Map<String,Object> root) {}
 Template base(int i,String month,Map<MonthlyPriceRequest,Selection> selected) {return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->baseInTransaction(i,month,selected));}
 Template baseInTransaction(int i,String month,Map<MonthlyPriceRequest,Selection> selected) {
  String p=product(i);long item=objectId(i);
  var date=YearMonth.parse(month).atDay(25).atStartOfDay();
  var bomResult=bomGateway.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(item,item,p,month,unit,p,p,null,null,org,org.equals("220")?"PLATE":"COMMERCIAL",date.toLocalDate(),date));
  assertThat(bomResult.status()).as(bomResult.message()).isEqualTo(com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.Status.AVAILABLE);
  var v=versions.createRunning(p,item,p,month,month,"MRP12-F-"+unit+month+i,unit);v.setFinancePricePrepareNo("MRP12-F-"+v.getId());v.setOaPricePrepareNo("MRP12-O-"+v.getId());versionMapper.updateById(v);
  var plan=plan(p,selected);var bindings=new LinkedHashMap<String,MonthlyCostVersionService.PreparationBinding>();String build="MRP12-B-"+v.getId();root(p,build,month);
  for(int n=0;n<plan.uses().size();n++){var use=plan.uses().get(n);var b=bom(p,item,build,month,n);var price=selected.get(use.request()).fact().price();var f=prepare(v,v.getFinancePricePrepareNo(),use.path(),b,price);var o=prepare(v,v.getOaPricePrepareNo(),use.path(),b,price);bindings.put(use.path(),new MonthlyCostVersionService.PreparationBinding(f.getId(),o.getId()));}
  var stored=costs.calculateAndSave(unit,v.getId(),plan,input(plan),null,d("80000"),bindings);MonthlyCostVersionService.validateStoredRows(stored);
  jdbc.update("UPDATE lp_quote_cost_run_version SET status='SUCCESS',confirm_message='MRP12 synthetic performance fixture, not business acceptance' WHERE id=?",v.getId());
  return new Template(stored,jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",v.getId()),jdbc.queryForList("SELECT * FROM lp_cost_run_part_item WHERE cost_run_version_id=? ORDER BY id",v.getId()),jdbc.queryForList("SELECT * FROM lp_cost_run_cost_item WHERE cost_run_version_id=? ORDER BY line_no",v.getId()),jdbc.queryForList("SELECT * FROM lp_price_prepare_item WHERE prepare_no IN (?,?) ORDER BY id",v.getFinancePricePrepareNo(),v.getOaPricePrepareNo()),jdbc.queryForList("SELECT * FROM lp_bom_costing_row WHERE build_batch_id=? ORDER BY id",build),jdbc.queryForMap("SELECT * FROM lp_quote_effective_bom_node WHERE build_batch_id=?",build));
 }
 void synthetic(int i,int group,String month,Map<MonthlyPriceRequest,Selection> selected,Template t) {
  String p=product(i),old=t.stored.version().getProductCode();long v=2000000L+group*count+i;String build="MRP12-B-"+v;var plan=plan(p,selected);var calc=MonthlyProductCost.calculate(plan,input(plan),null);var binding=new LinkedHashMap<String,MonthlyCostVersionService.PreparationBinding>();
  var prepareById=new HashMap<Long,Map<String,Object>>();t.preps.forEach(r->prepareById.put(((Number)r.get("id")).longValue(),r));
  for(int n=0;n<100;n++) {
   var use=plan.uses().get(n);long bId=100000000L+v*101+n,fId=1000000000L+v*201+n*2,oId=fId+1;var oldBinding=t.stored.snapshot().preparations().get(old+"/"+use.request().family().materialCode());
   var b=copy(t.boms.get(n),old,p);context(b,bId,p,v,month);b.put("build_batch_id",build);writer("lp_bom_costing_row").add(b);
   for(var entry:List.of(Map.entry(fId,oldBinding.financialItemId()),Map.entry(oId,oldBinding.customerItemId()))) {var row=copy(prepareById.get(entry.getValue()),old,p);context(row,entry.getKey(),p,v,month);row.put("prepare_no",(entry.getKey()==fId?"MRP12-F-":"MRP12-O-")+v);row.put("bom_row_id",bId);writer("lp_price_prepare_item").add(row);}
   var part=copy(t.parts.get(n),old,p);context(part,2000000000L+v*101+n,p,v,month);part.put("bom_row_id",bId);part.put("price_prepare_item_id",fId);writer("lp_cost_run_part_item").add(part);
   binding.put(use.path(),new MonthlyCostVersionService.PreparationBinding(fId,oId));
  }
  var root=copy(t.root,old,p);context(root,3000000000L+v,p,v,month);root.put("build_batch_id",build);writer("lp_quote_effective_bom_node").add(root);
  int line=0;for(var source:t.costs){var row=copy(source,old,p);context(row,4000000000L+v*50+line++,p,v,month);writer("lp_cost_run_cost_item").add(row);}
  var snapshot=new MonthlyCostVersionService.Snapshot(1,unit,v,"MRP12-R-"+v,p,objectId(i),p,month,calc,t.stored.snapshot().priceScenarios(),binding);
  var version=copy(t.version,old,p);context(version,v,p,v,month);version.put("status","RUNNING");pendingSuccess.add(v);version.put("version_no","MRP12-S-"+v);version.put("finance_price_prepare_no","MRP12-F-"+v);version.put("oa_price_prepare_no","MRP12-O-"+v);version.put("jianji_snapshot_json",codec.encode(snapshot));writer("lp_quote_cost_run_version").add(version);
 }
 void acceptance(YearMonth month,Map<String,Object> report) throws Exception {
  properties.setGoLiveMonths(Map.of("COMMERCIAL",month.minusMonths(2).toString(),"HOUSEHOLD",month.minusMonths(2).toString()));
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  var samples=new ArrayList<Long>();long beforeQueries=queries.get(),beforeWrites=writes.get();
  // Execute all discovery and real INSERTs; rollback keeps 20 independent first submissions.
  // These timings exclude commit, which is reported separately by the contended real submission.
  for(int trial=0;trial<20;trial++) {
   long start=System.nanoTime();
   tx.execute(status->{var batch=contracts.enqueue(unit,month);assertThat(batch.getTotalCount()).isEqualTo(count);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task WHERE batch_no=?",Integer.class,batch.getRepriceNo())).isEqualTo(count);status.setRollbackOnly();return null;});
   samples.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
  }
  long discoveryQueries=(queries.get()-beforeQueries)/20,insertStatements=(writes.get()-beforeWrites)/20;
  assertThat(discoveryQueries).isLessThanOrEqualTo(5);assertThat(insertStatements).isLessThanOrEqualTo(5+2*((count+199)/200));
  samples.sort(Long::compareTo);
  var pool=Executors.newFixedThreadPool(8);var barrier=new CyclicBarrier(8);var runs=new ArrayList<Future<Map.Entry<String,Long>>>();
  try {
   for(int n=0;n<8;n++)runs.add(pool.submit(()->{auth();try{barrier.await(10,TimeUnit.SECONDS);long at=System.nanoTime();var batch=contracts.enqueue(unit,month);return Map.entry(batch.getRepriceNo(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}finally{SecurityContextHolder.clearContext();}}));
   var identities=new HashSet<String>();var waits=new ArrayList<Long>();for(var future:runs){var run=future.get(30,TimeUnit.SECONDS);identities.add(run.getKey());waits.add(run.getValue());}
   assertThat(identities).hasSize(1);String no=identities.iterator().next();
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task WHERE batch_no=?",Integer.class,no)).isEqualTo(count);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_result WHERE reprice_no=?",Integer.class,no)).isEqualTo(count);
   report.put(unit+"Acceptance",Map.of("freshSubmissionRollbackSamplesMs",samples,"p95Ms",samples.get(18),"p99Ms",samples.get(19),"discoveryQueries",discoveryQueries,"insertStatements",insertStatements,"concurrentCommittedSubmissions",8,"batchCount",identities.size(),"contendedCommitDurationsMs",waits,"tasks",count));save("progress.json",report);
  } finally {pool.shutdownNow();}
  assertThat(samples.get(18)).isLessThanOrEqualTo(1000);assertThat(samples.get(19)).isLessThanOrEqualTo(2000);
 }
 @Test void actualCumulativeVolumePagingStreamingAndShortAcceptance() throws Exception {
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version",Long.class)).as("Scale must run in a fresh fixture JVM, separate from other full-schema business tests").isZero();
  count=Integer.getInteger("mrp12.scale.objects",1000);output=Path.of(System.getProperty("mrp12.scale.output",Files.createTempDirectory("mrp12-scale-").toString()));Files.createDirectories(output);
  var scopes=Arrays.asList(System.getProperty("mrp12.scale.units","COMMERCIAL,HOUSEHOLD").split(","));
  var report=new LinkedHashMap<String,Object>();report.put("objectsPerUnit",count);report.put("partsPerProduct",100);report.put("months",3);report.put("businessUnits",scopes.size());report.put("syntheticVolumeNotWorkflowAcceptance",true);report.put("jvmMaxHeap",Runtime.getRuntime().maxMemory());report.put("database",jdbc.queryForMap("SELECT @@version version,@@server_uuid uuid,DATABASE() db"));save("progress.json",report);
  new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/V128__monthly_reprice_menu_seed.sql")).execute(jdbc.getDataSource());
  YearMonth last=YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());var measures=new ArrayList<Map<String,Object>>();int group=0;
  for(String scope:scopes) {
   unit=scope;org=unit.equals("COMMERCIAL")?"210":"220";actor="mrp12_"+unit.charAt(0)+"_"+UUID.randomUUID().toString().substring(0,6);
   jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,'disabled-test-password',?,'0','0','MRP12',NOW(),'MRP12',NOW())",actor,"MRP12 benchmark",unit);actorId=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,10)",actorId);auth();fixtureObjects();rawBomFixtures();
   for(int m=0;m<3;m++) {
    String month=last.minusMonths(2-m).toString();var requests=new LinkedHashSet<MonthlyPriceRequest>();
    for(int n=0;n<100;n++)requests.add(new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),unit,material(n),null,"件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE));
    var c=new MonthlyMaterialPrices.Context(unit,product(0),objectId(0),product(0),YearMonth.parse(month),YearMonth.parse(month).atDay(25).atStartOfDay(),actor,org);
    long q0=queries.get();var small=prices.resolveCandidates(c,requests.stream().limit(10).collect(java.util.stream.Collectors.toSet()));long q10=queries.get()-q0;q0=queries.get();var found=prices.resolveCandidates(c,requests);long q100=queries.get()-q0;assertThat(q10).isPositive();assertThat(found.problems()).isEmpty();assertThat(q100).isLessThanOrEqualTo(q10+10);
    var selected=new LinkedHashMap<MonthlyPriceRequest,Selection>();found.values().forEach((r,v)->selected.put(r,v.selection()));var adopted=prices.adopt(c,plan(product(0),selected)).plan().selections();
    Template template=null;long started=System.nanoTime();for(int i=0;i<Math.min(100,count);i++){var saved=base(i,month,adopted);if(template==null)template=saved;}
    long baseMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);assertThat(baseMs).isLessThan(120000);
    for(int i=Math.min(100,count);i<count;i++){synthetic(i,group,month,adopted,template);if(i%5==4)flush();}flush();
    measures.add(Map.of("unit",unit,"month",month,"actualSaveObjects",Math.min(100,count),"baseMs",baseMs,"sourceQueries10",q10,"sourceQueries100",q100));report.put("setup",measures);save("progress.json",report);group++;
   }
   acceptance(last,report);
   var req=new MonthlyCostComparisonService.Request(last.minusMonths(2).toString(),last.toString(),List.of(MonthlyCostComparison.Metric.MATERIAL,MonthlyCostComparison.Metric.MANUFACTURE_COST),null,null,null,1,2);
   var samples=new ArrayList<Long>();long q0=queries.get();for(int trial=0;trial<20;trial++){long at=System.nanoTime();var page=comparison.compare(req);assertThat(page.totalProducts()).isEqualTo(count);assertThat(page.comparison().rows()).hasSize(200);samples.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}samples.sort(Long::compareTo);long p95=samples.get(18);assertThat(p95).isLessThan(2000);
   long queryStatements=queries.get()-q0;System.gc();Thread.sleep(100);var memory=java.lang.management.ManagementFactory.getMemoryMXBean();long initial=memory.getHeapMemoryUsage().getUsed();var peak=new AtomicLong(initial);var sampler=Executors.newSingleThreadScheduledExecutor();var ticker=sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max),0,20,TimeUnit.MILLISECONDS);
   long start=System.nanoTime();Path download=output.resolve(unit+"-actual.xlsx");try {
    String url="http://127.0.0.1:"+port+"/api/v1/monthly-reprice/1130/comparison/export?baselineMonth="+last.minusMonths(2)+"&comparisonMonth="+last+"&metrics=MATERIAL,MANUFACTURE_COST";
    var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("Authorization","Bearer "+jwt.generateToken(actor,unit)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofFile(download));assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("spreadsheetml");
   } finally {ticker.cancel(true);sampler.shutdownNow();}
   long exportMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),extra=peak.get()-initial;report.put(unit+"Report",Map.of("query200RowsMsP95",p95,"queryStatementsPerRequest",queryStatements/20.0,"exportMs",exportMs,"extraPeakHeapBytes",extra,"actualHttpBytes",Files.size(download),"workbook",download.getFileName().toString()));save("progress.json",report);
   assertThat(exportMs).isLessThan(60000);assertThat(extra).isLessThan(512L*1024*1024);
  }
  report.put("storedParts",jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item p JOIN lp_quote_cost_run_version v ON v.id=p.cost_run_version_id",Long.class));report.put("storedVersions",jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version",Long.class));report.put("sqlStatements",byStatement.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().get())));assertThat(report.get("storedVersions")).isEqualTo(3L*count*scopes.size());assertThat(report.get("storedParts")).isEqualTo(300L*count*scopes.size());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(300L*scopes.size());report.put("status","PASSED");save("verification.json",report);SecurityContextHolder.clearContext();
 }
}
