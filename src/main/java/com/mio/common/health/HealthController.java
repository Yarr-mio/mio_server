package com.mio.common.health;

import com.mio.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 메인 포트(8080)의 liveness 신호.
 *
 * <p>actuator 는 관리 포트로 분리돼 있어 8080 에서 도달할 수 없다. Nginx 는 8080 만 프록시하므로
 * 외부 모니터가 확인할 수 있는 경로가 하나는 필요하다. Nginx 설정에도 이미 {@code /health} 가
 * 헬스체크 경로로 잡혀 있는데, 지금까지 이 핸들러가 없어 401 을 반환하고 있었다.
 *
 * <p><b>이건 liveness 전용이다.</b> 프로세스가 요청을 받아 응답할 수 있다는 것만 뜻하며,
 * DB·Redis·외부 API 상태는 보지 않는다. 의존성까지 포함한 판정은 관리 포트의
 * {@code /actuator/health} 를 봐야 한다. 여기서 의존성을 확인하면 DB 순단마다 Nginx 헬스체크가
 * 실패해 트래픽이 끊기므로, 두 신호를 의도적으로 분리한다.
 */
@RestController
@Tag(name = "Health", description = "서비스 liveness 확인")
public class HealthController {

    @Operation(summary = "liveness 확인", description = "프로세스가 요청에 응답할 수 있는지만 확인한다. 의존성 상태는 포함하지 않는다.")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UP")));
    }
}
