package com.sanhua.marketingcost.service.collaboration;

public class CollaborationDomainException extends RuntimeException {

  private final CollaborationDomainErrorCode code;

  public CollaborationDomainException(CollaborationDomainErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public CollaborationDomainErrorCode code() {
    return code;
  }
}
