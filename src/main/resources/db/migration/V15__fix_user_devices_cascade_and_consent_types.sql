-- user_devices: users 삭제 시 연관 행 자동 제거
ALTER TABLE user_devices
    DROP CONSTRAINT user_devices_user_id_fkey,
    ADD CONSTRAINT user_devices_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- user_consents: 동의 유형을 age_verification / marketing 으로 교체
ALTER TABLE user_consents
    DROP CONSTRAINT user_consents_consent_type_check,
    ADD CONSTRAINT user_consents_consent_type_check
        CHECK (consent_type IN ('terms','privacy','age_verification','marketing'));
