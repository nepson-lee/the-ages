package com.theages.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginThrottle throttle;

    public AuthController(AuthService authService, LoginThrottle throttle) {
        this.authService = authService;
        this.throttle = throttle;
    }

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        throttle.acquire(http.getRemoteAddr(), LoginThrottle.Action.REGISTER).ifPresent(wait -> {
            throw new TooManyAttemptsException(wait);
        });
        String token = authService.register(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TokenResponse(token));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        throttle.acquire(http.getRemoteAddr(), LoginThrottle.Action.LOGIN).ifPresent(wait -> {
            throw new TooManyAttemptsException(wait);
        });
        // 帳號鎖住時連密碼都不檢查：就算這次猜對了也不會知道
        throttle.lockedFor(request.username()).ifPresent(wait -> {
            throw new TooManyAttemptsException(wait);
        });
        try {
            String token = authService.login(request.username(), request.password());
            throttle.recordSuccess(request.username());
            return new TokenResponse(token);
        } catch (AuthService.AuthException e) {
            throttle.recordFailure(request.username());
            throw e;
        }
    }

    /**
     * 嘗試太頻繁。IP 限流與帳號鎖定用同一個訊息，不透露是哪一種（也就不透露帳號是否存在）。
     */
    static final class TooManyAttemptsException extends RuntimeException {
        final Duration retryAfter;

        TooManyAttemptsException(Duration retryAfter) {
            super("嘗試次數過多");
            this.retryAfter = retryAfter;
        }
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    ResponseEntity<Map<String, String>> handleTooMany(TooManyAttemptsException e) {
        long seconds = Math.max(1, (e.retryAfter.toMillis() + 999) / 1000);
        String wait = seconds >= 60 ? "約 " + (seconds + 59) / 60 + " 分鐘" : seconds + " 秒";
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(Map.of("error", "嘗試次數過多，請 " + wait + "後再試。"));
    }

    @ExceptionHandler(AuthService.AuthException.class)
    ResponseEntity<Map<String, String>> handleAuth(AuthService.AuthException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(FieldError::getDefaultMessage)
            .orElse("輸入資料不正確");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    /** 兩個請求同時註冊同一個名字時，由資料庫的 UNIQUE 擋下。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> handleDuplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "這個名字已經有人使用了"));
    }

    /** 角色名稱沿用帳號名稱，所以限制為英數字（MUD 傳統）。 */
    public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{2,15}", message = "名字需為 3-16 個英數字，且以英文字母開頭")
        String username,
        @NotBlank @Size(min = 8, max = 72, message = "密碼長度需為 8-72 個字元")
        String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String token) {
    }
}
