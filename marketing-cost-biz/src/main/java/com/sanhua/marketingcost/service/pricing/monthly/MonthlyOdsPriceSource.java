package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.SupplierPriceDecision;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.entity.monthly.OdsMaterialPriceRow;
import com.sanhua.marketingcost.mapper.monthly.MonthlyOdsPriceSourceMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlySupplierInputMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Read-only ODS fallbacks. Captures complete raw evidence and reuses the existing supplier policy. */
@Component
public final class MonthlyOdsPriceSource {
  public record Source(OdsMaterialPriceRow selected,List<OdsMaterialPriceRow> candidates,
      List<SupplierSupplyRatio> ratios,String selectionRule,List<QuantityInterval> annualDecisionRegions) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String priceColumn,String conversion) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private record Cell(QuantityInterval annual,BigDecimal probe) {}
  private record Decision(Cell cell,OdsMaterialPriceRow row,SupplierPriceSelectionService.Selection supplier,Problem problem) {}
  private final MonthlyOdsPriceSourceMapper rows;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPriceSelectionService suppliers;
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyOdsPriceSource(MonthlyOdsPriceSourceMapper rows,MonthlySupplierInputMapper supplierInputs,
      SupplierPriceSelectionService suppliers,MonthlyPriceDocumentCodec codec) {
    this.rows=rows;this.supplierInputs=supplierInputs;this.suppliers=suppliers;this.codec=codec;
  }
  public Candidates orders(Context context,Set<MonthlyPriceRequest> requests) { return read(context,requests,false); }
  public Candidates tiers(Context context,Set<MonthlyPriceRequest> requests) { return read(context,requests,true); }

  private Candidates read(Context context,Set<MonthlyPriceRequest> requests,boolean tier) {
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests) {
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))
        throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"ODS取价范围与报价不一致");
      if(!"COMMERCIAL".equals(context.businessUnitType())) continue;
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>())
          .computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    for(var group:groups.entrySet()) {
      String company=switch(group.getKey()) { case "210" -> "浙江三花商用制冷有限公司";
        case "220" -> "浙江三花板换科技有限公司"; default -> throw new IllegalArgumentException("ODS未知取价组织"); };
      var codes=new ArrayList<>(group.getValue().keySet());
      for(int start=0;start<codes.size();start+=256) {
        var batch=codes.subList(start,Math.min(start+256,codes.size()));
        var raw=tier?rows.tiers(company,batch):rows.orders(company,batch);
        var byCode=new HashMap<String,List<OdsMaterialPriceRow>>();
        raw.forEach(row->byCode.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
        var relationCodes=new TreeSet<>(byCode.keySet());
        var ratios=new HashMap<String,List<SupplierSupplyRatio>>();
        if(!relationCodes.isEmpty())supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),relationCodes)
            .forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var keys=relationCodes.stream().filter(code->byCode.get(code).size()>1).map(code->SupplierPriceSelectionService.scopeKey(scope(context,group.getKey(),code,tier))).toList();
        var decisions=new HashMap<String,SupplierPriceDecision>();
        if(!keys.isEmpty())supplierInputs.decisions(keys).forEach(row->decisions.put(row.getScopeKey(),row));
        for(String code:batch)for(var request:group.getValue().get(code))try {
          var applicable=applicable(request,byCode.getOrDefault(code,List.of()));
          if(applicable.isEmpty())continue;
          if(!"CNY".equals(request.family().currency()))throw new IllegalArgumentException("ODS价格不能猜测币种转换");
          if(request.family().taxBasis()!=TaxBasis.EXCLUDED)continue;
          var actualRatios=ratios.getOrDefault(code,List.of());
          var decision=decisions.get(SupplierPriceSelectionService.scopeKey(scope(context,request.priceOrgCode(),code,tier)));
          var candidate=tier?tier(context,request,applicable,actualRatios,decision,problems)
              :order(context,request,applicable,actualRatios,decision,problems);
          if(candidate!=null)values.put(request,candidate);
        } catch(IllegalArgumentException | IllegalStateException invalid) {
          problems.put(request,new Problem(Failure.INVALID_INPUT,(tier?"阶梯":"订单")+"来源无效："+invalid.getMessage()));
        }
      }
    }
    for(var request:requests)if(!values.containsKey(request))
      problems.putIfAbsent(request,new Problem(Failure.MISSING,tier?"没有适用的ODS阶梯价格":"没有适用的ODS订单价格"));
    return new Candidates(values,problems);
  }

  private List<OdsMaterialPriceRow> applicable(MonthlyPriceRequest request,List<OdsMaterialPriceRow> raw) {
    String specified=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
    return raw.stream().filter(row->request.family().unit().equals(row.getUnit()==null?null:row.getUnit().strip()))
        .filter(row->request.family().specification()==null || request.family().specification().equals(row.getSpec()==null?null:row.getSpec().strip()))
        .filter(row->specified==null || specified.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode())))
        .sorted(Comparator.comparing(OdsMaterialPriceRow::getId)).toList();
  }

  private Candidate order(Context context,MonthlyPriceRequest request,List<OdsMaterialPriceRow> raw,
      List<SupplierSupplyRatio> ratios,SupplierPriceDecision decision,Map<MonthlyPriceRequest,Problem> problems) {
    raw=SupplyPriceDatePolicy.preferCurrent(raw,context.priceAsOfTime().toLocalDate(),
        row->date(row.getEffDate()),row->date(row.getExpDate()));
    var versions=OdsOrderPriceVersions.latestPerSupplier(raw.stream().map(row->
        new OdsOrderPriceVersions.Price(row.getSupplierCode(),date(row.getEffDate()),date(row.getExpDate()),row.getPrice())).toList());
    var active=new TreeMap<String,OdsMaterialPriceRow>();
    for(var row:raw) {
      var winner=versions.get(row.getSupplierCode().strip());
      if(Objects.equals(winner.effectiveDate(),date(row.getEffDate()))
          && Objects.equals(winner.expiryDate(),date(row.getExpDate())) && winner.price().compareTo(row.getPrice())==0)
        active.putIfAbsent(winner.supplierCode(),row);
    }
    var chosen=choose(context,request,active,ratios,decision,false);
    if(chosen.candidate()==null){review(context,request,chosen,null);problems.put(request,new Problem(Failure.WAITING,chosen.message()));return null;}
    var selected=active.get(chosen.candidate().supplierCode());
    var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any()));
    var candidate=candidate(request,"ODS_ORDER",selected,raw,ratios,chosen.rule(),List.of(),coverage);
    review(context,request,chosen,candidate.selection().fact().evidence().sha256());return candidate;
  }

  private Candidate tier(Context context,MonthlyPriceRequest request,List<OdsMaterialPriceRow> raw,
      List<SupplierSupplyRatio> ratios,SupplierPriceDecision approval,Map<MonthlyPriceRequest,Problem> problems) {
    var versions=OdsTierPriceVersions.latestPerSupplier(raw,context.priceAsOfTime().toLocalDate());
    if(versions.isEmpty())return null; // No applicable tier rows: annual usage cannot block a later source.
    var usage=context.annualUsage();
    var demand=OdsTierAnnualUsage.require(usage==null?null:usage.value(),usage==null?null:usage.unit());
    if(request.annualUsagePieces()==null || demand.annualPieces().compareTo(request.annualUsagePieces())!=0)
      throw new IllegalArgumentException("阶梯数量未绑定当前报价成品明细的年用量");
    var latest=versions.values().stream().flatMap(List::stream).toList();
    var intervals=new IdentityHashMap<OdsMaterialPriceRow,QuantityInterval>();var bounds=new TreeSet<BigDecimal>();
    for(var row:latest) {
      if(row.getJietiDown()==null || row.getJietiDown().signum()<0 || row.getPrice()==null || row.getPrice().signum()<0)
        throw new IllegalArgumentException("阶梯数量下限或价格无效");
      var interval=OdsTierAnnualUsage.annualBand(new QuantityInterval(
          row.getJietiDown(),true,row.getJietiUp(),false));
      intervals.put(row,interval);bounds.add(interval.lower());if(interval.upper()!=null)bounds.add(interval.upper());
    }
    var decisions=new ArrayList<Decision>();
    for(var cell:cells(bounds)) {
      var active=new TreeMap<String,OdsMaterialPriceRow>();Problem problem=null;
      for(var row:latest)if(intervals.get(row).contains(cell.probe())) {
        var previous=active.putIfAbsent(row.getSupplierCode().strip(),row);
        if(previous!=null && previous.getPrice().compareTo(row.getPrice())!=0)
          problem=new Problem(Failure.INVALID_INPUT,"同供应商最新一套阶梯存在重叠的不同价格");
      }
      if(problem!=null){decisions.add(new Decision(cell,null,null,problem));continue;}
      if(active.isEmpty()){decisions.add(new Decision(cell,null,null,new Problem(Failure.MISSING,"数量未命中阶梯")));continue;}
      var chosen=choose(context,request,active,ratios,approval,true);
      decisions.add(new Decision(cell,chosen.candidate()==null?null:active.get(chosen.candidate().supplierCode()),chosen,
          chosen.candidate()==null?new Problem(Failure.WAITING,chosen.message()):null));
    }
    var current=decisions.stream().filter(d->d.cell().annual().contains(demand.annualPieces())).findFirst().orElseThrow();
    if(current.problem()!=null) {
      if(current.supplier()!=null)review(context,request,current.supplier(),null);
      problems.put(request,current.problem());return null;
    }
    var regions=decisions.stream().filter(d->d.problem()==null && Objects.equals(d.row().getId(),current.row().getId()))
        .map(d->d.cell().annual()).toList();
    var coverage=new MonthlyPriceApplicability(regions.stream().map(interval->new MonthlyPriceCoverage(
        Set.of(request.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any(),AnnualUsageDomain.known(interval))).toList());
    var candidate=candidate(request,"ODS_TIER",current.row(),latest,ratios,current.supplier().rule(),regions,coverage);
    review(context,request,current.supplier(),candidate.selection().fact().evidence().sha256());return candidate;
  }

  private SupplierPriceSelectionService.Selection choose(Context context,MonthlyPriceRequest request,
      Map<String,OdsMaterialPriceRow> active,List<SupplierSupplyRatio> ratios,SupplierPriceDecision decision,boolean tier) {
    var candidates=active.entrySet().stream().map(entry->{var row=entry.getValue();
      String key=tier?entry.getKey()+":"+row.getJietiDown()+":"+row.getJietiUp():entry.getKey();
      return new SupplierPriceCandidate(key,entry.getKey(),row.getSupplierName(),row.getPrice(),date(row.getEffDate()),date(row.getExpDate()));
    }).toList();
    return suppliers.selectPrepared(scope(context,request.priceOrgCode(),request.family().materialCode(),tier),candidates,ratios,decision,context.supplierReviews().packagingHighestFallback());
  }
  private Candidate candidate(MonthlyPriceRequest request,String kind,OdsMaterialPriceRow chosen,List<OdsMaterialPriceRow> raw,
      List<SupplierSupplyRatio> ratios,String rule,List<QuantityInterval> regions,MonthlyPriceApplicability coverage) {
    var snapshot=new Snapshot(1,kind,new Source(chosen,raw,ratios,rule,regions),
        new Basis(request.family().unit(),"CNY",TaxBasis.EXCLUDED,"price","IDENTITY"));
    var fact=PriceFact.normal(chosen.getPrice(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
    return new Candidate(new Selection(null,coverage,fact),null);
  }
  private void review(Context context,MonthlyPriceRequest request,SupplierPriceSelectionService.Selection selection,String evidence) {
    if(selection.review()==null)return;
    var reviews=context.supplierReviews().forOrganization(request.priceOrgCode());
    if(evidence==null)reviews.add(selection.review());else reviews.add(selection.review(),evidence);
  }
  private static List<Cell> cells(SortedSet<BigDecimal> bounds) {
    var result=new ArrayList<Cell>();BigDecimal previous=null;
    for(var bound:bounds){result.add(new Cell(new QuantityInterval(previous,previous!=null,bound,false),
        previous==null?bound.subtract(BigDecimal.ONE):previous.add(bound).divide(BigDecimal.valueOf(2))));previous=bound;}
    result.add(new Cell(new QuantityInterval(previous,true,null,false),previous.add(BigDecimal.ONE)));return result;
  }
  private static LocalDate date(String text) {
    if(text==null || text.isBlank())return null;
    try { return LocalDate.parse(text.strip()); }
    catch(java.time.format.DateTimeParseException invalid) { throw new IllegalArgumentException("订单来源日期无效："+text,invalid); }
  }
  private static SupplierPriceScope scope(Context c,String org,String code,boolean tier) {
    return new SupplierPriceScope(c.businessUnitType(),c.oaNo(),c.oaFormItemId(),c.month().toString(),c.priceAsOfTime().toLocalDate(),org,code,tier?"ODS_TIER":"ODS_ORDER");
  }
}
