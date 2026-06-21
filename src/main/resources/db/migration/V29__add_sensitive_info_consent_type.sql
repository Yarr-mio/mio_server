-- Apple App Store Guideline 5.1.2(i) 대응: sensitive_info 동의 항목 추가
ALTER TABLE user_consents
    DROP CONSTRAINT user_consents_consent_type_check,
    ADD CONSTRAINT user_consents_consent_type_check
        CHECK (consent_type IN ('terms','privacy','age_verification','marketing','sensitive_info'));
