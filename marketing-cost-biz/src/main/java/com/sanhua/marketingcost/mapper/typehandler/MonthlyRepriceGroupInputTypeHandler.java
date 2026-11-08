package com.sanhua.marketingcost.mapper.typehandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Explicitly mapped only to compute-group input_document; callers always see the exact full text. */
public final class MonthlyRepriceGroupInputTypeHandler extends BaseTypeHandler<String> {
  private record Stored(int schemaVersion, int inputStorageVersion, String payload) {}
  private static final MonthlyPriceDocumentCodec CODEC = new MonthlyPriceDocumentCodec(new ObjectMapper());

  static String store(String input) {
    String envelope = CODEC.wrap(input);
    String compact = CODEC.compact(envelope);
    if (compact.equals(envelope)) return input;
    // Keep the schemaVersion=1 constraint; the separate marker describes storage, not input semantics.
    String stored = CODEC.unwrap(CODEC.encode(new Stored(1, 1, compact)));
    return stored.getBytes(StandardCharsets.UTF_8).length < input.getBytes(StandardCharsets.UTF_8).length ? stored : input;
  }

  static String restore(String stored) {
    if (stored == null) return null;
    var root = CODEC.decode(CODEC.wrap(stored), JsonNode.class);
    if (!root.has("inputStorageVersion")) return stored;
    var value = CODEC.decode(CODEC.wrap(stored), Stored.class);
    if (value.schemaVersion() != 1 || value.inputStorageVersion() != 1 || value.payload() == null)
      throw new IllegalArgumentException("不支持的共享计算输入存储格式");
    return CODEC.unwrap(value.payload());
  }

  @Override public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType type) throws SQLException {
    statement.setString(index, store(value));
  }
  @Override public String getNullableResult(ResultSet rows, String column) throws SQLException {
    return restore(rows.getString(column));
  }
  @Override public String getNullableResult(ResultSet rows, int column) throws SQLException {
    return restore(rows.getString(column));
  }
  @Override public String getNullableResult(CallableStatement statement, int column) throws SQLException {
    return restore(statement.getString(column));
  }
}
