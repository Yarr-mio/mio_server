package com.mio.todo.dto;

import java.util.List;

public record TodoListResponse(List<TodoResponse> todos) {}
