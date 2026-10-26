package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.util.Set;

/** Read-only boundary between cost input revisions and saved quotation price evidence. */
public interface QuotationMonthlyPriceInputs {
  record Scope(String evidence, Set<String> unadoptedMaterials, Set<String> householdObservers,
      boolean preparationKnown) {
    public Scope {
      unadoptedMaterials = Set.copyOf(unadoptedMaterials);
      householdObservers = Set.copyOf(householdObservers);
      if (evidence == null) throw new IllegalArgumentException("Price revision evidence is required");
    }

    /** No saved preparation exists yet; source availability still participates in the revision. */
    public static Scope unprepared() { return new Scope("UNPREPARED", Set.of(), Set.of(), false); }
  }

  Scope read(OaForm form, OaFormItem item, String pricingMonth);
}
