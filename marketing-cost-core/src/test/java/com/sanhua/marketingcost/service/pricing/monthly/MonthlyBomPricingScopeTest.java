package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyBomPricingScopeTest {
  @Test void quoteOnlyRootHasNoPriceIdentityWhileItsChildrenStillAdoptTheirOwnPrices() {
    var original = List.of(new Node("P", "P", null, null, BigDecimal.ONE), node("P/A", "A", "P", "2"));
    var values = candidates(Map.of(request("A"), price(false, Set.of())));
    var scope = select(original, values);
    assertThat(scope.ordinary()).isEqualTo(original);
    var plan = adoption(scope, List.of(new Use("P/A", request("A"), new BigDecimal("2"))), values);
    assertThat(plan.selections()).containsOnlyKeys(request("A"));
    assertThat(plan.ordinaryScopeRequests()).containsExactly(request("A"));
    assertThatThrownBy(() -> new Node("P/A", "A", "P", null, BigDecimal.ONE))
        .isInstanceOf(MonthlyPriceProblem.class);
  }

  static MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10), "COMMERCIAL",
        code, null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  }
  static Node node(String path, String code, String parent, String qty) {
    return new Node(path, code, parent, request(code), new BigDecimal(qty));
  }
  static Selection price(boolean household, Set<MonthlyPriceRequest> deps) {
    var evidence = SourceEvidence.ofJson("{\"sourceKind\":\"test\"}");
    var fact = household ? PriceFact.household(new BigDecimal("20.9853"), new BigDecimal("0.085"), evidence)
        : PriceFact.normal(BigDecimal.TEN, evidence, deps);
    return new Selection(null, new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any()), fact);
  }
  static Candidates candidates(Map<MonthlyPriceRequest, Selection> prices) {
    var values = new HashMap<MonthlyPriceRequest, Candidate>();
    prices.forEach((request, selection) -> values.put(request, new Candidate(selection,null)));
    return new Candidates(values, Map.of());
  }
  static List<Node> tree() {
    return List.of(node("/P/", "P", null, "1"), node("/P/A/", "A", "P", "0.5"),
        node("/P/A/B/", "B", "A", "1.25"), node("/P/B/", "B", "P", "3"));
  }

  @Test void truncatesByPathAndKeepsIndependentDuplicateMaterialAndOriginalTree() {
    var original = tree();
    var values = candidates(Map.of(request("A"), price(true,Set.of()), request("B"),price(false,Set.of())));
    var scope = select(original,values);
    assertThat(scope.original()).isEqualTo(original);
    assertThat(scope.ordinary()).extracting(Node::path).containsExactly("/P/", "/P/B/");
    assertThat(scope.suppressedPaths()).containsExactly("/P/A/B/");
    var plan = adoption(scope, List.of(new Use("/P/B/",request("B"),new BigDecimal("3"))),values);
    assertThat(plan.selections()).containsOnlyKeys(request("A"),request("B"));
    assertThat(plan.uses()).extracting(Use::cumulativeQuantity).containsExactly(new BigDecimal("0.5"),new BigDecimal("3"));
  }

  @Test void firstHouseholdHitWinsEvenWhenDescendantHasInvalidSource() {
    var values = new Candidates(Map.of(request("A"),new Candidate(price(true,Set.of()),null)),
        Map.of(request("B"),new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,"missing scrap")));
    var nodes = tree().subList(0,3);
    var scope = select(nodes,values);
    assertThat(scope.household()).extracting(Use::path).containsExactly("/P/A/");
    assertThat(adoption(scope,List.of(),values).selections()).containsOnlyKeys(request("A"));
  }

  @Test void independentMissingBranchStillBlocks() {
    var probes = candidates(Map.of(request("A"),price(true,Set.of())));
    var scope = select(tree(), probes);
    var values = new Candidates(probes.values(),Map.of(request("B"),new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,"missing B")));
    assertThatThrownBy(() -> adoption(scope,List.of(new Use("/P/B/",request("B"),BigDecimal.ONE)),values))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class, error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING));
  }

  @Test void nestedAndMultipleHouseholdsAggregateFractionalQuantitiesBeforeRounding() {
    var nodes = List.of(node("P","P",null,"1"), node("P/a","A","P","0.333333333333333333"),
        node("P/a/b","B","A","0.5"),node("P/c","C","P","2"),
        node("P/c/a","A","C","2.166666666666666667"),node("P/b","B","P","0.125"));
    var values = candidates(Map.of(request("A"),price(true,Set.of()),request("B"),price(true,Set.of())));
    var scope = select(nodes,values);
    assertThat(scope.household()).extracting(Use::path).containsExactly("P/a","P/b","P/c/a");
    var plan = adoption(scope,List.of(),values);
    assertThat(quantities(plan).get(new PriceIdentity(request("A").family(),price(true,Set.of()).applicability())))
        .isEqualByComparingTo("2.5");
    assertThat(scope.suppressedPaths()).containsExactly("P/a/b");
  }

  @Test void zeroQuantityRowsNeverAdoptAndAllZeroBomIsAnEmptyPlan() {
    var nodes = List.of(node("P","P",null,"0"),node("P/a","A","P","0"));
    var values = candidates(Map.of(request("P"),price(true,Set.of()),request("A"),price(true,Set.of())));
    var scope = select(nodes,values);
    assertThat(scope.household()).isEmpty();
    assertThat(scope.ordinary()).isEmpty();
    assertThat(adoption(scope,List.of(),values).selections()).isEmpty();
  }

  @Test void savedNormalTypeDoesNotCutItsChildren() {
    var scope = select(tree(), candidates(Map.of(request("A"),price(false,Set.of()))));
    assertThat(scope.ordinary()).hasSize(4);
    assertThat(scope.household()).isEmpty();
    assertThat(scope.suppressedPaths()).isEmpty();
  }

  @Test void dependencyClosureExcludesUnrelatedAndSuppressedCandidates() {
    var linked = request("B"); var scrap = request("SCRAP");
    var values = candidates(Map.of(request("A"),price(true,Set.of()),linked,price(false,Set.of(scrap)),
        scrap,price(false,Set.of()),request("UNUSED"),price(false,Set.of())));
    var plan = adoption(select(tree(),values),List.of(new Use("/P/B/",linked,BigDecimal.ONE)),values);
    assertThat(plan.selections()).containsOnlyKeys(request("A"),linked,scrap);
    assertThat(plan.uses()).extracting(Use::request).doesNotContain(scrap);
  }

  @Test void ordinaryUseCannotReintroduceCoveredPathOrItsSyntheticScrapRow() {
    var values = candidates(Map.of(request("A"),price(true,Set.of()),request("B"),price(false,Set.of())));
    var scope = select(tree(),values);
    for (String path : List.of("/P/A/B/", "P/A/__SCRAP__/B", "P/A")) {
      assertThatThrownBy(() -> adoption(scope,List.of(new Use(path,request("B"),BigDecimal.ONE)),values))
          .isInstanceOf(MonthlyPriceProblem.class);
    }
  }

  @Test void concurrentTypeChangeRequiresReplanningInBothDirections() {
    var house = candidates(Map.of(request("A"),price(true,Set.of())));
    var normal = candidates(Map.of(request("A"),price(false,Set.of())));
    assertThatThrownBy(() -> adoption(select(tree(),house),List.of(),normal))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,e -> assertThat(e.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED));
    assertThatThrownBy(() -> adoption(select(tree(),normal),List.of(new Use("/P/A/",request("A"),BigDecimal.ONE)),house))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,e -> assertThat(e.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED));
  }

  @Test void duplicateAndBrokenPathsBelowAHouseholdCannotBeMasked() {
    var values = candidates(Map.of(request("A"),price(true,Set.of())));
    var duplicate = new ArrayList<>(tree()); duplicate.add(node("P/A/B","B","A","1"));
    assertThatThrownBy(() -> select(duplicate,values)).isInstanceOf(MonthlyPriceProblem.class);
    var broken = List.of(tree().get(0), tree().get(1), node("/P/A/B/","B","WRONG","1"));
    assertThatThrownBy(() -> select(broken,values)).isInstanceOf(MonthlyPriceProblem.class);
    assertThatThrownBy(() -> select(List.of(tree().get(0),node("P/A/missing/B","B","A","1")),values))
        .isInstanceOf(MonthlyPriceProblem.class);
  }

  @Test void rejectsWrongMaterialIdentityAndIllegalQuantities() {
    assertThatThrownBy(() -> new Node("P/A","A","P",request("WRONG"),BigDecimal.ONE)).isInstanceOf(MonthlyPriceProblem.class);
    assertThatThrownBy(() -> node("P/A","A","P","-0.1")).isInstanceOf(MonthlyPriceProblem.class);
    assertThatThrownBy(() -> new Node("P/A","A","P",request("A"),null)).isInstanceOf(MonthlyPriceProblem.class);
    assertThatThrownBy(() -> validate(List.of(node("P","P",null,"0"),node("P/A","A","P","1"))))
        .isInstanceOf(MonthlyPriceProblem.class);
  }

  @Test void pathSegmentsDoNotSuppressSiblingPrefixes() {
    var nodes = List.of(node("P","P",null,"1"),node("P/1","A","P","1"),node("P/10","B","P","2"));
    assertThat(select(nodes,candidates(Map.of(request("A"),price(true,Set.of())))).ordinary())
        .extracting(Node::path).containsExactly("P","P/10");
  }

  @Test void rejectsInvalidPathsAndMultipleRoots() {
    for (String path : List.of("/", "P//A", "P/../A", "P/./A", "P/ /A")) {
      assertThatThrownBy(() -> node(path,"A","P","1")).isInstanceOf(MonthlyPriceProblem.class);
    }
    assertThatThrownBy(() -> validate(List.of(node("P","P",null,"1"),node("Q","Q",null,"1"))))
        .isInstanceOf(MonthlyPriceProblem.class);
  }

  @Test void missingDependencyIsExplicitAndNeverSilentlyOmitted() {
    var values = candidates(Map.of(request("B"),price(false,Set.of(request("SCRAP")))));
    assertThatThrownBy(() -> adoption(select(tree(),values),List.of(new Use("P/B",request("B"),BigDecimal.ONE)),values))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,e -> assertThat(e.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.PERSISTENCE_INCOMPLETE));
  }

  @Test void aggregatesSharedConsumerAliasesButKeepsEachUse() {
    var left = request("B");
    var right = new MonthlyPriceRequest(left.family(),"220",left.conditions(),BigDecimal.ONE);
    var selected = price(false,Set.of());
    var plan = new MonthlyAdoptionPlan(Map.of(left,selected,right,selected),
        List.of(new Use("P/left",left,new BigDecimal("0.5")),new Use("P/right",right,new BigDecimal("2"))),Set.of());
    assertThat(quantities(plan)).hasSize(1);
    assertThat(quantities(plan).values().iterator().next()).isEqualByComparingTo("2.5");
    assertThat(plan.uses()).hasSize(2);
  }

  @Test void handlesDeepPathsIterativelyAndPreservesAllExplanations() {
    var nodes = new ArrayList<Node>(); nodes.add(node("P","P",null,"1"));
    String path = "P"; String parent = "P";
    for (int i=0;i<1000;i++) { path += "/" + i; String code="M"+i; nodes.add(node(path,code,parent,"0.125"));parent=code; }
    var scope = select(nodes,candidates(Map.of(request("M0"),price(true,Set.of()))));
    assertThat(scope.original()).hasSize(1001);
    assertThat(scope.household()).hasSize(1);
    assertThat(scope.suppressedPaths()).hasSize(999);
  }

  @Test void observesUnpricedOrdinaryAncestorsWithoutAdoptingThem() {
    var values = candidates(Map.of(request("B"),price(false,Set.of())));
    var plan = adoption(select(tree(),values),List.of(new Use("P/A/B",request("B"),BigDecimal.ONE)),values);
    assertThat(plan.selections()).containsOnlyKeys(request("B"));
    assertThat(plan.ordinaryScopeRequests()).containsExactlyInAnyOrder(request("P"),request("A"),request("B"));
  }

  @Test void scopeGuardsAreImmutableAndMustSurviveEvenAnEmptyPlan() {
    var guards = new HashSet<>(Set.of(request("P")));
    var observed = new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of(),guards);
    guards.clear();
    assertThat(observed.ordinaryScopeRequests()).containsExactly(request("P"));
    var lost = new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of());
    assertThat(MonthlyAdoptionPlanConsistency.verify(observed,lost).outcome())
        .isEqualTo(MonthlyAdoptionPlanConsistency.Outcome.REPLAN_REQUIRED);
    assertThat(MonthlyAdoptionPlanConsistency.verify(observed,observed).outcome())
        .isEqualTo(MonthlyAdoptionPlanConsistency.Outcome.CONSISTENT);
  }

  @Test void householdCandidateCannotContradictAnOrdinaryGuardIncludingAnotherConsumerAlias() {
    var left = request("A");
    var right = new MonthlyPriceRequest(left.family(),"220",left.conditions(),BigDecimal.ONE);
    for (var guard : List.of(left,right)) {
      var plan = new MonthlyAdoptionPlan(Map.of(left,price(true,Set.of())),
          List.of(new Use("P/A",left,BigDecimal.ONE)),Set.of(),Set.of(guard));
      var selected = price(true,Set.of());
      var saved = new MonthlyAdoptionPlan(Map.of(left,new Selection(1L,selected.applicability(),selected.fact())),
          plan.uses(),Set.of(),Set.of(guard));
      var result = MonthlyAdoptionPlanConsistency.verify(plan,saved);
      assertThat(result.outcome()).isEqualTo(MonthlyAdoptionPlanConsistency.Outcome.INVALID_PLAN);
      assertThat(result.conflicts().get(guard)).contains(MonthlyAdoptionPlanConsistency.Reason.INVALID_INPUT);
    }
  }
}
