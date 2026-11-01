ALTER TABLE expense ADD COLUMN source_expense_id BIGINT NULL;

CREATE INDEX idx_expense_source_date ON expense (source_expense_id, expense_date);
