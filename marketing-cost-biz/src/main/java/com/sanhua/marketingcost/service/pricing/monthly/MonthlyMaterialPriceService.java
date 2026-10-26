package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One atomic set of adopted facts; BOM planning and product costing stay outside this module. */
public class MonthlyMaterialPriceService implements MonthlyMaterialPrices {
  public record Dependency(MonthlyPriceRequest request,long monthlyPriceId) {
    public Dependency {Objects.requireNonNull(request);if(monthlyPriceId<=0)throw new IllegalArgumentException("Saved child ID required");}
  }
  public record FirstUse(int schemaVersion,Context origin,List<Dependency> dependencies,
      List<com.sanhua.marketingcost.service.pricing.SupplierPriceReview> supplierReviews) {
    public FirstUse {Objects.requireNonNull(origin);dependencies=List.copyOf(dependencies);supplierReviews=List.copyOf(supplierReviews);}
  }
  private record ConsumerPrice(String organization,String materialCode) {}
  private record Restored(Map<Long,Selection> values,Map<MonthlyPriceFamilyKey,List<MonthlyPriceStorage.StoredPrice>> families) {}
  private final MonthlyPriceStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPriceCandidateResolver sources;
  private final PlatformTransactionManager transactions;
  private final java.time.Duration connectionAcquisitionBudget;
  public MonthlyMaterialPriceService(MonthlyPriceStorage storage,PlatformTransactionManager transactions,MonthlyPriceDocumentCodec codec,MonthlyPriceCandidateResolver sources){
    this(storage,transactions,codec,sources,java.time.Duration.ZERO);
  }
  MonthlyMaterialPriceService(MonthlyPriceStorage storage,PlatformTransactionManager transactions,MonthlyPriceDocumentCodec codec,MonthlyPriceCandidateResolver sources,java.time.Duration connectionAcquisitionBudget){
    this.storage=storage;this.codec=codec;this.sources=sources;
    this.transactions=Objects.requireNonNull(transactions);
    this.connectionAcquisitionBudget=Objects.requireNonNull(connectionAcquisitionBudget);if(connectionAcquisitionBudget.isNegative())throw new IllegalArgumentException("Connection acquisition budget cannot be negative");
  }
  @Override public Lookup lookup(Collection<MonthlyPriceRequest> requests){
    return databaseRead(()->lookupSaved(requests));
  }
  private Lookup lookupSaved(Collection<MonthlyPriceRequest> requests){
    var unique=new LinkedHashSet<>(requests);var rows=storage.readFamilies(unique.stream().map(MonthlyPriceRequest::family).toList());
    var complete=restore(rows);var selected=new LinkedHashMap<MonthlyPriceRequest,Selection>();var missing=new LinkedHashSet<MonthlyPriceRequest>();
    for(var request:unique){var found=matching(request,complete.families().getOrDefault(request.family(),List.of()));if(found==null)missing.add(request);else selected.put(request,complete.values().get(found.id()));}
    // Dependencies retain the saved request aliases, and are never rebuilt from current sources.
    var queue=new ArrayDeque<>(selected.values());
    while(!queue.isEmpty())for(var dependency:queue.remove().fact().dependencies()){
      if(selected.containsKey(dependency))continue;
      var found=matching(dependency,complete.families().getOrDefault(dependency.family(),List.of()));
      if(found==null)throw failure(Failure.PERSISTENCE_INCOMPLETE,"Saved dependency request is not uniquely resolved");
      var value=complete.values().get(found.id());selected.put(dependency,value);queue.add(value);
    }
    return new Lookup(selected,missing);
  }
  @Override public Candidates resolveCandidates(Context context,Set<MonthlyPriceRequest> requests){
    return databaseRead(()->resolveCandidates(context,requests,Set.of(),false));
  }
  @Override public Candidates resolveScrapCandidates(Context context,Set<MonthlyPriceRequest> requests){
    return databaseRead(()->resolveCandidates(context,requests,Set.of(),true));
  }
  private Candidates resolveCandidates(Context context,Set<MonthlyPriceRequest> requests,Set<Set<MonthlyPriceRequest>> activeBatches,boolean scrap){
    scope(context,requests);var lookup=lookup(requests);var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();
    lookup.values().forEach((request,value)->values.put(request,new Candidate(value,null)));
    if(lookup.missing().isEmpty())return new Candidates(values,Map.of());
    if(activeBatches.contains(lookup.missing()))throw failure(Failure.INVALID_INPUT,"Cyclic current-source dependency requests");
    var next=new HashSet<>(activeBatches);next.add(lookup.missing());var active=Set.copyOf(next);
    java.util.function.Function<Set<MonthlyPriceRequest>,Candidates> children=dependencies->resolveCandidates(context,dependencies,active,true);
    var resolved=scrap?sources.resolveScrap(context,lookup.missing(),children):sources.resolve(context,lookup.missing(),children);
    var reported=new HashSet<>(resolved.values().keySet());reported.addAll(resolved.problems().keySet());
    if(!reported.containsAll(lookup.missing()))throw failure(Failure.PERSISTENCE_INCOMPLETE,"Source resolver omitted requests");
    scope(context,reported);
    var extra=new LinkedHashSet<>(reported);extra.removeAll(lookup.missing());
    // A dependency callback can discover a concurrent winner for one of the original roots.
    // Verify every claimed saved identity against the database, including those roots.
    resolved.values().forEach((request,candidate)->{if(candidate.selection().monthlyPriceId()!=null)extra.add(request);});
    extra.removeAll(lookup.values().keySet());
    var authoritative=new LinkedHashMap<>(lookup.values());if(!extra.isEmpty())authoritative.putAll(lookup(extra).values());
    authoritative.forEach((request,selection)->values.putIfAbsent(request,new Candidate(selection,null)));
    for(var problem:resolved.problems().keySet())if(authoritative.containsKey(problem))throw failure(lookup.values().containsKey(problem)?Failure.PERSISTENCE_INCOMPLETE:Failure.REPLAN_REQUIRED,"Source problem contradicts an adopted dependency");
    var unadopted=resolved.values().keySet().stream().filter(request->!authoritative.containsKey(request)).toList();
    var occupied=storage.readFamilies(unadopted.stream().map(MonthlyPriceRequest::family).toList());
    for(var entry:resolved.values().entrySet()){
      var supplied=entry.getValue().selection();var saved=authoritative.get(entry.getKey());
      if(saved!=null){
        if(supplied.monthlyPriceId()!=null && !supplied.monthlyPriceId().equals(saved.monthlyPriceId()))throw failure(Failure.PERSISTENCE_INCOMPLETE,"Source substituted a saved monthly price identity");
        if(!supplied.applicability().equals(saved.applicability()) || !supplied.fact().equals(saved.fact()))throw failure(lookup.values().containsKey(entry.getKey()) || supplied.monthlyPriceId()!=null?Failure.PERSISTENCE_INCOMPLETE:Failure.REPLAN_REQUIRED,"Source candidate contradicts the adopted fact; replan its parent");
        continue;
      }
      if(supplied.monthlyPriceId()!=null)throw failure(Failure.PERSISTENCE_INCOMPLETE,"Source fabricated a saved monthly price identity");
      if(!supplied.applicability().matches(entry.getKey()))throw failure(Failure.INVALID_INPUT,"Source returned an invalid candidate binding");
      var remaining=Optional.of(entry.getValue().selection().applicability());
      for(var row:occupied)if(row.family().equals(entry.getKey().family()))remaining=remaining.flatMap(value->value.subtract(row.applicability()));
      if(remaining.isEmpty() || !remaining.get().matches(entry.getKey()))throw failure(Failure.REPLAN_REQUIRED,"Source candidate was occupied; read committed winners again");
      var candidate=entry.getValue();values.put(entry.getKey(),new Candidate(new Selection(null,remaining.get(),candidate.selection().fact()),candidate.householdSourceId()));
    }
    return new Candidates(values,resolved.problems());
  }
  @Override public Map<Long,Selection> readByIds(String unit,Collection<Long> ids){return databaseRead(()->Map.copyOf(restore(storage.readByIds(unit,ids)).values()));}
  /** Reads may also exhaust the bounded monthly pool while other clients are adopting. */
  private <T> T databaseRead(java.util.function.Supplier<T> operation) {
    try { return operation.get(); }
    catch (MonthlyPriceProblem domainFailure) { throw domainFailure; }
    catch (RuntimeException readFailure) {
      if (concurrencyFailure(readFailure)) throw new MonthlyPriceProblem(Failure.TRANSIENT_CONCURRENCY_FAILURE,
          "Monthly price read temporarily unavailable; replan within the original budget", readFailure);
      throw readFailure;
    }
  }
  private Restored restore(List<MonthlyPriceStorage.StoredPrice> roots) {
    var rows=new LinkedHashMap<Long,MonthlyPriceStorage.StoredPrice>();roots.forEach(row->rows.put(row.id(),row));
    var metadata=new HashMap<Long,FirstUse>();var pending=new ArrayList<>(roots);
    while(!pending.isEmpty()) {
      var missing=new LinkedHashMap<String,Set<Long>>();
      for(var row:pending) {
        var first=codec.decode(codec.wrap(row.firstUseJson()),FirstUse.class);
        if(first.schemaVersion()!=2)throw failure(Failure.PERSISTENCE_INCOMPLETE,"Unsupported first-use evidence");
        metadata.put(row.id(),first);
        for(var child:first.dependencies())if(!rows.containsKey(child.monthlyPriceId()))
          missing.computeIfAbsent(row.family().businessUnitType(),ignored->new LinkedHashSet<>()).add(child.monthlyPriceId());
      }
      pending.clear();
      for(var group:missing.entrySet()) {
        var loaded=storage.readByIds(group.getKey(),group.getValue());
        if(loaded.size()!=group.getValue().size())throw failure(Failure.PERSISTENCE_INCOMPLETE,"Missing saved price dependency");
        for(var row:loaded)if(rows.putIfAbsent(row.id(),row)==null)pending.add(row);
      }
    }
    var result=new LinkedHashMap<Long,Selection>();
    var families=new LinkedHashMap<MonthlyPriceFamilyKey,List<MonthlyPriceStorage.StoredPrice>>();
    for(var row:rows.values()) {
      var dependencies=new LinkedHashSet<MonthlyPriceRequest>();
      for(var child:metadata.get(row.id()).dependencies()) {
        var saved=rows.get(child.monthlyPriceId());var request=child.request();
        if(saved==null || saved.id()==row.id() || !saved.family().equals(request.family())
            || !saved.applicability().matches(request) || !row.family().businessUnitType().equals(request.family().businessUnitType())
            || !row.family().month().equals(request.family().month()))
          throw failure(Failure.PERSISTENCE_INCOMPLETE,"Saved dependency identity or scope differs");
        dependencies.add(request);
      }
      families.computeIfAbsent(row.family(),ignored->new ArrayList<>()).add(row);
      result.put(row.id(),new Selection(row.id(),row.applicability(),
          new PriceFact(row.pricingType(),row.unitPrice(),row.householdRate(),row.evidence(),dependencies)));
    }
    return new Restored(Map.copyOf(result),families);
  }
  private MonthlyPriceStorage.StoredPrice matching(MonthlyPriceRequest request,Collection<MonthlyPriceStorage.StoredPrice> rows){
    MonthlyPriceStorage.StoredPrice found=null;
    for(var row:rows)if(row.family().equals(request.family()) && row.applicability().matches(request)){if(found!=null && found.id()!=row.id())throw failure(Failure.PERSISTENCE_INCOMPLETE,"Overlapping saved monthly prices");found=row;}
    return found;
  }
  @Override public AdoptionResult adopt(Context context,MonthlyAdoptionPlan planned,java.time.Duration remainingBudget) {
    long started=System.nanoTime();Objects.requireNonNull(remainingBudget);Objects.requireNonNull(planned);
    scope(context,planned.selections().keySet());
    scope(context,planned.ordinaryScopeRequests());
    var budget=remainingBudget.compareTo(MonthlyAdoptionRetryPolicy.OPERATION_BUDGET)>0
        ?MonthlyAdoptionRetryPolicy.OPERATION_BUDGET:remainingBudget;
    if(planned.selections().isEmpty() && planned.ordinaryScopeRequests().isEmpty())return result(context,planned);
    var committing=new java.util.concurrent.atomic.AtomicBoolean();
    try {
      return Objects.requireNonNull(transaction(remaining(budget,started)).execute(status->{
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization(){
              @Override public void beforeCommit(boolean readOnly){committing.set(true);}
            });
        return persist(context,planned);
      }));
    } catch(MonthlyPriceProblem expected) {
      throw expected;
    } catch(DataAccessException | org.springframework.transaction.TransactionException error) {
      if(!committing.get() && concurrencyFailure(error))
        throw new MonthlyPriceProblem(Failure.TRANSIENT_CONCURRENCY_FAILURE,"Monthly price lock conflict; transaction rolled back",error);
      // A lost commit response is recovered from immutable prices, without a separate receipt table.
      try {
        var read=transaction(remaining(budget,started));
        var saved=read.execute(status->{lockAndCheckScope(planned);return lookup(planned.selections().keySet());});
        if(saved!=null && saved.missing().isEmpty()) {
          var actual=new MonthlyAdoptionPlan(saved.values(),planned.uses(),planned.suppressedPaths(),planned.ordinaryScopeRequests());
          if(MonthlyAdoptionPlanConsistency.verify(planned,actual).outcome()==MonthlyAdoptionPlanConsistency.Outcome.CONSISTENT)
            return result(context,actual);
        }
      } catch(RuntimeException recovery) {error.addSuppressed(recovery);}
      throw new MonthlyPriceProblem(committing.get()?Failure.COMMIT_OUTCOME_UNKNOWN:Failure.PERSISTENCE_INCOMPLETE,
          "Monthly price transaction could not be confirmed; retry reads saved prices first",error);
    }
  }

  private AdoptionResult persist(Context context,MonthlyAdoptionPlan planned) {
    lockAndCheckScope(planned);
    var existing=lookup(planned.selections().keySet());var resolved=new LinkedHashMap<MonthlyPriceRequest,Selection>();
    var rows=new ArrayList<>(storage.readFamilies(planned.selections().keySet().stream().map(MonthlyPriceRequest::family).toList()));
    for(var request:planned.selections().keySet())save(request,context,planned,existing.values(),resolved,rows,new HashSet<>());
    var actual=new MonthlyAdoptionPlan(resolved,planned.uses(),planned.suppressedPaths(),planned.ordinaryScopeRequests());
    var checked=MonthlyAdoptionPlanConsistency.verify(planned,actual);
    if(checked.outcome()!=MonthlyAdoptionPlanConsistency.Outcome.CONSISTENT)
      throw failure(checked.outcome()==MonthlyAdoptionPlanConsistency.Outcome.REPLAN_REQUIRED
          ?Failure.REPLAN_REQUIRED:Failure.INVALID_PLAN,checked.conflicts().toString());
    return result(context,actual);
  }

  private void lockAndCheckScope(MonthlyAdoptionPlan planned) {
    var observed=new LinkedHashSet<>(planned.selections().keySet());
    observed.addAll(planned.ordinaryScopeRequests());
    storage.lockFamilies(observed.stream().map(MonthlyPriceRequest::family).toList());
    var scopeWinners=lookup(planned.ordinaryScopeRequests());
    for(var request:planned.ordinaryScopeRequests()) {
      var winner=scopeWinners.values().get(request);
      if(winner!=null && winner.fact().type()==PricingType.HOUSEHOLD_INQUIRY)
        throw failure(Failure.REPLAN_REQUIRED,"An ordinary BOM node was adopted as household; rebuild the scope");
    }
  }

  private Selection save(MonthlyPriceRequest request,Context context,MonthlyAdoptionPlan planned,
      Map<MonthlyPriceRequest,Selection> existing,Map<MonthlyPriceRequest,Selection> resolved,
      List<MonthlyPriceStorage.StoredPrice> rows,Set<MonthlyPriceRequest> active) {
    if(resolved.containsKey(request))return resolved.get(request);
    var saved=existing.get(request);
    if(saved==null) {
      var staged=matching(request,rows);
      if(staged!=null)saved=resolved.values().stream().filter(value->value.monthlyPriceId().equals(staged.id())).findFirst().orElse(null);
    }
    if(saved!=null){
      resolved.put(request,saved);
      // A NORMAL winner can introduce saved dependencies absent from a HOUSEHOLD candidate.
      // Carry that complete fact graph into comparison so the conflict triggers a full replan.
      for(var dependency:saved.fact().dependencies()) {
        if(!resolved.containsKey(dependency) && !existing.containsKey(dependency))
          throw failure(Failure.PERSISTENCE_INCOMPLETE,"Saved price dependency was not restored");
        save(dependency,context,planned,existing,resolved,rows,active);
      }
      return saved;
    }
    if(!active.add(request))throw failure(Failure.INVALID_PLAN,"Cyclic price dependency");
    var proposed=planned.selections().get(request);
    if(proposed==null)throw failure(Failure.INVALID_PLAN,"Price dependency absent from plan");
    var dependencies=new ArrayList<Dependency>();
    for(var child:proposed.fact().dependencies())
      dependencies.add(new Dependency(child,save(child,context,planned,existing,resolved,rows,active).monthlyPriceId()));
    active.remove(request);
    var coverage=Optional.of(proposed.applicability());
    for(var occupied:rows)if(occupied.family().equals(request.family()))coverage=coverage.flatMap(value->value.subtract(occupied.applicability()));
    if(coverage.isEmpty() || !coverage.get().matches(request) || !coverage.get().equals(proposed.applicability()))
      throw failure(Failure.REPLAN_REQUIRED,"Candidate coverage changed before commit");
    var first=new FirstUse(2,context,dependencies,reviews(context,Map.of(request,proposed)));
    var row=storage.insertPrice(request.family(),proposed.applicability(),proposed.fact(),householdSourceId(proposed),
        codec.unwrap(codec.encode(first)),context.actor());
    rows.add(row);
    saved=new Selection(row.id(),row.applicability(),proposed.fact());resolved.put(request,saved);
    return saved;
  }

  private AdoptionResult result(Context context,MonthlyAdoptionPlan plan) {
    return new AdoptionResult(plan,reviews(context,plan.selections()));
  }
  private List<com.sanhua.marketingcost.service.pricing.SupplierPriceReview> reviews(Context context,Map<MonthlyPriceRequest,Selection> selections) {
    var evidence=new HashMap<ConsumerPrice,Set<String>>();
    selections.forEach((request,selection)->evidence.computeIfAbsent(
        new ConsumerPrice(request.priceOrgCode(),request.family().materialCode()),ignored->new HashSet<>()).add(selection.fact().evidence().sha256()));
    return context.supplierReviews().successfulReviews().stream().filter(binding->{
      var scope=binding.review().scope();
      return Objects.equals(scope.businessUnitType(),context.businessUnitType()) && Objects.equals(scope.oaNo(),context.oaNo())
          && Objects.equals(scope.oaFormItemId(),context.oaFormItemId()) && Objects.equals(scope.periodMonth(),context.month().toString())
          && Objects.equals(scope.pricingDate(),context.priceAsOfTime().toLocalDate())
          && evidence.getOrDefault(new ConsumerPrice(scope.orgCode(),scope.materialCode()),Set.of()).contains(binding.evidenceSha256());
    }).map(com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext.SuccessfulReview::review).distinct().toList();
  }
  private Long householdSourceId(Selection selection){
    if(selection.fact().type()!=PricingType.HOUSEHOLD_INQUIRY)return null;
    try{var field=new com.fasterxml.jackson.databind.ObjectMapper().readTree(selection.fact().evidence().json()).path("householdSourceId");if(!field.isIntegralNumber() || !field.canConvertToLong() || field.longValue()<=0)throw failure(Failure.EVIDENCE_UNAVAILABLE,"Household evidence lacks its immutable source identity");return field.longValue();}
    catch(java.io.IOException invalid){throw new MonthlyPriceProblem(Failure.EVIDENCE_UNAVAILABLE,"Household evidence cannot be read",invalid);}
  }
  private static void scope(Context context,Collection<MonthlyPriceRequest> requests){Objects.requireNonNull(context);for(var request:requests)if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw failure(Failure.INVALID_INPUT,"Requests do not belong to the adoption business unit and month");}
  private static MonthlyPriceProblem failure(Failure reason,String message){return new MonthlyPriceProblem(reason,message);}
  private TransactionTemplate transaction(java.time.Duration remaining){
    Objects.requireNonNull(remaining);long seconds=remaining.minus(connectionAcquisitionBudget).getSeconds();
    if(seconds<1)throw failure(Failure.TRANSIENT_CONCURRENCY_FAILURE,"Insufficient budget for another database transaction");
    var result=new TransactionTemplate(transactions);result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);result.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);result.setTimeout((int)Math.min(3,seconds));return result;
  }
  private static java.time.Duration remaining(java.time.Duration budget,long started){return budget.minus(java.time.Duration.ofNanos(System.nanoTime()-started));}
  private static boolean concurrencyFailure(Throwable failure){
    for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
      if(cause instanceof java.sql.SQLTransientConnectionException || cause instanceof java.sql.SQLTimeoutException || cause instanceof org.springframework.dao.QueryTimeoutException || cause instanceof org.springframework.transaction.TransactionTimedOutException)return true;
      if(cause instanceof java.sql.SQLException sql && (sql.getErrorCode()==1213 || sql.getErrorCode()==1205 || sql.getSQLState()!=null && sql.getSQLState().startsWith("08")))return true;
    }
    return false;
  }
}
