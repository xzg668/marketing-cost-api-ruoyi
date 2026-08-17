package com.sanhua.marketingcost.service.collaboration;

/** 当前报价产品行允许展示的唯一下一步操作。 */
public enum QuoteItemCollaborationAction {
  ASSIGN_TECHNICIAN,
  START_BOM_SUPPLEMENT,
  START_PACKAGE_SUPPLEMENT,
  START_PRICE_SUPPLEMENT,
  LINK_EXISTING_TASK,
  APPLY_APPROVED_RESULT,
  VIEW_SUPPLEMENT,
  START_COSTING,
  CONTINUE_COSTING,
  VIEW_COSTING_RESULT,
  NONE;

  public boolean canStartCollaboration() {
    return this == START_BOM_SUPPLEMENT
        || this == START_PACKAGE_SUPPLEMENT
        || this == START_PRICE_SUPPLEMENT
        || this == LINK_EXISTING_TASK
        || this == APPLY_APPROVED_RESULT;
  }

  public boolean batchSelectable() {
    return this == ASSIGN_TECHNICIAN || canStartCollaboration();
  }
}
