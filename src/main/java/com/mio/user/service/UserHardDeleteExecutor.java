package com.mio.user.service;

import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 사용자 DB 하드 삭제만 독립 트랜잭션으로 실행한다.
 *
 * <p>삭제 작업 상태와 같은 트랜잭션에서 {@code delete()}하면 실제 SQL/커밋 실패가 서비스
 * 메서드 반환 뒤 발생해 실패 횟수와 원인이 함께 롤백될 수 있다. 이 프록시가 커밋까지 끝낸
 * 뒤 반환하므로 호출자는 실패를 잡아 작업 상태 트랜잭션에 기록할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class UserHardDeleteExecutor {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteUser(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        userRepository.delete(user);
        // 지연된 DELETE와 FK cascade 실패를 이 트랜잭션 경계 안에서 확정한다.
        userRepository.flush();
    }
}
