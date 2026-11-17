package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.sanhua.marketingcost.security.BusinessUnitContext;

/** Refreshes only recorded changed workspaces; never invoked by a GET/list. */
@Service
@lombok.extern.slf4j.Slf4j
public class TechnicalDataPendingRefresh {
  private final TechnicalDataPendingState state;
  private final TechnicalDataQuoteSourceReader sources;
  private final TechnicalDataSharedModuleQuery shared;
  public TechnicalDataPendingRefresh(TechnicalDataPendingState state,TechnicalDataQuoteSourceReader sources,
      TechnicalDataSharedModuleQuery shared) {this.state=state;this.sources=sources;this.shared=shared;}

  public int runBatch() {
    int updated=0;
    for(var work:state.waiting(16)) {
      var previous=SecurityContextHolder.getContext();
      var context=SecurityContextHolder.createEmptyContext();
      var authentication=new UsernamePasswordAuthenticationToken("technical-pending-refresh",null,List.of());
      authentication.setDetails(work.unit()==null?Map.of():Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,work.unit()));
      context.setAuthentication(authentication);SecurityContextHolder.setContext(context);
      try {
        var current=sources.preview(work.itemId(),work.month());
        var unavailable = current.modules().stream()
            .filter(row -> row.availability() == TechnicalDataAvailability.ERROR)
            .map(row -> row.moduleType() + "[" + row.reasonCode() + "]: " + row.reason())
            .toList();
        if (!unavailable.isEmpty()) {
          state.retry(work);
          log.warn("待办资料来源暂未核实，保留已保存状态：item={}, month={}, reasons={}",
              work.itemId(), work.month(), unavailable);
          continue;
        }
        var merged=merge(work.original(),current);
        var sharedNow=shared.current(work.itemId(),work.month(),merged.modules());
        var projected=new TechnicalDataSourceCheckResponse(merged.oaFormItemId(),merged.accountingMonth(),merged.fingerprint(),
            merged.checkedAt(),TechnicalDataSharedModuleQuery.applySharedSources(merged.modules(),sharedNow),merged.taskUpdateMessage(),sharedNow);
        if(state.complete(work,projected)) updated++;
      } catch(RuntimeException failure) {
        state.retry(work);log.warn("待办资料更新暂未完成，保留已保存状态：item={}, month={}",work.itemId(),work.month(),failure);
      } finally {SecurityContextHolder.setContext(previous);}
    }
    return updated;
  }

  static TechnicalDataSourceCheckResponse merge(TechnicalDataSourceCheckResponse saved,TechnicalDataSourceCheckResponse current) {
    boolean mixed=saved.modules().stream().anyMatch(row -> "PRICE".equals(row.moduleType()) && "PRICE_BASIS_UNCONFIRMED".equals(row.reasonCode()));
    if(mixed && current.modules().stream().anyMatch(row -> "PRICE".equals(row.moduleType())
        && (row.availability()==TechnicalDataAvailability.MISSING || row.availability()==TechnicalDataAvailability.AVAILABLE))) return current;
    var modules=saved.modules().stream().map(old -> {
      if(!old.required() || "DRAWING_BOM".equals(old.moduleType())) return old;
      return current.modules().stream().filter(next -> next.moduleType().equals(old.moduleType()) && !next.required()
          && (next.availability()==TechnicalDataAvailability.AVAILABLE || "PRICE".equals(next.moduleType())
            && next.availability()==TechnicalDataAvailability.UNCONFIRMED && "PRICE_BASIS_UNCONFIRMED".equals(next.reasonCode())))
          .findFirst().orElse(old);
    }).toList();
    return new TechnicalDataSourceCheckResponse(saved.oaFormItemId(),saved.accountingMonth(),saved.fingerprint(),
        current.checkedAt(),modules,saved.taskUpdateMessage(),saved.sharedModules());
  }
}
