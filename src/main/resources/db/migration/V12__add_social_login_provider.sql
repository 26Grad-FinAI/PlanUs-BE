ALTER TABLE "user" ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE "user" ADD COLUMN provider_id VARCHAR(255);
ALTER TABLE "user" ALTER COLUMN password DROP NOT NULL;

CREATE UNIQUE INDEX uq_user_provider_provider_id
    ON "user" (provider, provider_id)
    WHERE provider_id IS NOT NULL;
