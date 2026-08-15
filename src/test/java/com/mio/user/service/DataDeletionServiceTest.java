package com.mio.user.service;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.domain.DeletionStatus;
import com.mio.user.repository.DataDeletionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataDeletionServiceTest {

    @Mock private DataDeletionRequestRepository deletionRequestRepository;
    @Mock private UserCachePurger cachePurger;
    @Mock private UserHardDeleteExecutor hardDeleteExecutor;

    private DataDeletionService service;

    @BeforeEach
    void setUp() {
        service = new DataDeletionService(
                deletionRequestRepository,
                cachePurger,
                hardDeleteExecutor
        );
    }

    @Test
    @DisplayName("DB 하드 삭제 트랜잭션 실패를 삼키지 않고 재시도 상태로 저장한다")
    void executeDeletion_databaseCommitFails_recordsRetryableFailure() {
        UUID requestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DataDeletionRequest request = request(requestId, userId);
        when(deletionRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        doThrow(new IllegalStateException("database unavailable"))
                .when(hardDeleteExecutor).deleteUser(userId);

        boolean completed = service.executeDeletion(requestId);

        assertThat(completed).isFalse();
        assertThat(request.getStatus()).isEqualTo(DeletionStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getLastError()).contains("database unavailable");
        verify(hardDeleteExecutor).deleteUser(userId);
    }

    @Test
    @DisplayName("독립 하드 삭제가 성공하면 작업 상태를 completed로 남긴다")
    void executeDeletion_databaseCommitSucceeds_recordsCompleted() {
        UUID requestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DataDeletionRequest request = request(requestId, userId);
        when(deletionRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        boolean completed = service.executeDeletion(requestId);

        assertThat(completed).isTrue();
        assertThat(request.getStatus()).isEqualTo(DeletionStatus.COMPLETED);
        assertThat(request.getDatabasePurgedAt()).isNotNull();
        verify(hardDeleteExecutor).deleteUser(userId);
    }

    @Test
    @DisplayName("탈퇴 접수 트랜잭션에서 Redis 사용자 캐시를 즉시 삭제한다")
    void requestDeletion_purgesCacheImmediately() {
        UUID userId = UUID.randomUUID();
        OffsetDateTime withdrawnAt = OffsetDateTime.now(ZoneOffset.UTC);
        DataDeletionRequest request = DataDeletionRequest.open(userId, withdrawnAt.plusDays(30));
        when(deletionRequestRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());
        when(deletionRequestRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenReturn(request);

        DataDeletionRequest result = service.requestDeletion(userId, withdrawnAt);

        verify(cachePurger).purge(userId);
        assertThat(result.getCachePurgedAt()).isNotNull();
    }

    private DataDeletionRequest request(UUID requestId, UUID userId) {
        DataDeletionRequest request = DataDeletionRequest.open(
                userId,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
        );
        ReflectionTestUtils.setField(request, "id", requestId);
        return request;
    }
}
