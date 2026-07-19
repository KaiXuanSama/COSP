package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.account.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理后台账号 API。 */
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/config/api/account")
    public Mono<ResponseEntity<Map<String, Object>>> saveAccount(
            Mono<Authentication> authenticationMono, ServerWebExchange exchange) {
        return Mono.zip(authenticationMono, exchange.getFormData())
                .flatMap(tuple -> accountService.updateAccount(tuple.getT1().getName(), toCommand(tuple.getT2())))
                .map(result -> ResponseEntity.ok(toResponse(result)));
    }

    private Map<String, Object> toResponse(AccountService.AccountUpdateResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", result.ok());
        if (!result.ok()) {
            response.put("error", result.message());
        } else {
            response.put("message", result.message());
            if (result.changed()) {
                response.put("usernameChanged", result.usernameChanged());
            }
        }
        return response;
    }

    private AccountService.AccountUpdateCommand toCommand(MultiValueMap<String, String> form) {
        return new AccountService.AccountUpdateCommand(
                form.getFirst("newUsername"),
                form.getFirst("currentPassword"),
                form.getFirst("newPassword"),
                form.getFirst("confirmPassword"));
    }
}