ALTER TABLE "user"
    ADD CONSTRAINT ck_user_provider_fields
    CHECK (
        (provider = 'LOCAL' AND password IS NOT NULL)
        OR (provider IN ('GOOGLE', 'KAKAO') AND provider_id IS NOT NULL)
    );
