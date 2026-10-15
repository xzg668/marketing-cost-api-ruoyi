package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reserve the existing daily display number in a short transaction shared by API and workers. */
@Component
public class QuoteCostVersionSequence {
  private final JdbcTemplate jdbc;

  public QuoteCostVersionSequence(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @RetryOnConcurrency
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public long next(LocalDate day, String prefix) {
    long initial = 0;
    if (jdbc.queryForList(
            "SELECT sequence_day FROM lp_quote_cost_version_sequence WHERE sequence_day=?", day)
        .isEmpty()) {
      initial =
          jdbc.queryForObject(
              """
SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(version_no,'-',3),'-',-1) AS UNSIGNED)),0)
FROM lp_quote_cost_run_version WHERE version_no LIKE ?
""",
              Long.class,
              prefix + "%");
    }
    jdbc.update(
        """
        INSERT INTO lp_quote_cost_version_sequence(sequence_day,last_number) VALUES (?,?)
        ON DUPLICATE KEY UPDATE last_number=last_number+1
        """,
        day,
        initial + 1);
    // The reservation commits before costing finishes; rollback can leave a gap, never a duplicate.
    return jdbc.queryForObject(
        "SELECT last_number FROM lp_quote_cost_version_sequence WHERE sequence_day=?",
        Long.class,
        day);
  }
}
