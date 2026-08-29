package com.mio.session.service;

import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Message;
import com.mio.session.domain.Session;
import com.mio.session.dto.SessionMessagesResponse;
import com.mio.session.dto.SessionMessagesResponse.SessionMessageItem;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 세션 대화 이력 조회 (이슈 #531).
 *
 * <p>대화 원문은 계속 {@code messages} 에 저장돼 왔지만 사용자용 조회 경로가 없어서, 앱을 다시
 * 켜면 진행 중이던 대화를 화면에 되살릴 방법이 없었다. 선제 인사(#530)가 세션의 첫 메시지로
 * 저장되면서 이 공백이 더 분명해졌다.
 *
 * <p>대화 원문을 반환하는 경로이므로 소유자 검증이 유일한 방어선이다. 조회 전용이며 어떤
 * 상태도 바꾸지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionMessageHistoryService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    /** 커서 구분자. UUID·ISO 시각 어디에도 나타나지 않는 문자를 쓴다. */
    private static final String CURSOR_DELIMITER = "|";

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final MessageEncryptor messageEncryptor;

    /**
     * 세션의 대화를 오래된 순으로 반환한다.
     *
     * @param cursor 이전 응답의 {@code next_cursor}. null 이면 가장 오래된 메시지부터
     * @param limit  1~{@value #MAX_LIMIT}. null 이면 {@value #DEFAULT_LIMIT}
     */
    @Transactional(readOnly = true)
    public SessionMessagesResponse getHistory(UUID userId, UUID sessionId, String cursor, Integer limit) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        int pageSize = resolveLimit(limit);
        // 한 건 더 읽어 다음 페이지 존재 여부를 판단한다. 별도 count 쿼리를 돌리면 페이지마다
        // 전체 스캔이 한 번 더 붙는다.
        PageRequest page = PageRequest.of(0, pageSize + 1);

        List<Message> rows = cursor == null || cursor.isBlank()
                ? messageRepository.findHistoryFirstPage(sessionId, page)
                : findAfterCursor(sessionId, cursor, page);

        boolean hasNext = rows.size() > pageSize;
        List<Message> pageRows = hasNext ? rows.subList(0, pageSize) : rows;

        List<SessionMessageItem> items = new ArrayList<>(pageRows.size());
        for (Message message : pageRows) {
            decrypt(message).ifPresent(content -> items.add(new SessionMessageItem(
                    message.getId(),
                    message.getRole().value(),
                    message.getMessageKind().value(),
                    content,
                    message.getCreatedAt())));
        }

        // 커서는 복호화 성공 여부와 무관하게 실제로 읽은 마지막 행을 기준으로 만든다.
        // 복호화에 실패한 행을 기준에서 빼면 다음 페이지가 그 행부터 다시 시작해 무한히 맴돈다.
        String nextCursor = hasNext ? encodeCursor(pageRows.get(pageRows.size() - 1)) : null;

        return new SessionMessagesResponse(sessionId, items, nextCursor, hasNext);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return limit;
    }

    private List<Message> findAfterCursor(UUID sessionId, String cursor, PageRequest page) {
        Cursor decoded = decodeCursor(cursor);
        return messageRepository.findHistoryAfter(sessionId, decoded.createdAt(), decoded.id(), page);
    }

    /**
     * 복호화한 본문. 실패하면 빈 값을 반환하고 그 메시지를 건너뛴다.
     *
     * <p>한 건의 복호화 실패로 이력 전체가 500 이 되면 사용자는 대화로 돌아갈 수 없다.
     * 원문은 로그에 남기지 않는다.
     */
    private Optional<String> decrypt(Message message) {
        try {
            return Optional.of(
                    new String(messageEncryptor.decrypt(message.getContentCiphertext()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Skipping undecryptable message in history: messageId={}", message.getId(), e);
            return Optional.empty();
        }
    }

    private String encodeCursor(Message last) {
        String raw = last.getCreatedAt() + CURSOR_DELIMITER + last.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf(CURSOR_DELIMITER);
            if (separator < 0) {
                throw new IllegalArgumentException("missing delimiter");
            }
            return new Cursor(
                    OffsetDateTime.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // 커서는 서버가 만든 opaque 값이다. 깨진 값이 오면 클라이언트가 형식을 조립했거나
            // 잘라 보낸 것이므로, 조용히 첫 페이지로 되돌리지 않고 잘못된 입력으로 알린다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {
    }
}
