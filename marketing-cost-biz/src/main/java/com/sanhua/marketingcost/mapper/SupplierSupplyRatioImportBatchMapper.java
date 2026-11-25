package com.sanhua.marketingcost.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Imported version ownership also covers older files without before/after audit records. */
@Mapper
public interface SupplierSupplyRatioImportBatchMapper {
  record Batch(String batchNo, String fileName, String importedBy, LocalDateTime importedAt,
      long importedRows, long activeRows, boolean voided) {}

  String SCOPE = """
      FROM lp_supplier_supply_ratio r
      WHERE r.business_unit_type=#{unit} AND r.source_type='EXCEL' AND r.deleted=0
        AND r.source_batch_no IS NOT NULL AND TRIM(r.source_batch_no)<>''
      """;

  @Select("SELECT COUNT(DISTINCT r.source_batch_no) " + SCOPE)
  long count(@Param("unit") String unit);

  @Select("""
      SELECT r.source_batch_no batch_no, MAX(r.import_file_name) file_name,
        MAX(r.imported_by) imported_by, MAX(r.imported_at) imported_at,
        COUNT(*) imported_rows, SUM(r.is_active=1) active_rows,
        EXISTS(SELECT 1 FROM lp_business_change_log l
          JOIN lp_supplier_supply_ratio v ON v.id=l.biz_id
          WHERE l.biz_domain='SUPPLIER_SUPPLY_RATIO' AND l.biz_type='SUPPLIER_RELATION'
            AND l.change_source='FINANCE_VOID' AND l.request_id=r.source_batch_no
            AND v.business_unit_type=#{unit}) voided
      """ + SCOPE + """
      GROUP BY r.source_batch_no ORDER BY MAX(r.imported_at) DESC, MAX(r.id) DESC
      LIMIT #{size} OFFSET #{offset}
      """)
  List<Batch> list(@Param("unit") String unit, @Param("size") int size, @Param("offset") long offset);
}
