package com.sanhua.marketingcost.service.technicaldata;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TechnicalDataAuxiliaryClassificationRepository {
  public record Classification(Long detailId, String contentFingerprint, String subjectCode, String subjectName) {}
  private final JdbcTemplate jdbc;
  public TechnicalDataAuxiliaryClassificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public List<Classification> find(Long versionId) {
    return jdbc.query("""
        SELECT detail_id,content_fingerprint,subject_code,subject_name
        FROM lp_quote_tech_aux_classification WHERE technical_version_id=? ORDER BY detail_id
        """, (rs, row) -> new Classification(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)), versionId);
  }
  public void save(Long versionId, Classification value, Long userId) {
    jdbc.update("""
        INSERT INTO lp_quote_tech_aux_classification
          (technical_version_id,detail_id,content_fingerprint,subject_code,subject_name,classified_by)
        VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE content_fingerprint=VALUES(content_fingerprint),
          subject_code=VALUES(subject_code),subject_name=VALUES(subject_name),classified_by=VALUES(classified_by),classified_at=NOW(6)
        """, versionId,value.detailId(),value.contentFingerprint(),value.subjectCode(),value.subjectName(),userId);
  }
}
