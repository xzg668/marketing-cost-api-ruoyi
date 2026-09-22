package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceParameters;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Prices;
import java.io.IOException;
import java.math.BigDecimal;

/** 页面用十进制文本回显金额，避免 JavaScript 二进制数改写高精度输入；冻结存储格式保持不变。 */
public class TechnicalDataPriceContentSerializer extends JsonSerializer<Prices> {
  @Override
  public void serialize(Prices value, JsonGenerator generator, SerializerProvider provider) throws IOException {
    ObjectNode json = ((ObjectMapper) generator.getCodec()).valueToTree(value);
    if (value.items() != null) {
      for (int index = 0; index < value.items().size(); index++) {
        var item = value.items().get(index);
        var node = (ObjectNode) json.path("items").get(index);
        decimal(node, "unitPrice", item.unitPrice());
        parameters((ObjectNode) node.get("parameters"), item.parameters());
        if (item.reference() != null && item.reference().parameters() != null) {
          parameters((ObjectNode) node.path("reference").get("parameters"), item.reference().parameters());
        }
      }
    }
    generator.writeTree(json);
  }

  private void parameters(ObjectNode node, PriceParameters value) {
    if (node == null || value == null) return;
    decimal(node, "blankWeight", value.blankWeight());
    decimal(node, "netWeight", value.netWeight());
    decimal(node, "processFee", value.processFee());
    decimal(node, "agentFee", value.agentFee());
  }

  private void decimal(ObjectNode node, String field, BigDecimal value) {
    if (value != null) node.put(field, value.toPlainString());
  }
}
