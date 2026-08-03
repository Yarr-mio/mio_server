package com.mio.user.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 소셜 로그인 이메일 (Apple은 최초 로그인 시에만 제공) */
    @Column(name = "email")
    private String email;

    @Column(name = "social_provider", nullable = false)
    private String socialProvider;

    @Column(name = "social_id", nullable = false)
    private String socialId;

    @Column(name = "nickname")
    private String nickname;

    /** 나이대: 10대 / 20대 / 30대 / 40대 / 50대+ */
    @Column(name = "age_range")
    private String ageRange;

    @Column(name = "gender")
    private String gender;

    /** 취준생/취업자 구분. 값 검증 없음 — gender/ageRange와 동일한 자유 문자열 (이슈 #282). */
    @Column(name = "employment_status")
    private String employmentStatus;

    /** 가입 시 초기 동의값 (이후 변경은 notification_settings 참조) */
    @Column(name = "notification_agree", nullable = false)
    @Builder.Default
    private boolean notificationAgree = true;

    /** 마케팅 활용 동의 (선택) */
    @Column(name = "marketing_agree", nullable = false)
    @Builder.Default
    private boolean marketingAgree = false;

    /** 개인정보 및 감정 데이터 활용 동의 */
    @Column(name = "privacy_consent", nullable = false)
    private boolean privacyConsent;

    /**
     * 회원가입 단계 상태머신
     * SOCIAL_AUTHENTICATED → CONSENT_AGREED → PROFILE_COMPLETED → ONBOARDING_COMPLETED → COMPLETED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "signup_step", nullable = false)
    @Builder.Default
    private SignupStep signupStep = SignupStep.SOCIAL_AUTHENTICATED;

    /** 온보딩 진행 단계: 0=미시작, 1=감정상태, 2=고민유형, 3=상담스타일 완료 */
    @Column(name = "onboarding_step", nullable = false)
    private int onboardingStep;

    /** 미선택 시 'mio' 자동 설정 */
    @Column(name = "preferred_character_id")
    @Builder.Default
    private String preferredCharacterId = "mio";

    @Column(name = "last_checkin_at")
    private OffsetDateTime lastCheckinAt;

    /** MVP: 항상 false, post-MVP 활성화 */
    @Column(name = "is_premium", nullable = false)
    private boolean isPremium;

    /** 만 14세 미만 여부 */
    @Column(name = "is_minor", nullable = false)
    private boolean isMinor;

    /** Admin 엔드포인트(/v1/admin/**) 접근 권한. DB에서 직접 플래그를 세운다 — 셀프서비스 승격 경로 없음 (이슈 #279). */
    @Column(name = "is_admin", nullable = false)
    @Builder.Default
    private boolean isAdmin = false;

    /** PENDING / ACTIVE / SUSPENDED / DELETED */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "signup_completed_at")
    private OffsetDateTime signupCompletedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public void updateOnboardingStep(int step) {
        if (step > this.onboardingStep) {
            this.onboardingStep = step;
        }
    }

    public void completeOnboarding(String characterId) {
        this.preferredCharacterId = characterId;
        this.onboardingStep = 4;
        this.signupStep = SignupStep.ONBOARDING_COMPLETED;
    }

    public void changeCharacter(String characterId) {
        this.preferredCharacterId = characterId;
    }

    public void agreeConsent(boolean privacyConsent, boolean marketingAgree) {
        this.privacyConsent = privacyConsent;
        this.marketingAgree = marketingAgree;
        this.signupStep = SignupStep.CONSENT_AGREED;
    }
    public void completeProfile(String nickname, String ageRange, String gender, String employmentStatus) {
        this.nickname = nickname;
        this.ageRange = ageRange;
        this.gender = gender;
        this.employmentStatus = employmentStatus;
        this.signupStep = SignupStep.PROFILE_COMPLETED;
    }

    public void activate() {
        this.status = "ACTIVE";
    }

    public void finalizeSignup() {
        this.signupStep = SignupStep.COMPLETED;
        this.status = "ACTIVE";
        this.signupCompletedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void updateProfile(String nickname, String ageRange, boolean ageRangePresent) {
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (ageRangePresent) {
            this.ageRange = ageRange;
        }
    }

    public void softDelete(String anonymizedSocialId) {
        this.socialId = anonymizedSocialId;
        this.nickname = "탈퇴한 사용자";
        this.email = null;
        this.status = "DELETED";
        this.deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
