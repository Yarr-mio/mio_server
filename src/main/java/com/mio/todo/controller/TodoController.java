package com.mio.todo.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.todo.dto.TodoCheckinRequest;
import com.mio.todo.dto.TodoCheckinResponse;
import com.mio.todo.dto.TodoGenerateRequest;
import com.mio.todo.dto.TodoResponse;
import com.mio.todo.service.TodoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> generate(
            @RequestHeader("X-User-Id") String userIdStr,
            @Valid @RequestBody TodoGenerateRequest request) {
        UUID userId = resolveUserId(userIdStr);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(todoService.generate(userId, request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getTodos(
            @RequestHeader("X-User-Id") String userIdStr,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status) {
        UUID userId = resolveUserId(userIdStr);
        return ResponseEntity.ok(ApiResponse.ok(todoService.getTodos(userId, date, status)));
    }

    @PostMapping("/{todoId}/checkin")
    public ResponseEntity<ApiResponse<TodoCheckinResponse>> checkin(
            @RequestHeader("X-User-Id") String userIdStr,
            @PathVariable UUID todoId,
            @Valid @RequestBody TodoCheckinRequest request) {
        UUID userId = resolveUserId(userIdStr);
        return ResponseEntity.ok(ApiResponse.ok(todoService.checkin(userId, todoId, request)));
    }

    private UUID resolveUserId(String userIdStr) {
        try {
            return UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
