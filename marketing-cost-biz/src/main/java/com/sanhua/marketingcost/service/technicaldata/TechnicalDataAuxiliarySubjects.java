package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse.Subject;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 与 CMS 辅料发布使用同一份二级科目设置，包装辅料不属于本模块。 */
@Service
public class TechnicalDataAuxiliarySubjects {
  private final JdbcTemplate jdbc;
  public TechnicalDataAuxiliarySubjects(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public List<Subject> list(String businessUnit) {
    return jdbc.query("""
        SELECT DISTINCT TRIM(second_subject_code) AS code, TRIM(second_subject_name) AS name
        FROM cms_subject_setting_raw
        WHERE business_unit_type=? AND first_subject_name='辅助材料'
          AND NULLIF(TRIM(second_subject_code),'') IS NOT NULL
          AND NULLIF(TRIM(second_subject_name),'') IS NOT NULL
          AND TRIM(second_subject_name)<>'包装辅料'
        ORDER BY code,name
        """, (rs, index) -> new Subject(rs.getString("code"), rs.getString("name")), businessUnit);
  }
  public Subject require(List<Subject> subjects, String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("二级科目名称不能为空");
    var matches = subjects.stream().filter(s -> s.name().equals(name.trim())).toList();
    if (matches.isEmpty()) throw new IllegalArgumentException("二级科目无效：" + name);
    if (matches.size() != 1 || subjects.stream().filter(s -> s.code().equals(matches.getFirst().code())).count() != 1) {
      throw new IllegalArgumentException("二级科目名称或编码不唯一，请先核对 CMS 科目设置：" + name);
    }
    return matches.getFirst();
  }
}
