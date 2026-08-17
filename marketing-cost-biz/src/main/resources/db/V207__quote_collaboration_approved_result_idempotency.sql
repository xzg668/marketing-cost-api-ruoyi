ALTER TABLE `lp_quote_collaboration_approved_result`
  ADD UNIQUE KEY `uk_approved_result_source`
    (`source_product_task_id`, `source_review_id`, `result_type`);
