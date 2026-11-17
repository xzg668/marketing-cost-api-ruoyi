package com.sanhua.marketingcost.service.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persistent requirements in the existing price-type table; reads never create tasks or prices. */
@Component
public class MaterialSpecialPricePolicies {
  private final JdbcTemplate jdbc;
  private final MonthlyPriceDocumentCodec codec;
  public MaterialSpecialPricePolicies(JdbcTemplate jdbc, MonthlyPriceDocumentCodec codec) {
    this.jdbc=jdbc; this.codec=codec;
  }
  public Map<MonthlyPriceRequest,MaterialSpecialPricePolicy> read(Collection<MonthlyPriceRequest> requests, boolean lock) {
    var groups=new TreeMap<String,List<MonthlyPriceRequest>>();
    for(var request:requests) if(!request.family().specificationOnly())
      groups.computeIfAbsent(request.family().businessUnitType(),ignored->new ArrayList<>()).add(request);
    var result=new LinkedHashMap<MonthlyPriceRequest,MaterialSpecialPricePolicy>();
    for(var group:groups.entrySet()) {
      var codes=group.getValue().stream().map(MonthlyPriceRequest::materialCode).distinct().sorted().toList();
      for(int start=0;start<codes.size();start+=256) {
        var chunk=codes.subList(start,Math.min(start+256,codes.size()));
        var args=new ArrayList<Object>();args.add(group.getKey());args.addAll(chunk);
        var rows=jdbc.query("SELECT * FROM lp_material_price_type WHERE business_unit_type=? AND special_rule=1 AND material_code IN ("
            +String.join(",",Collections.nCopies(chunk.size(),"?"))+") ORDER BY material_code,price_org_code,id"+(lock?" FOR SHARE":""),
            (rs,n)->new MaterialSpecialPricePolicy(rs.getLong("id"),rs.getString("business_unit_type"),rs.getString("price_org_code"),
                rs.getString("material_code"),rs.getString("material_name"),rs.getString("material_spec"),rs.getString("material_model"),
                rs.getString("price_type"),rs.getString("price_refresh_mode"),rs.getInt("rule_enabled")==1,rs.getString("reminder_text"),rs.getLong("rule_version")),args.toArray());
        for(var request:group.getValue()) for(var rule:rows)
          if(rule.enabled()&&rule.materialCode().equals(request.materialCode())
              && (rule.priceOrgCode().equals(request.priceOrgCode())||"ALL".equals(rule.priceOrgCode()))
              && (!result.containsKey(request)||rule.priceOrgCode().equals(request.priceOrgCode()))) result.put(request,rule);
      }
    }
    return result;
  }
  public boolean accepts(MonthlyPriceRequest request, Selection selected, MaterialSpecialPricePolicy rule) {
    var document=codec.decode(codec.wrap(selected.fact().evidence().json()),JsonNode.class);
    var stamp=document.path("specialPricePolicy");
    if(rule==null) return stamp.isMissingNode();
    return stamp.path("id").asLong()==rule.id() && stamp.path("version").asLong()==rule.version()
        && stamp.path("type").asText().equals(rule.priceType()) && stamp.path("mode").asText().equals(rule.refreshMode())
        && (!rule.currentMonthOnly()||request.family().month().toString().equals(sourceMonth(document)));
  }
  public Candidate stamp(Context context, MonthlyPriceRequest request, Candidate candidate, MaterialSpecialPricePolicy rule) {
    var selected=candidate.selection();var fact=selected.fact();
    var document=codec.decode(codec.wrap(fact.evidence().json()),ObjectNode.class);
    String month=sourceMonth(document);
    if(rule.currentMonthOnly()&&!request.family().month().toString().equals(month))
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.SPECIAL_PRICE_REQUIRED,rule.requiredMessage(request.family().month().toString()));
    if(month==null||month.compareTo(request.family().month().toString())>0)
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.SPECIAL_PRICE_REQUIRED,rule.requiredMessage(request.family().month().toString()));
    var stamp=document.putObject("specialPricePolicy");stamp.put("id",rule.id());stamp.put("version",rule.version());
    stamp.put("type",rule.priceType());stamp.put("mode",rule.refreshMode());
    var revised=new PriceFact(fact.type(),fact.price(),fact.householdRate(),SourceEvidence.ofJson(codec.unwrap(codec.encode(document))),fact.dependencies());
    context.supplierReviews().successfulReviews().stream().filter(binding->binding.evidenceSha256().equals(fact.evidence().sha256())).toList()
        .forEach(binding->context.supplierReviews().add(binding.review(),revised.evidence().sha256()));
    // A policy is scoped to the consuming organization; it cannot create an unconstrained cross-org price.
    var fragments=selected.applicability().fragments().stream().filter(part->part.organizations().contains(request.priceOrgCode()))
        .map(part->new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),part.quantities(),part.conditions(),part.annualUsage())).toList();
    return new Candidate(new Selection(null,new MonthlyPriceApplicability(fragments,MonthlyPriceDocumentCodec.sha256(rule.id()+":"+rule.version()+":"+rule.priceType()+":"+rule.refreshMode())),revised),candidate.householdSourceId());
  }
  /** Special carry-forward is per material and type, independent of the ordinary global workbook month. */
  public String sourceMonth(MonthlyPriceRequest request,MaterialSpecialPricePolicy rule) {
    String month=request.family().month().toString();
    if(rule.currentMonthOnly())return month;
    String table=switch(rule.priceType()) {
      case "LINKED" -> "lp_price_linked_item";
      case "RANGE" -> "lp_price_range_factor_rule";
      default -> throw new IllegalArgumentException("该来源不使用原稿选月");
    };
    String extra="LINKED".equals(rule.priceType())?" AND deleted=0 AND source_kind='PUBLIC'":"";
    String found=jdbc.queryForObject("SELECT MAX(pricing_month) FROM "+table+" WHERE business_unit_type=? AND org_code=? AND material_code=?"
        +" AND pricing_month<=? AND rule_status IN ('LEGACY','PUBLISHED')"+extra,String.class,
        request.family().businessUnitType(),request.priceOrgCode(),request.materialCode(),month);
    return found==null?month:found;
  }
  static String sourceMonth(JsonNode document) {
    var source=document.path("source");
    for(String pointer:List.of("/selected/original/sourceMonth","/originalMonth","/selected/pricingMonth","/selected/priceMonth","/selected/period",
        "/rule/priceMonth","/rule/pricingMonth","/priceMonth","/pricingMonth","/selected/sourceMonth")) {
      String value=source.at(pointer).asText("");
      if(value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))return value;
    }
    return null;
  }
}
