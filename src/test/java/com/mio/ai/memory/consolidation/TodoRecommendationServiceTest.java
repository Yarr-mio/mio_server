package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.memory.episodic.InterventionOutcome;
import com.mio.ai.memory.episodic.InterventionOutcomeRepository;
import com.mio.ai.memory.ontology.BehaviorTemplate;
import com.mio.ai.memory.ontology.BehaviorTemplateRepository;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.session.domain.Session;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TodoRecommendationServiceTest {

    private BehaviorTemplateRepository templateRepository;
    private BehaviorTaskRepository behaviorTaskRepository;
    private UserMemoryPreferenceRepository memoryPreferenceRepository;
    private InterventionOutcomeRepository outcomeRepository;
    private TodoActionPersonalizer actionPersonalizer;
    private TodoRecommendationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        templateRepository = mock(BehaviorTemplateRepository.class);
        behaviorTaskRepository = mock(BehaviorTaskRepository.class);
        memoryPreferenceRepository = mock(UserMemoryPreferenceRepository.class);
        outcomeRepository = mock(InterventionOutcomeRepository.class);
        actionPersonalizer = mock(TodoActionPersonalizer.class);

        service = new TodoRecommendationService(
                templateRepository, behaviorTaskRepository, memoryPreferenceRepository,
                outcomeRepository, actionPersonalizer, new ObjectMapper());

        when(memoryPreferenceRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(outcomeRepository.findRecentByUserId(any())).thenReturn(List.of());
        // 개인화기는 기본적으로 입력 템플릿 문구를 그대로 반환하도록(폴백 경로) 스텁.
        when(actionPersonalizer.personalize(any(), any(), anyList()))
                .thenAnswer(inv -> ((List<BehaviorTemplate>) inv.getArgument(2)).stream()
                        .map(BehaviorTemplate::getActionTextKo).toList());
    }

    @Test
    @DisplayName("왜곡 코드에 적합한 템플릿이 카테고리별로 선택된다")
    void selectsTemplateMatchingDistortion() {
        var lowFit = template("bt_a", "인지_재구성", "일반 사고 기록", "thought_record",
                List.of(), List.of(), 2);
        var highFit = template("bt_b", "인지_재구성", "증거 점검", "evidence_check",
                List.of("catastrophizing"), List.of(), 2);
        when(templateRepository.findAll()).thenReturn(List.of(lowFit, highFit));

        service.generateForSession(user(), session(),
                new TodoRecommendationService.TodoGenerationInput(
                        List.of("catastrophizing"), "anxious", List.of("발표"), "요약"));

        List<BehaviorTask> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getInterventionKind()).isEqualTo("evidence_check");
    }

    @Test
    @DisplayName("disliked_patterns에 등록된 intervention_kind는 후보에서 제외된다")
    void excludesDislikedInterventionKinds() throws Exception {
        var disliked = template("bt_a", "행동_활성화", "산책", "walk", List.of(), List.of(), 1);
        var allowed = template("bt_b", "행동_활성화", "스트레칭", "stretch", List.of(), List.of(), 1);
        when(templateRepository.findAll()).thenReturn(List.of(disliked, allowed));

        var pref = mock(UserMemoryPreference.class);
        when(pref.getDislikedPatterns()).thenReturn(new ObjectMapper().writeValueAsString(List.of("walk")));
        when(memoryPreferenceRepository.findByUserId(any())).thenReturn(Optional.of(pref));

        service.generateForSession(user(), session(),
                new TodoRecommendationService.TodoGenerationInput(
                        List.of(), "calm", List.of(), "요약"));

        List<BehaviorTask> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getInterventionKind()).isEqualTo("stretch");
    }

    @Test
    @DisplayName("과거 감정 개선 성과가 있는 개입이 동일 조건에서 우선 선택된다")
    void boostsInterventionWithPositivePastOutcome() {
        var neutral = template("bt_a", "심리_안정", "호흡", "breathing", List.of(), List.of(), 1);
        var proven = template("bt_b", "심리_안정", "그라운딩", "grounding", List.of(), List.of(), 1);
        when(templateRepository.findAll()).thenReturn(List.of(neutral, proven));
        // grounding이 과거 두 번 감정 개선(positive) → 가점.
        var o1 = outcome("grounding", "positive");
        var o2 = outcome("grounding", "positive");
        when(outcomeRepository.findRecentByUserId(any())).thenReturn(List.of(o1, o2));

        service.generateForSession(user(), session(),
                new TodoRecommendationService.TodoGenerationInput(
                        List.of(), null, List.of(), "요약"));

        List<BehaviorTask> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getInterventionKind()).isEqualTo("grounding");
    }

    @Test
    @DisplayName("개인화기가 반환한 문구가 action_text로 저장된다")
    void usesPersonalizedActionText() {
        var t = template("bt_a", "심리_안정", "기본 호흡", "breathing", List.of(), List.of(), 1);
        when(templateRepository.findAll()).thenReturn(List.of(t));
        when(actionPersonalizer.personalize(any(), any(), anyList()))
                .thenReturn(List.of("발표 전 긴장을 풀기 위해 4박자 호흡 5분 하기"));

        service.generateForSession(user(), session(),
                new TodoRecommendationService.TodoGenerationInput(
                        List.of(), "anxious", List.of("발표"), "요약"));

        List<BehaviorTask> saved = captureSaved();
        assertThat(saved.get(0).getActionText()).isEqualTo("발표 전 긴장을 풀기 위해 4박자 호흡 5분 하기");
        // 임상 필드는 템플릿 값 유지 (LLM 값 아님).
        assertThat(saved.get(0).getCategory()).isEqualTo("심리_안정");
        assertThat(saved.get(0).getInterventionKind()).isEqualTo("breathing");
    }

    @Test
    @DisplayName("후보 템플릿이 없으면 아무것도 저장하지 않는다")
    void savesNothingWhenPoolEmpty() {
        when(templateRepository.findAll()).thenReturn(List.of());

        service.generateForSession(user(), session(),
                new TodoRecommendationService.TodoGenerationInput(List.of(), null, List.of(), "요약"));

        verify(behaviorTaskRepository, never()).saveAll(any());
    }

    // ── helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<BehaviorTask> captureSaved() {
        ArgumentCaptor<List<BehaviorTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(behaviorTaskRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private User user() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        return user;
    }

    private Session session() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        return session;
    }

    private BehaviorTemplate template(String code, String category, String actionText, String kind,
                                      List<String> fitsDistortions, List<String> fitsEmotions, int difficulty) {
        BehaviorTemplate t = newTemplate();
        ReflectionTestUtils.setField(t, "code", code);
        ReflectionTestUtils.setField(t, "category", category);
        ReflectionTestUtils.setField(t, "actionTextKo", actionText);
        ReflectionTestUtils.setField(t, "interventionKind", kind);
        ReflectionTestUtils.setField(t, "fitsDistortions", fitsDistortions);
        ReflectionTestUtils.setField(t, "fitsEmotions", fitsEmotions);
        ReflectionTestUtils.setField(t, "difficulty", difficulty);
        ReflectionTestUtils.setField(t, "estimatedMinutes", 5);
        return t;
    }

    private BehaviorTemplate newTemplate() {
        try {
            var ctor = BehaviorTemplate.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private InterventionOutcome outcome(String kind, String reaction) {
        InterventionOutcome o = mock(InterventionOutcome.class);
        when(o.getInterventionKind()).thenReturn(kind);
        when(o.getUserReaction()).thenReturn(reaction);
        return o;
    }
}
