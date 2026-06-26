package com.mio.todo.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.todo.dto.TodoCheckinRequest;
import com.mio.todo.dto.TodoCheckinResponse;
import com.mio.todo.dto.TodoListResponse;
import com.mio.todo.dto.TodoResponse;
import com.mio.todo.service.TodoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @GetMapping
    public ResponseEntity<ApiResponse<TodoListResponse>> getTodos(
            Principal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(new TodoListResponse(todoService.getTodos(userId, date, status))));
    }

    @PostMapping("/{todoId}/checkin")
    public ResponseEntity<ApiResponse<TodoCheckinResponse>> checkin(
            Principal principal,
            @PathVariable UUID todoId,
            @Valid @RequestBody TodoCheckinRequest request) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(todoService.checkin(userId, todoId, request)));
    }

    private UUID resolveUserId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
