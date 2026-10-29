package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Legacy quantity bands retain their original primary-supplier policy and exact decision regions. */
@Component
public final class MonthlyQuantityRangeSource {
  public record SelectionProof(QuantityInterval quantities,String matchMode,int candidateSupplierCount,
      String supplierCode,String supplierName,Long supplyRatioRecordId,BigDecimal supplyRatio) {}
  public record Source(PriceRangeItem selected,List<PriceRangeItem> candidates,String supplierRatioMaterialCode,List<SupplierSupplyRatio> ratios,List<SelectionProof> regions) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String priceColumn,String conversion) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private record Cell(QuantityInterval interval,BigDecimal probe) {}
  private record Decision(Cell cell,SupplierPreferredPriceSelection<PriceRangeItem> selection,Problem problem) {}
  private record FamilyDecisions(List<PriceRangeItem> rows,List<Decision> decisions,Map<Long,Selection> selected) {}
  private final MonthlyQuantityRangeSourceMapper rows;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPreferredPriceSelector suppliers;
  private final MonthlyPriceDocumentCodec codec;
  public MonthlyQuantityRangeSource(MonthlyQuantityRangeSourceMapper rows,MonthlySupplierInputMapper supplierInputs,SupplierPreferredPriceSelector suppliers,MonthlyPriceDocumentCodec codec){this.rows=rows;this.supplierInputs=supplierInputs;this.suppliers=suppliers;this.codec=codec;}

  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Quantity source scope differs from request");
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>()).computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    for(var organization:groups.entrySet()){
      var codes=new ArrayList<>(organization.getValue().keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=codes.subList(start,Math.min(start+256,codes.size()));var byCode=new HashMap<String,List<PriceRangeItem>>();
        rows.prices(context.businessUnitType(),organization.getKey(),context.priceAsOfTime().toLocalDate(),batch).forEach(row->byCode.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
        // The legacy supplier-input key is normalized; the monthly family still keeps the exact code.
        var ratioCodes=byCode.keySet().stream().map(SupplierSupplyRatioNormalizeUtils::normalizeToNull).collect(java.util.stream.Collectors.toSet());
        var capturedRatios=ratioCodes.isEmpty()?List.<com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput>of():supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),ratioCodes);
        var ratios=new HashMap<String,List<SupplierSupplyRatio>>();capturedRatios.forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var primary=suppliers.prepareSuppliers(context.businessUnitType(),ratioCodes,context.priceAsOfTime().toLocalDate(),ratios);
        var calculations=new HashMap<MonthlyPriceFamilyKey,FamilyDecisions>();
        for(String code:batch)for(var request:organization.getValue().get(code))try{
          var source=byCode.getOrDefault(code,List.of());if(source.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"没有适用数量区间来源"));continue;}
          if(!"CNY".equals(request.family().currency()))throw new IllegalArgumentException("数量区间现有CNY成本口径不能猜测币种转换");
          var calculation=calculations.computeIfAbsent(request.family(),family->decisions(context,request,source,primary.getOrDefault(SupplierSupplyRatioNormalizeUtils.normalizeToNull(code),SupplierSupplyRatioResolveResult.miss("没有有效主供应商"))));
          var decision=calculation.decisions().stream().filter(value->value.cell().interval().contains(request.priceBasisQuantity())).findFirst().orElseThrow();
          if(decision.problem()!=null){problems.put(request,decision.problem());continue;}
          var selected=decision.selection().row();
          var selection=calculation.selected().computeIfAbsent(selected.getId(),ignored->selection(request,selected,calculation,ratios.getOrDefault(SupplierSupplyRatioNormalizeUtils.normalizeToNull(code),List.of())));
          values.put(request,new Candidate(selection,null));
        }catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"数量区间来源无效："+invalid.getMessage()));}
      }
    }
    return new Candidates(values,problems);
  }
  private FamilyDecisions decisions(Context context,MonthlyPriceRequest request,List<PriceRangeItem> original,SupplierSupplyRatioResolveResult primary){
    String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
    var allowed=original.stream().filter(row->explicit==null || explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode())))
        .filter(row->request.family().unit().equals(row.getUnit()) && (request.family().specification()==null || request.family().specification().equals(row.getSpecModel()))).toList();
    if(allowed.isEmpty())throw new IllegalArgumentException("数量区间的规格、计价单位或指定供应商不适用");
    var intervals=new LinkedHashMap<Long,QuantityInterval>();var bounds=new TreeSet<BigDecimal>();
    for(var row:allowed){var interval=new QuantityInterval(row.getRangeLow(),!Integer.valueOf(0).equals(row.getLowerInclusive()),row.getRangeHigh(),!Integer.valueOf(0).equals(row.getUpperInclusive()));intervals.put(row.getId(),interval);if(interval.lower()!=null)bounds.add(interval.lower());if(interval.upper()!=null)bounds.add(interval.upper());}
    var decisions=new ArrayList<Decision>();
    for(var cell:cells(bounds)){
      var matched=allowed.stream().filter(row->intervals.get(row.getId()).contains(cell.probe())).toList();
      if(matched.isEmpty()){decisions.add(new Decision(cell,null,new Problem(Failure.MISSING,"数量未命中任何区间")));continue;}
      var selected=suppliers.select(matched,context.businessUnitType(),request.family().materialCode(),matched.getFirst().getMaterialName(),matched.getFirst().getSpecModel(),context.priceAsOfTime().toLocalDate(),PriceRangeItem::getSupplierName,PriceRangeItem::getSupplierCode,primary);
      if(selected.failed() || selected.row()==null){decisions.add(new Decision(cell,selected,new Problem(Failure.WAITING,selected.failureMessage())));continue;}
      var tax=selected.row().getPriceInclTax()!=null?TaxBasis.INCLUDED:TaxBasis.EXCLUDED;
      var problem=tax==request.family().taxBasis()?null:new Problem(Failure.INVALID_INPUT,"现有区间优先金额列的税口径不适用，不能猜测转换");
      decisions.add(new Decision(cell,selected,problem));
    }
    return new FamilyDecisions(allowed,List.copyOf(decisions),new HashMap<>());
  }
  private Selection selection(MonthlyPriceRequest request,PriceRangeItem row,FamilyDecisions calculation,List<SupplierSupplyRatio> ratios){
    var proofs=new ArrayList<SelectionProof>();var regions=new ArrayList<QuantityInterval>();
    for(var decision:calculation.decisions())if(decision.problem()==null && row.getId().equals(decision.selection().row().getId())){
      var chosen=decision.selection();regions.add(decision.cell().interval());proofs.add(new SelectionProof(decision.cell().interval(),chosen.matchMode(),chosen.candidateSupplierCount(),chosen.mainSupplierCode(),chosen.mainSupplierName(),chosen.supplyRatioRecordId(),chosen.supplyRatio()));
    }
    var coverage=new MonthlyPriceApplicability(merge(regions).stream().map(region->new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),region,ConditionDomain.any())).toList());
    String column=row.getPriceInclTax()!=null?"price_incl_tax":"price_excl_tax";BigDecimal price=row.getPriceInclTax()!=null?row.getPriceInclTax():row.getPriceExclTax();
    var snapshot=new Snapshot(1,"RANGE_QTY",new Source(row,calculation.rows(),SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().materialCode()),ratios,List.copyOf(proofs)),new Basis(request.family().unit(),"CNY",request.family().taxBasis(),column,"IDENTITY"));
    return new Selection(null,coverage,PriceFact.normal(price,SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of()));
  }
  private List<Cell> cells(SortedSet<BigDecimal> bounds){
    if(bounds.isEmpty())return List.of(new Cell(QuantityInterval.all(),BigDecimal.ZERO));
    var result=new ArrayList<Cell>();BigDecimal previous=null;
    for(var bound:bounds){
      result.add(new Cell(new QuantityInterval(previous,false,bound,false),previous==null?bound.subtract(BigDecimal.ONE):previous.add(bound).divide(BigDecimal.valueOf(2))));
      result.add(new Cell(new QuantityInterval(bound,true,bound,true),bound));previous=bound;
    }
    result.add(new Cell(new QuantityInterval(previous,false,null,false),previous.add(BigDecimal.ONE)));return result;
  }
  private List<QuantityInterval> merge(List<QuantityInterval> cells){
    var result=new ArrayList<QuantityInterval>();
    for(var interval:cells){
      if(!result.isEmpty()){
        var last=result.getLast();if(last.upper()!=null && interval.lower()!=null && last.upper().compareTo(interval.lower())==0 && (last.upperInclusive() || interval.lowerInclusive())){
          result.set(result.size()-1,new QuantityInterval(last.lower(),last.lowerInclusive(),interval.upper(),interval.upperInclusive()));continue;
        }
      }
      result.add(interval);
    }
    return List.copyOf(result);
  }
}
