package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Like cache invalidation, but durable and scoped; source writes and dirty markers commit together. */
@Aspect
@Component
public class TechnicalDataPendingInputWrites {
  private static final Set<String> TABLES=Set.of("lp_price_fixed_item","lp_price_range_item","lp_price_linked_item");
  private static final Set<String> MATERIAL_FIELDS=Set.of("materialCode","materialNo","material_code","material_no",
      "parentMaterialNo","productCode","topProductCode","gapMaterialCode");
  private final TechnicalDataPendingState state;
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager transactions;
  public TechnicalDataPendingInputWrites(TechnicalDataPendingState state,JdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.state=state;this.jdbc=jdbc;this.transactions=transactions;
  }

  @Around("@annotation(change)")
  public Object changed(ProceedingJoinPoint command,RefreshPendingInputs change) throws Throwable {
    var method=((org.aspectj.lang.reflect.MethodSignature)command.getSignature()).getMethod();
    var attributes=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
        .getTransactionAttribute(method,command.getTarget().getClass());
    if(attributes!=null && attributes.getPropagationBehavior()!=org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED)
      throw new IllegalStateException("独立事务的来源更新必须在其提交点登记待办变更");
    // Preserve the source command's original isolation/timeout/transaction definition.
    var transaction=attributes==null?new TransactionTemplate(transactions):new TransactionTemplate(transactions,attributes);
    try {
      return transaction.execute(status -> {
        var materials=new LinkedHashSet<String>();
        String unit=change.unitArgument()>=0?(String)command.getArgs()[change.unitArgument()]:BusinessUnitContext.getCurrentBusinessUnitType();
        if(!change.wholeUnit()) {
          for(Object arg:command.getArgs()) collect(arg,materials);
          if(!change.previousTable().isBlank()) {
            if(!TABLES.contains(change.previousTable())) throw new IllegalArgumentException("未登记的来源表");
            if(command.getArgs().length>0 && command.getArgs()[0] instanceof Long id) {
              materials.addAll(jdbc.queryForList("SELECT material_code FROM "+change.previousTable()+" WHERE id=?",String.class,id));
            }
          }
          Long taskId=null;
          if(change.taskArgument()>=0) {
            Object task=command.getArgs()[change.taskArgument()];
            taskId=task instanceof Long id?id:task instanceof com.sanhua.marketingcost.entity.QuoteTechTask entity?entity.getId():null;
          }
          if(change.productArgument()>=0 && command.getArgs()[change.productArgument()] instanceof Long productId) {
            var taskIds=jdbc.queryForList("SELECT task_id FROM lp_quote_tech_product WHERE id=?",Long.class,productId);
            if(!taskIds.isEmpty()) taskId=taskIds.getFirst();
          }
          if(taskId!=null) {
            materials.addAll(jdbc.queryForList("SELECT material_no FROM lp_quote_tech_product WHERE task_id=?",String.class,taskId));
            var units=jdbc.queryForList("SELECT business_unit_type FROM lp_quote_tech_task WHERE id=?",String.class,taskId);
            if(!units.isEmpty()) unit=units.getFirst();
          }
        }
        Object result;
        try {result=command.proceed();} catch(Throwable failure) {throw new CommandFailure(failure);}
        if(Boolean.FALSE.equals(result)) return result;
        if(!change.wholeUnit()) collect(result,materials);
        materials.removeIf(value -> value==null || value.isBlank());
        state.changed(new TechnicalDataPendingState.Change(Set.of(change.modules()),unit,materials,Set.of()));
        return result;
      });
    } catch(CommandFailure failure) {throw failure.getCause();}
  }

  private void collect(Object value,Set<String> codes) {
    if(value==null || value instanceof Number || value instanceof CharSequence
        || value instanceof org.springframework.web.multipart.MultipartFile || value instanceof java.io.InputStream
        || value instanceof byte[] || value instanceof TechnicalDataActor) return;
    if(value instanceof Collection<?> collection) {collection.forEach(item -> collect(item,codes));return;}
    if(value instanceof Map<?,?> map) {
      map.forEach((key,child) -> {if(MATERIAL_FIELDS.contains(key) && child instanceof String code) codes.add(code.trim());
        else if(child instanceof Map<?,?> || child instanceof Collection<?>) collect(child,codes);});
      return;
    }
    // Read only identity/container accessors. Do not serialize uploaded files or full business snapshots.
    var fields=new LinkedHashSet<>(MATERIAL_FIELDS);
    fields.addAll(List.of("rows","items","products","changes","groups"));
    for(String field:fields) {
      for(String accessor:List.of(field,"get"+Character.toUpperCase(field.charAt(0))+field.substring(1))) {
        try {
          var method=value.getClass().getMethod(accessor);
          if(method.getParameterCount()!=0) continue;
          Object child=method.invoke(value);
          if(MATERIAL_FIELDS.contains(field) && child instanceof String code) codes.add(code.trim());
          else if(child instanceof Collection<?> || child instanceof Map<?,?>) collect(child,codes);
          break;
        } catch(NoSuchMethodException absent) {
          // Records use component accessors; beans use getX.
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("无法读取变更资料身份",failure);}
      }
    }
  }
  private static class CommandFailure extends RuntimeException {
    CommandFailure(Throwable cause) {super(cause);}
  }
}
