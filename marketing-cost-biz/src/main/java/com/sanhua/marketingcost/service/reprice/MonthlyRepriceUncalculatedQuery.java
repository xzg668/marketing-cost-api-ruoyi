package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.*;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue;
import com.sanhua.marketingcost.mapper.MonthlyRepriceUncalculatedMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A short read snapshot combines progress, failed customer objects and individually unresolved sources. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MonthlyRepriceUncalculatedQuery {
  private final MonthlyRepriceUncalculatedMapper query;
  private final PermissionService permissions;
  private final ObjectMapper json;
  public MonthlyRepriceUncalculatedQuery(MonthlyRepriceUncalculatedMapper query,PermissionService permissions,ObjectMapper json) {
    this.query=query;this.permissions=permissions;this.json=json;
  }

  public Page page(String month,String customer,String product,String status,int page,int size) {
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    if(!Set.of("COMMERCIAL","HOUSEHOLD").contains(Objects.toString(unit,"")))throw new AccessDeniedException("缺少有效业务单元");
    month=month==null?com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth():month;
    if(!month.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw new IllegalArgumentException("月份须为YYYY-MM");
    YearMonth.parse(month);
    if(page<1 || page>100000 || size<1 || size>100)throw new IllegalArgumentException("分页范围无效，每页最多100条");
    customer=filter(customer);product=filter(product);status=filter(status);
    if(status!=null && !Set.of("FAILED","PENDING","RUNNING","WAIT_CUSTOMER","DATA_ISSUE").contains(status))
      throw new IllegalArgumentException("未核算状态无效");
    boolean operator=MonthlyRepriceContractService.canOperate(unit);
    boolean review=operator || permissions.hasAnyPermi("price:monthly-reprice:review","price:monthly-reprice:operate","price:monthly-reprice:confirm");
    var batch=query.month(unit,month,review);
    if(batch==null)return new Page(null,month,new Statistics(0,0,0,0,0,0,0,0,0),operator,0,List.of(),List.of());
    var criteria=new MonthlyRepriceUncalculatedMapper.Query(batch.getId(),customer,product,status,(page-1)*size,size);
    long total=query.count(criteria);var items=query.page(criteria);
    var sourceIds=items.stream().filter(i->"SOURCE".equals(i.kind())).map(i->Long.toString(i.id())).toList();
    var attempts=sourceIds.isEmpty()?Map.<Long,MonthlyRepriceUncalculatedMapper.SourceAttempt>of():query.sourceAttempts(sourceIds).stream()
        .collect(Collectors.toMap(MonthlyRepriceUncalculatedMapper.SourceAttempt::id,Function.identity()));
    var rows=new ArrayList<Row>();
    for(var item:items) {
      var attempt=attempts.get(item.id());
      boolean source="SOURCE".equals(item.kind());
      String reason=item.reason();
      if(source && attempt!=null && "WAIT_CUSTOMER".equals(item.status()))reason=attempt.reason();
      if(reason==null || reason.isBlank())reason=switch(item.status()) {case "RUNNING"->"正在核算，请稍候";case "PENDING"->"已在核算队列中";default->"核算未完成，请检查资料";};
      boolean retry=operator
          && (("FAILED".equals(item.status()) && item.resultId()!=null) || "WAIT_CUSTOMER".equals(item.status()));
      rows.add(new Row(item.kind(),item.id(),item.resultId(),item.repriceNo(),item.pricingMonth(),item.productCode(),item.customer(),
          item.oaNo(),item.oaFormItemId(),item.sourceVersionId(),item.sourceCount(),item.status(),item.reasonCode(),reason,
          materials(item.diagnostics()),source && attempt!=null?attempt.retryCount():item.retryCount(),
          source && attempt!=null?attempt.lastAttemptAt():item.lastAttemptAt(),retry));
    }
    return new Page(batch.getRepriceNo(),month,query.statistics(batch.getId()),operator,total,rows,problems(batch.getCheckResultJson()));
  }
  private List<Issue> materials(String diagnostics) {
    if(diagnostics==null)return List.of();
    try {
      var rows=json.readTree(diagnostics).path("failure").path("issues");
      if(rows.isMissingNode() || rows.isNull())return List.of();
      if(!rows.isArray())throw new IllegalArgumentException("未核算物料记录格式无效");
      var result=new ArrayList<Issue>();for(var row:rows)result.add(json.treeToValue(row,Issue.class));return List.copyOf(result);
    } catch(java.io.IOException failure) {throw new IllegalStateException("未核算物料记录不可读取",failure);}
  }
  private List<String> problems(String evidence) {
    if(evidence==null)return List.of();
    try {
      var result=new ArrayList<String>();for(var node:json.readTree(evidence).path("problems"))result.add(node.asText());return List.copyOf(result);
    } catch(java.io.IOException failure) {throw new IllegalStateException("批次检查记录不可读取",failure);}
  }
  private static String filter(String text) {
    if(text==null || text.isBlank())return null;
    text=text.trim();if(text.length()>255)throw new IllegalArgumentException("查询条件过长");return text;
  }
}
