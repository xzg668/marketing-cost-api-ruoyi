package com.sanhua.marketingcost.dto.costing;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;

/** Preserve large v2 fees through MySQL JSON without changing historical numeric fingerprints. */
public final class ProductFeeAmountSerializer extends JsonSerializer<BigDecimal> {
  public interface VersionedFees {
    Integer encodingVersion();
  }

  @Override
  public void serialize(BigDecimal amount, JsonGenerator json, SerializerProvider provider)
      throws IOException {
    Object owner = json.getOutputContext().getCurrentValue();
    if (owner instanceof VersionedFees fees && Integer.valueOf(2).equals(fees.encodingVersion())
        && amount.stripTrailingZeros().precision() > 15) {
      json.writeString(amount.toPlainString());
    } else {
      json.writeNumber(amount);
    }
  }
}
