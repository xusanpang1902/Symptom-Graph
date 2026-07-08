-- Upgrade an existing database created before Milestone 16.
-- Run this once if uploads fail with:
--   Unknown column 'review_status' in 'field list'
--
-- Fresh databases created from src/main/resources/db/schema.sql already include
-- these columns and do not need this script.

ALTER TABLE corpus_record
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED' COMMENT '人工校对状态' AFTER last_failed_at,
    ADD COLUMN reviewed_raw_content TEXT NULL COMMENT '人工修正后的评论原文' AFTER review_status,
    ADD COLUMN reviewed_context_target TEXT NULL COMMENT '人工修正后的上下文原文' AFTER reviewed_raw_content,
    ADD COLUMN reviewed_tags JSON NULL COMMENT '人工修正后的标签数组' AFTER reviewed_context_target,
    ADD COLUMN reviewed_at DATETIME NULL COMMENT '人工校对时间' AFTER reviewed_tags,
    ADD COLUMN review_note TEXT NULL COMMENT '人工校对备注' AFTER reviewed_at;

CREATE INDEX idx_review_status ON corpus_record (review_status);
