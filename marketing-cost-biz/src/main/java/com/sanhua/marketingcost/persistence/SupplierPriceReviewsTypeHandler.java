package com.sanhua.marketingcost.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReview;
import java.sql.*;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 批次快照使用同一日期格式读写，不依赖全局 JSON 处理器的静态配置。 */
public class SupplierPriceReviewsTypeHandler extends BaseTypeHandler<List<SupplierPriceReview>> {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final TypeReference<List<SupplierPriceReview>> TYPE = new TypeReference<>() {};
  @Override public void setNonNullParameter(PreparedStatement statement, int i,
      List<SupplierPriceReview> value, JdbcType jdbcType) throws SQLException {
    try { statement.setString(i, JSON.writeValueAsString(value)); }
    catch (Exception e) { throw new SQLException("供货比率确认快照无法保存", e); }
  }
  @Override public List<SupplierPriceReview> getNullableResult(ResultSet r, String name) throws SQLException { return read(r.getString(name)); }
  @Override public List<SupplierPriceReview> getNullableResult(ResultSet r, int index) throws SQLException { return read(r.getString(index)); }
  @Override public List<SupplierPriceReview> getNullableResult(CallableStatement r, int index) throws SQLException { return read(r.getString(index)); }
  private List<SupplierPriceReview> read(String value) throws SQLException {
    if (value == null) return List.of();
    try { return JSON.readValue(value, TYPE); }
    catch (Exception e) { throw new SQLException("供货比率确认快照无法读取", e); }
  }
}
