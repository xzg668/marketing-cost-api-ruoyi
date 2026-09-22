package com.sanhua.marketingcost.integration.oa;

import java.util.List;

final class OaQuotationValidationException extends RuntimeException {
  private final List<OaQuotationResponse.FieldError> errors;

  OaQuotationValidationException(String field, String message) {
    super(message);
    errors = List.of(new OaQuotationResponse.FieldError(field, message));
  }

  List<OaQuotationResponse.FieldError> errors() {
    return errors;
  }
}
