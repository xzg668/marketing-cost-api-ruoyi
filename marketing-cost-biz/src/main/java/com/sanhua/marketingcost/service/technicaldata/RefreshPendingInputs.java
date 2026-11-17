package com.sanhua.marketingcost.service.technicaldata;

import java.lang.annotation.*;

/** Explicit write-side invalidation of the saved pending projection. Never place on reads/previews. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RefreshPendingInputs {
  String[] modules();
  /** Old row is included when an edit renames or deletes the source's material. */
  String previousTable() default "";
  /** Global rules and month selection can affect more than the submitted material codes. */
  boolean wholeUnit() default false;
  /** Technical commands identify their shared source by task, not by the current browser's product. */
  int unitArgument() default -1;
  int taskArgument() default -1;
  int productArgument() default -1;
}
