package com.mio.ai.memory.working;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class WorkingMemoryTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private HashOperations<String, Object, Object> hashOps;
    private SetOperations<String, String> setOps;
    private WorkingMemory workingMemory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        hashOps = mock(HashOperations.class);
        setOps = mock(SetOperations.class);

        given(redisTemplate.opsForList()).willReturn(listOps);
        given(redisTemplate.opsForHash()).willReturn(hashOps);
        given(redisTemplate.opsForSet()).willReturn(setOps);

        workingMemory = new WorkingMemory(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("appendMessage는 LPUSH 후 LTRIM(0, 19)을 실행한다")
    void appendMessage_calls_lpush_and_ltrim() {
        UUID sessionId = UUID.randomUUID();
        given(listOps.leftPush(anyString(), anyString())).willReturn(1L);

        workingMemory.appendMessage(sessionId, "user", "안녕하세요");

        verify(listOps).leftPush(contains(sessionId.toString()), anyString());
        verify(listOps).trim(contains(sessionId.toString()), eq(0L), eq(19L));
        verify(redisTemplate).expire(contains(sessionId.toString()), eq(Duration.ofMinutes(90)));
    }

    @Test
    @DisplayName("getRecentMessages는 LRANGE 결과를 오래된 순서로 반환한다")
    void getRecentMessages_reverses_order_to_chronological() throws Exception {
        UUID sessionId = UUID.randomUUID();
        WorkingMessage oldest = new WorkingMessage("user", "첫 메시지", 1000L);
        WorkingMessage newest = new WorkingMessage("assistant", "최신 응답", 2000L);

        // Redis는 LPUSH 최신 우선 → 리스트 순서는 newest, oldest
        List<String> redisOrder = List.of(
                objectMapper.writeValueAsString(newest),
                objectMapper.writeValueAsString(oldest)
        );
        given(listOps.range(anyString(), eq(0L), eq(19L))).willReturn(redisOrder);

        List<WorkingMessage> result = workingMemory.getRecentMessages(sessionId);

        assertThat(result).hasSize(2);
        // 반환 순서는 오래된 것부터 (chronological)
        assertThat(result.get(0).content()).isEqualTo("첫 메시지");
        assertThat(result.get(1).content()).isEqualTo("최신 응답");
    }

    @Test
    @DisplayName("getRecentMessages는 빈 리스트 반환 시 emptyList를 반환한다")
    void getRecentMessages_empty_redis_returns_empty_list() {
        UUID sessionId = UUID.randomUUID();
        given(listOps.range(anyString(), anyLong(), anyLong())).willReturn(List.of());

        List<WorkingMessage> result = workingMemory.getRecentMessages(sessionId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getSocraticQuestionCount는 Redis hash에서 값을 읽는다")
    void getSocraticQuestionCount_reads_from_hash() {
        UUID sessionId = UUID.randomUUID();
        given(hashOps.get(anyString(), eq("socratic_count"))).willReturn("2");

        int count = workingMemory.getSocraticQuestionCount(sessionId);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("null 값이면 0을 반환한다")
    void getSocraticQuestionCount_null_returns_zero() {
        UUID sessionId = UUID.randomUUID();
        given(hashOps.get(anyString(), anyString())).willReturn(null);

        int count = workingMemory.getSocraticQuestionCount(sessionId);

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("incrementSocraticQuestionCount는 HINCRBY와 expire를 실행한다")
    void incrementSocraticQuestionCount_increments_and_sets_ttl() {
        UUID sessionId = UUID.randomUUID();
        given(hashOps.increment(anyString(), anyString(), anyLong())).willReturn(1L);

        workingMemory.incrementSocraticQuestionCount(sessionId);

        verify(hashOps).increment(contains(sessionId.toString()), eq("socratic_count"), eq(1L));
        verify(redisTemplate).expire(contains(sessionId.toString()), eq(Duration.ofMinutes(90)));
    }

    @Test
    @DisplayName("getSessionDelta는 모든 필드를 합산하여 반환한다")
    void getSessionDelta_aggregates_all_fields() {
        UUID sessionId = UUID.randomUUID();

        given(hashOps.entries(contains("working"))).willReturn(Map.of(
                "socratic_count", "1",
                "risk_accumulation", "3",
                "distortion:catastrophizing", "2",
                "distortion:all_or_nothing", "1"
        ));
        given(setOps.members(contains("beliefs"))).willReturn(Set.of("belief-uuid-1"));
        given(setOps.members(contains("triggers"))).willReturn(Set.of("work_pressure", "family"));

        SessionDelta delta = workingMemory.getSessionDelta(sessionId);

        assertThat(delta.socraticQuestionsUsed()).isEqualTo(1);
        assertThat(delta.sessionRiskAccumulation()).isEqualTo(3);
        assertThat(delta.distortionCount("catastrophizing")).isEqualTo(2);
        assertThat(delta.distortionCount("all_or_nothing")).isEqualTo(1);
        assertThat(delta.activatedBeliefIds()).containsExactly("belief-uuid-1");
        assertThat(delta.currentSessionTriggers()).containsExactlyInAnyOrder("work_pressure", "family");
    }

    @Test
    @DisplayName("socraticLimitReached는 2회 이상 시 true다")
    void socraticLimitReached_returns_true_at_two() {
        SessionDelta delta2 = new SessionDelta(2, Map.of(), 0, Set.of(), Set.of());
        SessionDelta delta1 = new SessionDelta(1, Map.of(), 0, Set.of(), Set.of());

        assertThat(delta2.socraticLimitReached()).isTrue();
        assertThat(delta1.socraticLimitReached()).isFalse();
    }

    @Test
    @DisplayName("clear는 4개 키를 모두 삭제한다")
    void clear_deletes_all_four_keys() {
        UUID sessionId = UUID.randomUUID();

        workingMemory.clear(sessionId);

        verify(redisTemplate).delete(argThat((List<String> keys) ->
                keys.size() == 4 && keys.stream().allMatch(k -> k.contains(sessionId.toString()))
        ));
    }

    @Test
    @DisplayName("addSessionTrigger는 SADD와 expire를 실행한다")
    void addSessionTrigger_adds_to_set_and_sets_ttl() {
        UUID sessionId = UUID.randomUUID();
        given(setOps.add(anyString(), any(String[].class))).willReturn(1L);

        workingMemory.addSessionTrigger(sessionId, "work_pressure");

        verify(setOps).add(contains(sessionId.toString()), eq("work_pressure"));
        verify(redisTemplate).expire(contains(sessionId.toString()), eq(Duration.ofMinutes(90)));
    }
}
