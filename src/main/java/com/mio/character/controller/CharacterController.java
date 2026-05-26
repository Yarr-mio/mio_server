package com.mio.character.controller;

import com.mio.character.dto.*;
import com.mio.character.service.CharacterService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping("/v1/characters")
    public ResponseEntity<ApiResponse<CharacterListResponse>> listCharacters() {
        return ResponseEntity.ok(ApiResponse.ok(characterService.listCharacters()));
    }

    @PostMapping("/v1/user/character")
    public ResponseEntity<ApiResponse<CharacterChangeResponse>> changeCharacter(
            Principal principal,
            @Valid @RequestBody CharacterChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(characterService.changeCharacter(resolveUserId(principal), request)));
    }

    @GetMapping("/v1/user/character")
    public ResponseEntity<ApiResponse<UserCharacterResponse>> getCurrentCharacter(
            Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(characterService.getCurrentCharacter(resolveUserId(principal))));
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
