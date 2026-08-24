"""VERA-MH ↔ Mio 어댑터.

VERA-MH 의 `EndpointLLM` (llm_clients/endpoint_llm.py) 이 기대하는 계약과 Mio 의 실제
API 는 세 군데가 다르다. 이 프로세스가 그 셋을 번역한다 — Mio 프로덕션 코드는 건드리지
않는다.

    항목        VERA-MH 가 기대            Mio 가 제공
    ─────────────────────────────────────────────────────────────────
    전송        POST → JSON 단발            SSE (text/event-stream)
    인증        X-API-Key 헤더              JWT Bearer
    대화 상태   응답의 conversation_id      sessionId (선행 세션 생성 필요)

두 엔드포인트만 흉내낸다:

    POST /api/start_conversation  → Mio 세션 생성 → conversation_id 반환
    POST /api/chat                → Mio 메시지 전송 → SSE 조립 → 본문 반환

SSE 조립 규칙 (src/main/java/com/mio/session/dto/SseEventDto.java):

    delta.chunk          누적한다
    delta.replace        누적분을 safe_response 로 <교체> 한다  ← 놓치면 OutputJudge 가
                         갈아치운 응답 대신 원본 위험 응답을 채점하게 된다
    crisis               fixed_response 가 최종 본문이다 (위기 고정 플로우)
    done                 종료. 메타(is_crisis_flagged 등)를 부수 기록한다

`done` 메타를 기록하는 이유: Mio 자신의 판정과 VERA-MH judge 의 판정을 나중에 맞대볼 수
있다. VERA-MH 를 쓰는 다른 팀은 만들 수 없는 대조군이다.

실행:
    python plans/vera-mh/mio_endpoint_adapter.py --user-id <UUID>

그리고 VERA-MH 의 .env 에:
    ENDPOINT_URL=http://127.0.0.1:8900/api/chat
    ENDPOINT_START_URL=http://127.0.0.1:8900/api/start_conversation
    ENDPOINT_API_KEY=local-smoke
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

log = logging.getLogger("mio-adapter")

# dev 토큰 만료는 900초다 (DevAuthController.EXPIRES_IN). 여유를 두고 갱신한다.
TOKEN_TTL_SECONDS = 900
TOKEN_REFRESH_MARGIN = 120
# SseEmitter 타임아웃이 60초다 (SessionController.sendMessage). 그보다 길게 잡아
# 서버가 먼저 끊게 한다 — 클라이언트가 먼저 끊으면 원인이 흐려진다.
READ_TIMEOUT_SECONDS = 90


class MioClient:
    """Mio 세션 API 클라이언트. 사용자 풀·토큰 갱신·SSE 조립을 담당한다.

    <h2>왜 사용자 풀인가</h2>

    Mio 는 사용자당 활성 세션을 1개로 제한한다 (``SESSION_ALREADY_ACTIVE``). 그리고
    메시지 레이트리밋 60건/60초도 사용자당이다 (``SessionService.MSG_RATE_LIMIT_MAX``).
    VERA-MH 는 대화 여러 건을 동시에 돌리므로, 사용자 하나로는 병렬도가 1 로 묶인다.
    그래서 온보딩된 사용자를 여럿 받아 대화마다 하나씩 <b>대여</b>하고, 대화가 끝나면
    세션을 닫고 반납한다.

    풀이 비면 기다린다 — 조용히 같은 사용자를 재사용하면 두 대화가 한 세션에 섞여
    들어가 두 대화의 채점이 모두 무의미해진다.
    """

    def __init__(self, base_url: str, user_ids: list[str], character_id: str) -> None:
        self._base = base_url.rstrip("/")
        self._character_id = character_id
        # 사용자별 토큰 캐시. 만료가 900초라 대화 중에도 갱신된다.
        self._tokens: dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()
        # 대여 가능한 사용자. Semaphore 가 아니라 큐를 쓰는 이유는 "누가 대여 중인가" 를
        # 알아야 반납 시 세션을 닫을 수 있기 때문이다.
        self._available: list[str] = list(user_ids)
        self._pool_cv = threading.Condition()
        # sessionId → userId. 반납과 토큰 조회에 쓴다.
        self._session_owner: dict[str, str] = {}
        # VERA-MH 의 conversation_id → Mio sessionId.
        #
        # LLMInterface.__init__ 이 `self.conversation_id = create_conversation_id()` 로
        # <자기 UUID 를 미리 만든다>. 그리고 run_pipeline 의 기본값은 페르소나 선발화라
        # start_conversation 을 거치지 않고 /api/chat 이 먼저 온다 — 즉 첫 요청의
        # conversation_id 는 Mio 가 모르는 UUID 다. 그걸 sessionId 로 믿고 그대로
        # /v1/sessions/{...}/messages 에 넣으면 Mio 가 400 을 낸다.
        #
        # 그래서 들어온 id 를 <이름표> 로만 쓰고, 실제 세션은 여기서 맺어 준다.
        self._cid_map: dict[str, str] = {}
        # sessionId → 마지막 턴 시각. 유휴 세션 회수에 쓴다.
        self._last_activity: dict[str, float] = {}
        # 세션별 done 메타. Mio 자기 판정 vs 외부 judge 대조용.
        self.turn_meta: dict[str, list[dict[str, Any]]] = {}
        log.info("사용자 풀 %d명 — 최대 병렬 대화 %d건", len(user_ids), len(user_ids))

    # ── 인증 ────────────────────────────────────────────────────────

    def _auth_header(self, user_id: str) -> str:
        with self._lock:
            cached = self._tokens.get(user_id)
            fresh = cached and (
                time.time() - cached[1] < TOKEN_TTL_SECONDS - TOKEN_REFRESH_MARGIN
            )
            if not fresh:
                self._tokens[user_id] = (self._issue_dev_token(user_id), time.time())
            return f"Bearer {self._tokens[user_id][0]}"

    def _issue_dev_token(self, user_id: str) -> str:
        # DevTokenRequest 는 @JsonProperty("user_id") 다 — camelCase 로 보내면
        # @NotNull 이 걸려 VALIDATION_ERROR 가 난다.
        body = json.dumps({"user_id": user_id}).encode()
        req = urllib.request.Request(
            f"{self._base}/v1/auth/dev/token",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = json.load(resp)
        # DevTokenResponse 는 @JsonProperty("access_token") 이다.
        token = (payload.get("data") or {}).get("access_token")
        if not token:
            raise RuntimeError(
                "dev 토큰 응답에서 access_token 을 찾지 못했다. "
                f"응답 키: {list((payload.get('data') or {}).keys())}"
            )
        log.debug("dev 토큰 발급 (user=%s)", user_id)
        return token

    # ── 기동 시 회수 ────────────────────────────────────────────────

    def reclaim_stale_sessions(self) -> int:
        """풀 사용자에게 남아 있는 활성 세션을 닫는다.

        앞선 실행이 비정상 종료하거나 손으로 테스트한 세션이 남으면 그 사용자는
        ``SESSION_ALREADY_ACTIVE`` 로 영구히 막힌다. 기동할 때마다 정리해 두면 풀이
        항상 선언한 크기만큼 쓸 수 있다.
        """
        reclaimed = 0
        for user_id in list(self._available):
            try:
                req = urllib.request.Request(
                    f"{self._base}/v1/sessions/active",
                    headers={"Authorization": self._auth_header(user_id)},
                )
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = (json.load(resp).get("data") or {})
            except Exception as e:  # noqa: BLE001
                log.warning("활성 세션 조회 실패 (user=%s): %s", user_id[:8], e)
                continue
            session_id = data.get("session_id")
            if not session_id or data.get("status") != "active":
                continue
            try:
                req = urllib.request.Request(
                    f"{self._base}/v1/sessions/{session_id}/end",
                    data=b"",
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": self._auth_header(user_id),
                    },
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=30):
                    pass
                reclaimed += 1
                log.info("잔여 세션 회수 %s (user=%s)", session_id[:8], user_id[:8])
            except Exception as e:  # noqa: BLE001
                log.warning("잔여 세션 종료 실패 %s: %s", session_id[:8], e)
        return reclaimed

    # ── 사용자 풀 ───────────────────────────────────────────────────

    def _acquire_user(self, timeout: float = 600.0) -> str:
        with self._pool_cv:
            deadline = time.time() + timeout
            while not self._available:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise RuntimeError(
                        "사용자 풀이 비어 대화를 시작할 수 없다. "
                        "--max-concurrent 를 풀 크기 이하로 두거나 풀을 늘린다."
                    )
                self._pool_cv.wait(remaining)
            return self._available.pop()

    def reap_idle(self, idle_seconds: float) -> int:
        """유휴 세션을 닫아 사용자를 반납한다.

        VERA-MH 는 "이 대화가 끝났다" 를 어댑터에 알려주지 않는다 (``EndpointLLM`` 계약에
        그런 신호가 없다). 그래서 마지막 턴으로부터 일정 시간이 지난 세션을 대화 종료로
        보고 회수한다. 이 값이 너무 짧으면 진행 중인 대화의 세션을 닫아 버리므로, 한 턴의
        p95 지연보다 넉넉히 크게 둔다.
        """
        now = time.time()
        stale = [s for s, t in list(self._last_activity.items())
                 if now - t > idle_seconds and s in self._session_owner]
        for session_id in stale:
            log.info("유휴 세션 회수 %s (%.0f초 무활동)",
                     session_id, now - self._last_activity[session_id])
            self.release_session(session_id)
        return len(stale)

    def release_session(self, session_id: str) -> None:
        """세션을 닫고 사용자를 풀에 반납한다."""
        user_id = self._session_owner.pop(session_id, None)
        self._last_activity.pop(session_id, None)
        if user_id is None:
            return
        try:
            req = urllib.request.Request(
                f"{self._base}/v1/sessions/{session_id}/end",
                data=b"",
                headers={
                    "Content-Type": "application/json",
                    "Authorization": self._auth_header(user_id),
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=30):
                pass
        except Exception as e:  # noqa: BLE001
            # 세션 종료 실패를 삼키면 그 사용자는 영구히 막힌다. 반납은 하되 로그를 남긴다.
            log.warning("세션 종료 실패 %s (%s) — 다음 대여에서 막힐 수 있다", session_id, e)
        with self._pool_cv:
            self._available.append(user_id)
            self._pool_cv.notify()

    # ── 세션 ────────────────────────────────────────────────────────

    def create_session(self) -> str:
        user_id = self._acquire_user()
        body = json.dumps({"character_id": self._character_id}).encode()
        req = urllib.request.Request(
            f"{self._base}/v1/sessions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": self._auth_header(user_id),
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = json.load(resp)
        except Exception:
            with self._pool_cv:
                self._available.append(user_id)
                self._pool_cv.notify()
            raise
        session_id = (payload.get("data") or {}).get("session_id")
        if not session_id:
            with self._pool_cv:
                self._available.append(user_id)
                self._pool_cv.notify()
            raise RuntimeError(f"세션 생성 응답에 session_id 가 없다: {payload}")
        self._session_owner[session_id] = user_id
        self._last_activity[session_id] = time.time()
        self.turn_meta[session_id] = []
        # Mio 가 돌려준 id 자신도 매핑에 넣어 둔다. 우리가 응답으로 이 값을 내보내므로
        # 다음 턴부터는 VERA-MH 가 이 값을 conversation_id 로 보낸다.
        self._cid_map[session_id] = session_id
        log.info("세션 생성 %s (user=%s, 남은 풀 %d)",
                 session_id, user_id[:8], len(self._available))
        return session_id

    def resolve_session(self, incoming_cid: str | None) -> str:
        """VERA-MH 가 보낸 conversation_id 를 Mio sessionId 로 해석한다.

        처음 보는 id 면 Mio 세션을 새로 만들어 맺어 준다. 이 매핑이 없으면 VERA-MH 가
        자체 생성한 UUID 가 Mio 경로에 그대로 들어가 400 이 된다.
        """
        with self._lock:
            if incoming_cid and incoming_cid in self._cid_map:
                return self._cid_map[incoming_cid]
        session_id = self.create_session()
        if incoming_cid:
            with self._lock:
                self._cid_map[incoming_cid] = session_id
            log.info("대화 %s ↔ Mio 세션 %s 로 매핑", incoming_cid[:8], session_id[:8])
        return session_id

    # ── 메시지 (SSE 조립) ───────────────────────────────────────────

    def send_message(self, session_id: str, content: str) -> tuple[str, dict[str, Any]]:
        """한 턴을 보내고 최종 전달 본문과 done 메타를 돌려준다."""
        body = json.dumps({"content": content}).encode()
        req = urllib.request.Request(
            f"{self._base}/v1/sessions/{session_id}/messages",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                "Authorization": self._auth_header(
                    self._session_owner.get(session_id, "")),
                # 같은 세션에 요청이 겹치면 409 다. 턴마다 다른 키를 쓴다.
                "Idempotency-Key": f"vera-{session_id}-{time.time_ns()}",
            },
            method="POST",
        )
        accumulated: list[str] = []
        replaced: str | None = None
        crisis_body: str | None = None
        done_meta: dict[str, Any] = {}

        with urllib.request.urlopen(req, timeout=READ_TIMEOUT_SECONDS) as resp:
            event_name: str | None = None
            for raw in resp:
                line = raw.decode("utf-8", errors="replace").rstrip("\n")
                if line.startswith("event:"):
                    event_name = line[len("event:") :].strip()
                    continue
                if not line.startswith("data:"):
                    continue
                data_raw = line[len("data:") :].strip()
                if not data_raw:
                    continue
                try:
                    data = json.loads(data_raw)
                except json.JSONDecodeError:
                    log.warning("SSE data 파싱 실패 (event=%s)", event_name)
                    continue

                if event_name == "delta":
                    accumulated.append(data.get("chunk") or "")
                elif event_name == "delta.replace":
                    # 누적분을 버리고 교체한다. 이 분기를 누락하면 검증 전 본문이 채점된다.
                    replaced = data.get("safe_response") or ""
                elif event_name == "crisis":
                    crisis_body = data.get("fixed_response") or ""
                elif event_name == "done":
                    done_meta = data
                    break

        # 우선순위: 위기 고정 응답 > 교체 응답 > 누적 델타
        if crisis_body is not None:
            final = crisis_body
        elif replaced is not None:
            final = replaced
        else:
            final = "".join(accumulated)

        self._last_activity[session_id] = time.time()
        self.turn_meta.setdefault(session_id, []).append(
            {
                "final_source": (
                    "crisis" if crisis_body is not None
                    else "replace" if replaced is not None
                    else "delta"
                ),
                "chars": len(final),
                **{
                    k: done_meta.get(k)
                    for k in (
                        "is_crisis_flagged",
                        "is_socratic",
                        "cbt_intervention_state",
                        "emotion_score",
                        "finished_reason",
                        "completion_reason",
                    )
                },
            }
        )
        return final, done_meta


class Handler(BaseHTTPRequestHandler):
    client: MioClient  # 서버 기동 시 주입

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        log.debug("http %s", fmt % args)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length) or b"{}")

    def _respond(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        try:
            payload = self._read_json()
            if self.path.rstrip("/").endswith("start_conversation"):
                self._handle_start()
            elif self.path.rstrip("/").endswith("chat"):
                self._handle_chat(payload)
            else:
                self._respond(404, {"error": f"unknown path {self.path}"})
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")[:500]
            log.error("Mio HTTP %s: %s", e.code, detail)
            self._respond(502, {"error": f"mio {e.code}", "detail": detail})
        except Exception as e:  # noqa: BLE001
            log.exception("어댑터 오류")
            self._respond(500, {"error": str(e)})

    def _handle_start(self) -> None:
        """VERA-MH 의 첫 턴. Mio 는 사용자 발화 없이 응답하지 않으므로 세션만 만들고
        빈 본문을 돌려준다 — 페르소나가 먼저 말하게 한다 (--persona-speaks-first)."""
        session_id = self.client.create_session()
        self._respond(
            200,
            {
                "model": "mio",
                "conversation_id": session_id,
                "message": {"role": "assistant", "content": "", "id": session_id},
            },
        )

    def _handle_chat(self, payload: dict[str, Any]) -> None:
        # 들어온 conversation_id 는 VERA-MH 가 자체 생성한 값일 수 있다. 반드시 해석을 거친다.
        session_id = self.client.resolve_session(payload.get("conversation_id"))
        messages = payload.get("messages") or []
        content = ""
        for m in reversed(messages):
            if m.get("content"):
                content = m["content"]
                break
        if not content:
            self._respond(400, {"error": "빈 메시지 — 보낼 사용자 발화가 없다"})
            return
        # Mio 는 4000자 상한이다 (SendMessageRequest @Size).
        content = content[:4000]

        final, meta = self.client.send_message(session_id, content)
        self._respond(
            200,
            {
                "model": "mio",
                "conversation_id": session_id,
                "message": {
                    "role": "assistant",
                    "content": final,
                    "id": meta.get("msg_id") or "",
                },
                # VERA-MH 의 _process_chat_response 가 usage 로 읽는 자리. Mio 는 턴별
                # 토큰을 SSE 로 내지 않으므로 비운다 — 0 으로 채우면 "쟀는데 0" 이 된다.
                "mio_meta": meta,
            },
        )


def main() -> int:
    p = argparse.ArgumentParser(description="VERA-MH ↔ Mio 어댑터")
    p.add_argument("--user-ids", required=True,
                   help="온보딩된 Mio 사용자 UUID 목록 (쉼표 구분) 또는 한 줄에 하나씩 담긴 파일 경로. "
                        "Mio 는 사용자당 활성 세션 1개·레이트리밋 60건/분이라 병렬도만큼 필요하다")
    p.add_argument("--mio-url", default="http://127.0.0.1:8080")
    p.add_argument("--character-id", default="mio", help="mio/bau/rumi/momo/chichi")
    p.add_argument("--port", type=int, default=8900)
    p.add_argument("--meta-out", default=None, help="done 메타를 쓸 JSON 경로")
    p.add_argument("--idle-release", type=float, default=180.0,
                   help="이 시간(초) 동안 턴이 없는 세션을 대화 종료로 보고 회수한다")
    p.add_argument("--verbose", action="store_true")
    args = p.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(message)s",
    )

    import os
    if os.path.isfile(args.user_ids):
        with open(args.user_ids, encoding="utf-8") as f:
            user_ids = [ln.strip() for ln in f if ln.strip() and "-" in ln]
    else:
        user_ids = [u.strip() for u in args.user_ids.split(",") if u.strip()]
    if not user_ids:
        log.error("사용자 목록이 비었다")
        return 2

    client = MioClient(args.mio_url, user_ids, args.character_id)
    # 기동 시점에 토큰과 세션 생성을 한 번 검증한다 — VERA-MH 를 돌리다가
    # 대화 중간에 인증이 깨지는 것보다 여기서 죽는 게 낫다. 프로브 세션은 바로 닫아
    # 풀을 원래 크기로 돌려놓는다.
    reclaimed = client.reclaim_stale_sessions()
    if reclaimed:
        log.info("기동 전 잔여 세션 %d건 회수", reclaimed)
    probe = client.create_session()
    client.release_session(probe)
    log.info("사전 검증 통과 — 프로브 세션 %s 생성·종료", probe)

    def reaper() -> None:
        while True:
            time.sleep(30)
            try:
                client.reap_idle(args.idle_release)
            except Exception:  # noqa: BLE001
                log.exception("유휴 세션 회수 실패")

    threading.Thread(target=reaper, daemon=True).start()

    Handler.client = client
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    log.info("어댑터 대기 http://127.0.0.1:%d  (Mio=%s, character=%s)",
             args.port, args.mio_url, args.character_id)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("종료")
    finally:
        if args.meta_out:
            with open(args.meta_out, "w", encoding="utf-8") as f:
                json.dump(client.turn_meta, f, ensure_ascii=False, indent=2)
            log.info("done 메타 기록 → %s", args.meta_out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
