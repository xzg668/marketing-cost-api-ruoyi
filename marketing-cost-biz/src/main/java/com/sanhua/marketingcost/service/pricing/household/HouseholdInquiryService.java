package com.sanhua.marketingcost.service.pricing.household;

import static com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanhua.marketingcost.entity.monthly.*;
import com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Source maintenance only. No monthly adoption or costing dependency enters this module. */
@Service
public class HouseholdInquiryService {
  private final HouseholdInquiryQueryMapper queries;
  private final HouseholdInquiryStorage storage;
  private final HouseholdInquiryWorkbookParser parser;
  private final HouseholdInquiryFiles files;
  private final MonthlyPriceDocumentCodec codec;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final Clock clock;

  @Autowired
  public HouseholdInquiryService(HouseholdInquiryQueryMapper queries,HouseholdInquiryStorage storage,
      HouseholdInquiryWorkbookParser parser,HouseholdInquiryFiles files,MonthlyPriceDocumentCodec codec,
      PlatformTransactionManager manager,ObjectMapper mapper) {
    this(queries,storage,parser,files,codec,manager,mapper,Clock.systemUTC());
  }
  HouseholdInquiryService(HouseholdInquiryQueryMapper queries,HouseholdInquiryStorage storage,
      HouseholdInquiryWorkbookParser parser,HouseholdInquiryFiles files,MonthlyPriceDocumentCodec codec,
      PlatformTransactionManager manager,ObjectMapper mapper,Clock clock) {
    this.queries=queries;this.storage=storage;this.parser=parser;this.files=files;this.codec=codec;this.clock=clock;
    json=mapper.copy().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(8);
  }

  public Preview preview(String unit,String actor,byte[] bytes,String fileName,String sourceMonth,PriceContext context) {
    scope(unit);actor(actor);Objects.requireNonNull(context,"来源价格口径不能为空");month(sourceMonth);
    var workbook=validateEvidence(parser.parse(bytes,fileName),context,sourceMonth);
    String key=files.save(unit,bytes); // Completely durable and SHA-verified before inserting a batch.
    if(!key.equals(unit+"/"+workbook.sha256()))throw new IllegalStateException("解析原件与已归档原件摘要不一致");
    var current=new HashMap<String,HouseholdInquiryPriceRow>();
    var materials=workbook.rows().stream().map(HouseholdInquiryWorkbookParser.SourceRow::materialCode).toList();
    storage.current(unit,materials).forEach(row->current.put(row.getMaterialCode(),row));
    var fingerprints=new HashMap<String,String>();
    for(var row:workbook.rows()) fingerprints.put(row.materialCode(),fingerprint(new RecordIdentity(row.businessRow(),context,sourceMonth)));
    var historical=new HashSet<String>();
    for(int start=0;start<materials.size();start+=256) {
      var group=materials.subList(start,Math.min(start+256,materials.size()));
      var hashes=group.stream().map(fingerprints::get).toList();
      for(var old:queries.historicalFingerprints(unit,group,hashes)) historical.add(old.get("material_code")+"/"+old.get("record_fingerprint"));
    }
    var rows=new ArrayList<PlannedRow>();
    for(var row:workbook.rows()) {
      var previous=current.get(row.materialCode());
      String pricing=fingerprint(new PricingIdentity(row.materialCode(),row.unitPrice(),row.householdRate(),context));
      String record=fingerprints.get(row.materialCode());
      var action=previous==null?Action.CREATE:record.equals(previous.getRecordFingerprint())?Action.UNCHANGED:Action.NEW_VERSION;
      rows.add(new PlannedRow(row,previous==null?null:previous.getId(),action,
          action==Action.NEW_VERSION&&historical.contains(row.materialCode()+"/"+record),
          previous==null||!pricing.equals(previous.getPricingFingerprint()),pricing,record));
    }
    var warnings=new ArrayList<String>();
    if(!sourceMonth.endsWith("-10")) warnings.add("页签提示10月，明确来源年月为"+sourceMonth+"；来源年月不决定核算月份或当前版本顺序");
    var content=new PreviewContent(workbook,sourceMonth,"OPERATOR_INPUT",context,rows,warnings);
    var batch=new HouseholdInquiryBatchRow();batch.setBusinessUnitType(unit);batch.setPreviewId(UUID.randomUUID().toString());
    batch.setPublishRequestId(UUID.randomUUID().toString());batch.setPreviewFingerprint(previewFingerprint(unit,actor,content));
    batch.setFileKey(key);batch.setFileSha256(workbook.sha256());batch.setFileName(workbook.fileName());
    batch.setWorksheetName(HouseholdInquiryWorkbookParser.SHEET);batch.setParserSchema(HouseholdInquiryWorkbookParser.SCHEMA);
    batch.setSourceMonth(sourceMonth);batch.setSourceMonthOrigin("OPERATOR_INPUT");batch.setPricingUnit(context.unit());batch.setCurrency(context.currency());batch.setTaxBasis(context.taxBasis().name());
    batch.setPreviewJson(write(content));batch.setStatus("PREVIEW");batch.setCreatedBy(actor);
    batch.setExpiresAt(LocalDateTime.ofInstant(clock.instant().plusSeconds(1800),ZoneOffset.UTC));
    return transaction.execute(status->previewView(storage.insertBatch(batch),content));
  }

  public PublishResult publish(String unit,String actor,PublishRequest request) {
    scope(unit);actor(actor);validateRequest(request);
    var observed=queries.batchByRequest(unit,request.publishRequestId());
    if(observed==null) {
      var other=storage.batch(unit,request.previewId());
      if(other.isPresent()) { owner(other.get(),actor);throw fail("IDEMPOTENCY_CONFLICT",409,"预览只接受原预留发布请求"); }
      throw fail("PREVIEW_NOT_FOUND",404,"预览不存在");
    }
    verify(observed,actor,request);
    if("PUBLISHED".equals(observed.getStatus())) return result(observed);
    if(!"PREVIEW".equals(observed.getStatus()))throw fail("PREVIEW_CLOSED",409,"预览已结束");
    if(!clock.instant().isBefore(observed.getExpiresAt().toInstant(ZoneOffset.UTC))) {
      return recoverAfterExpiry(unit,actor,request);
    }
    files.read(unit,observed.getFileSha256());
    try {
      return transaction.execute(status->{
        var batch=queries.lockBatch(unit,request.previewId());verify(batch,actor,request);
        if("PUBLISHED".equals(batch.getStatus())) return result(batch);
        ensurePending(batch);
        var content=codec.decode(batch.getPreviewJson(),PreviewContent.class);
        if(!previewFingerprint(unit,actor,content).equals(batch.getPreviewFingerprint())) throw new IllegalStateException("保存的预览内容与指纹不符");
        if(!content.workbook().errors().isEmpty()||content.rows().isEmpty()) throw fail("PREVIEW_HAS_ERRORS",400,"询价存在错误，整批不能发布");
        var current=lockCurrent(unit,content.rows());
        for(var row:content.rows()) {
          var existing=current.get(row.source().materialCode());Long id=existing==null?null:existing.getId();
          if(!Objects.equals(id,row.expectedCurrentId())) throw fail("STALE_PREVIEW",409,"目标来源已变化，请重新预览");
        }
        var saved=publishRows(unit,batch,content,current);
        storage.completePublication(unit,batch.getId(),write(saved));return saved;
      });
    } catch(DuplicateKeyException conflict) { throw fail("STALE_PREVIEW",409,"目标来源已被并发发布，请重新预览");
    } catch(ConcurrencyFailureException conflict) {
      var recovered=recoverOrUncertain(unit,actor,request,conflict);if(recovered!=null)return recovered;
      throw caused(fail("PUBLICATION_CONFLICT",409,"发布发生并发冲突，请沿用原请求重试"),conflict);
    } catch(TransactionException|DataAccessResourceFailureException uncertain) {
      var recovered=recoverOrUncertain(unit,actor,request,uncertain);if(recovered!=null)return recovered;
      throw caused(fail("PUBLICATION_UNCERTAIN",503,"发布结果尚未确认，请沿用原请求重试"),uncertain);
    }
  }

  /** An expired non-locking observation cannot rule out an in-flight successful publication. */
  private PublishResult recoverAfterExpiry(String unit,String actor,PublishRequest request) {
    try {
      return transaction.execute(status->{
        var batch=queries.lockBatch(unit,request.previewId());verify(batch,actor,request);
        if("PUBLISHED".equals(batch.getStatus()))return result(batch);
        if(!"PREVIEW".equals(batch.getStatus()))throw fail("PREVIEW_CLOSED",409,"预览已结束");
        throw fail("PREVIEW_EXPIRED",409,"预览已过期，请重新预览");
      });
    } catch(DataAccessException|TransactionException uncertain) {
      var recovered=recoverOrUncertain(unit,actor,request,uncertain);if(recovered!=null)return recovered;
      throw caused(fail("PUBLICATION_UNCERTAIN",503,"发布结果尚未确认，请沿用原请求重试"),uncertain);
    }
  }

  private Map<String,HouseholdInquiryPriceRow> lockCurrent(String unit,List<PlannedRow> rows) {
    var materials=rows.stream().map(r->r.source().materialCode()).distinct()
        .sorted((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8))).toList();
    var result=new HashMap<String,HouseholdInquiryPriceRow>();
    for(int start=0;start<materials.size();start+=256) for(var row:queries.lockCurrent(unit,materials.subList(start,Math.min(start+256,materials.size())))) result.put(row.getMaterialCode(),row);
    return result;
  }
  private PublishResult publishRows(String unit,HouseholdInquiryBatchRow batch,PreviewContent content,Map<String,HouseholdInquiryPriceRow> current) {
    var results=new ArrayList<PublishedRow>();int inserted=0;
    for(var plan:content.rows().stream().sorted(Comparator.comparing(p->p.source().materialCode())).toList()) {
      var source=plan.source();var old=current.get(source.materialCode());
      if(plan.action()==Action.UNCHANGED) {
        results.add(new PublishedRow(source.materialCode(),Action.UNCHANGED,old.getId(),old.getRevisionNo(),old.getPreviousPriceId(),false,false));continue;
      }
      if(old!=null)storage.retire(unit,old.getId());
      var row=new HouseholdInquiryPriceRow();row.setBusinessUnitType(unit);row.setMaterialCode(source.materialCode());
      row.setMaterialName(source.materialName());row.setSpecification(source.specification());row.setRevisionNo(old==null?1:old.getRevisionNo()+1);
      row.setPreviousPriceId(old==null?null:old.getId());row.setImportBatchId(batch.getId());row.setSourceRowsJson(write(source.sourceRows()));
      row.setUnitPrice(new BigDecimal(source.unitPrice()));row.setHouseholdRate(new BigDecimal(source.householdRate()));
      row.setPricingFingerprint(plan.pricingFingerprint());row.setRecordFingerprint(plan.recordFingerprint());
      row.setReferenceJson(write(new StoredEvidence(source,content.priceContext(),content.sourceMonth())));row.setCurrentFlag(1);
      var saved=storage.insertVersion(row);inserted++;
      results.add(new PublishedRow(source.materialCode(),plan.action(),saved.getId(),saved.getRevisionNo(),saved.getPreviousPriceId(),plan.priceChanged(),plan.historicalValue()));
    }
    return new PublishResult(batch.getId(),batch.getPublishRequestId(),"PUBLISHED",results,content.workbook().duplicates().size(),inserted,batch.getFileSha256());
  }

  public Preview preview(String unit,String actor,String previewId) {
    scope(unit);actor(actor);var batch=storage.batch(unit,previewId).orElseThrow(()->fail("PREVIEW_NOT_FOUND",404,"预览不存在"));
    owner(batch,actor);return previewView(batch,read(batch.getPreviewJson(),PreviewContent.class));
  }
  public void cancel(String unit,String actor,String previewId) {
    scope(unit);actor(actor);
    transaction.executeWithoutResult(status->{
      var batch=queries.lockBatch(unit,previewId);if(batch==null)throw fail("PREVIEW_NOT_FOUND",404,"预览不存在");owner(batch,actor);
      if("CANCELLED".equals(batch.getStatus()))return;
      if(!"PREVIEW".equals(batch.getStatus())||queries.cancel(unit,batch.getId())!=1)throw fail("PREVIEW_CLOSED",409,"预览已结束");
    });
  }
  public PricePage current(String unit,String material,int page,int size) {
    scope(unit);pagination(page,size);material=optionalMaterial(material);
    return new PricePage(queries.page(unit,material,size,(page-1)*size).stream().map(this::view).toList(),queries.count(unit,material),page,size);
  }
  public PricePage history(String unit,String material,int page,int size) {
    scope(unit);pagination(page,size);material=optionalMaterial(material);
    if(material==null)throw new IllegalArgumentException("历史查询必须明确料号");
    return new PricePage(queries.historyPage(unit,material,size,(page-1)*size).stream().map(this::view).toList(),queries.historyCount(unit,material),page,size);
  }
  public PriceView price(String unit,long id) { scope(unit);return view(savedPrice(unit,id)); }
  public FinancialView financial(String unit,long id) {
    scope(unit);var row=savedPrice(unit,id);var batch=queries.batch(unit,row.getImportBatchId());
    return new FinancialView(view(row),codec.decode(row.getReferenceJson(),StoredEvidence.class),batch.getFileName(),batch.getFileSha256());
  }
  public OriginalFile original(String unit,long id) {
    scope(unit);var row=savedPrice(unit,id);var batch=queries.batch(unit,row.getImportBatchId());
    return new OriginalFile(batch.getFileName(),batch.getFileSha256(),files.read(unit,batch.getFileSha256()));
  }
  public ImportView importDetails(String unit,String actor,long batchId) {
    scope(unit);actor(actor);var batch=queries.batch(unit,batchId);
    if(batch==null)throw fail("PREVIEW_NOT_FOUND",404,"导入批次不存在");
    if(!"PUBLISHED".equals(batch.getStatus()))owner(batch,actor);
    return new ImportView(previewView(batch,codec.decode(batch.getPreviewJson(),PreviewContent.class)),
        "PUBLISHED".equals(batch.getStatus())?result(batch):null,batch.getCreatedBy(),batch.getPublishedAt());
  }
  public OriginalFile importOriginal(String unit,String actor,long batchId) {
    importDetails(unit,actor,batchId);var batch=queries.batch(unit,batchId);
    return new OriginalFile(batch.getFileName(),batch.getFileSha256(),files.read(unit,batch.getFileSha256()));
  }
  private HouseholdInquiryPriceRow savedPrice(String unit,long id) {
    var row=queries.price(unit,id);if(row==null)throw fail("SOURCE_NOT_FOUND",404,"来源版本不存在");return row;
  }
  private PriceView view(HouseholdInquiryPriceRow row) {
    var coordinates=read(row.getSourceRowsJson(),Integer[].class);
    return new PriceView(row.getId(),row.getMaterialCode(),row.getMaterialName(),row.getSpecification(),row.getRevisionNo(),row.getCurrentFlag()==1,
        plain(row.getUnitPrice()),plain(row.getHouseholdRate()),row.getSourceMonth(),new PriceContext(row.getPricingUnit(),row.getCurrency(),TaxBasis.valueOf(row.getTaxBasis())),
        row.getImportBatchId(),row.getCreatedAt(),List.of(coordinates));
  }
  private Preview previewView(HouseholdInquiryBatchRow batch,PreviewContent content) {
    Instant expiry=batch.getExpiresAt().toInstant(ZoneOffset.UTC);String state=batch.getStatus();
    if(state.equals("PREVIEW")&&!clock.instant().isBefore(expiry))state="EXPIRED";
    return new Preview(batch.getId(),batch.getPreviewId(),batch.getPublishRequestId(),batch.getPreviewFingerprint(),expiry,state,content);
  }
  private void ensurePending(HouseholdInquiryBatchRow batch) {
    if(!"PREVIEW".equals(batch.getStatus()))throw fail("PREVIEW_CLOSED",409,"预览已结束");
    if(!clock.instant().isBefore(batch.getExpiresAt().toInstant(ZoneOffset.UTC)))throw fail("PREVIEW_EXPIRED",409,"预览已过期，请重新预览");
  }
  private PublishResult result(HouseholdInquiryBatchRow batch) { return codec.decode(batch.getResultJson(),PublishResult.class); }
  private PublishResult recoverOrUncertain(String unit,String actor,PublishRequest request,RuntimeException original) {
    try{return recover(unit,actor,request);}
    catch(DataAccessException recoveryFailure) {
      var outcome=caused(fail("PUBLICATION_UNCERTAIN",503,"发布结果尚未确认，请沿用原请求重试"),original);
      outcome.addSuppressed(recoveryFailure);throw outcome;
    }
  }
  private static HouseholdInquiryFailure caused(HouseholdInquiryFailure outcome,RuntimeException cause) { outcome.initCause(cause);return outcome; }
  private HouseholdInquiryWorkbookParser.WorkbookData validateEvidence(HouseholdInquiryWorkbookParser.WorkbookData workbook,PriceContext context,String sourceMonth) {
    var valid=new ArrayList<HouseholdInquiryWorkbookParser.SourceRow>();var errors=new ArrayList<>(workbook.errors());
    for(var row:workbook.rows()) {
      try { MonthlyAdoptionPlan.SourceEvidence.ofJson(write(new StoredEvidence(row,context,sourceMonth)));valid.add(row); }
      catch(IllegalArgumentException failure) { errors.add(new HouseholdInquiryWorkbookParser.Issue(row.sourceRows().getFirst(),"A:S","单行来源信息过长或无法无损保存，请精简参考内容后重新预览")); }
    }
    return new HouseholdInquiryWorkbookParser.WorkbookData(workbook.fileName(),workbook.sha256(),valid,errors,workbook.duplicates());
  }
  private PublishResult recover(String unit,String actor,PublishRequest request) {
    var row=queries.batchByRequest(unit,request.publishRequestId());
    if(row==null)return null;verify(row,actor,request);return "PUBLISHED".equals(row.getStatus())?result(row):null;
  }
  private static void verify(HouseholdInquiryBatchRow batch,String actor,PublishRequest request) {
    if(batch==null)throw fail("PREVIEW_NOT_FOUND",404,"预览不存在");owner(batch,actor);
    if(!batch.getPreviewId().equals(request.previewId())||!batch.getPublishRequestId().equals(request.publishRequestId())||!batch.getPreviewFingerprint().equals(request.previewFingerprint()))throw fail("IDEMPOTENCY_CONFLICT",409,"发布请求与原预览不一致");
  }
  private static void owner(HouseholdInquiryBatchRow batch,String actor) {
    if(!batch.getCreatedBy().equals(actor))throw fail("PREVIEW_OWNER_MISMATCH",403,"不能使用其他操作者的预览");
  }
  private String previewFingerprint(String unit,String actor,PreviewContent content) {
    return fingerprint(new PreviewIdentity(unit,actor,HouseholdInquiryWorkbookParser.SCHEMA,content));
  }
  private String fingerprint(Object value) { return MonthlyPriceDocumentCodec.sha256(write(value)); }
  private String write(Object value) {
    try{return json.writeValueAsString(value);}catch(java.io.IOException failure){throw new IllegalArgumentException("询价文档无法编码",failure);}
  }
  private <T> T read(String value,Class<T> type) {
    try{return json.readValue(value,type);}catch(java.io.IOException failure){throw new IllegalStateException("保存的询价文档无法读取",failure);}
  }
  private static void scope(String unit) { if(!Set.of("COMMERCIAL","HOUSEHOLD").contains(unit==null?"":unit))throw new IllegalArgumentException("必须选择有效业务单元"); }
  private static void actor(String actor) { if(actor==null||actor.isBlank()||actor.length()>64)throw new IllegalArgumentException("必须使用有效认证操作者"); }
  private static void month(String month) { if(month==null||!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")||month.startsWith("0000"))throw new IllegalArgumentException("请明确输入来源年月YYYY-MM，不能从页签、文件名或上传时间猜测年份"); }
  private static void validateRequest(PublishRequest request) {
    if(request==null||request.previewId()==null||request.previewId().isBlank()||request.previewId().length()>64||request.publishRequestId()==null||request.publishRequestId().isBlank()||request.publishRequestId().length()>64||request.previewFingerprint()==null||!request.previewFingerprint().matches("[0-9a-f]{64}"))throw fail("INVALID_REQUEST",400,"发布只接受预览返回的三项身份");
  }
  private static void pagination(int page,int size) { if(page<1||page>1_000_000||size<1||size>100)throw new IllegalArgumentException("页码应为1至1000000，每页1至100条"); }
  private static String optionalMaterial(String material) { if(material==null||material.isBlank())return null;if(material.length()>64||!material.equals(material.strip()))throw new IllegalArgumentException("必须使用精确料号");return material; }
  private static String plain(BigDecimal value) { return value.signum()==0?"0":value.stripTrailingZeros().toPlainString(); }
  private static HouseholdInquiryFailure fail(String code,int status,String message){return new HouseholdInquiryFailure(code,status,message);}
  private record PricingIdentity(String materialCode,String unitPrice,String householdRate,PriceContext context) {}
  private record RecordIdentity(HouseholdInquiryWorkbookParser.BusinessRow business,PriceContext context,String sourceMonth) {}
  private record PreviewIdentity(String businessUnit,String actor,String schema,PreviewContent content) {}
}
