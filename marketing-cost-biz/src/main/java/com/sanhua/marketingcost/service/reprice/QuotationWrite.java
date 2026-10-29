package com.sanhua.marketingcost.service.reprice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Ordinary quotation boundary; source maintenance and saved-history reads do not use this gate. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QuotationWrite {
  /** Long preparation entry points check on entry; their publication transactions hold the fence. */
  boolean holdUntilCompletion() default true;
}
