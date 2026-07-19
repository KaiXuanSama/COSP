package com.kaixuan.copilot_ollama_proxy.application.account;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 管理员账号更新用例。
 */
@Service
public class AccountService {

    private final JdbcUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;

    public AccountService(JdbcUserDetailsManager userDetailsManager, PasswordEncoder passwordEncoder) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
    }

    public Mono<AccountUpdateResult> updateAccount(String currentUsername, AccountUpdateCommand command) {
        return Mono.fromCallable(() -> updateAccountBlocking(currentUsername, command))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AccountUpdateResult updateAccountBlocking(String currentUsername, AccountUpdateCommand command) {
        UserDetails currentUser = userDetailsManager.loadUserByUsername(currentUsername);
        if (command.currentPassword() == null
                || !passwordEncoder.matches(command.currentPassword(), currentUser.getPassword())) {
            return AccountUpdateResult.rejected("当前密码不正确。");
        }

        String requestedUsername = command.newUsername() == null ? "" : command.newUsername().trim();
        String finalUsername = requestedUsername.isBlank() || requestedUsername.equals(currentUsername)
                ? currentUsername : requestedUsername;
        String newPassword = command.newPassword();
        if (newPassword != null && !newPassword.isBlank()) {
            if (newPassword.length() < 4) {
                return AccountUpdateResult.rejected("新密码长度至少 4 位。");
            }
            if (!newPassword.equals(command.confirmPassword())) {
                return AccountUpdateResult.rejected("两次输入的新密码不一致。");
            }
        }

        boolean passwordChanged = newPassword != null && !newPassword.isBlank();
        boolean usernameChanged = !finalUsername.equals(currentUsername);
        boolean changed = passwordChanged || usernameChanged;
        if (!changed) {
            return new AccountUpdateResult(true, "未做任何修改。", false, false);
        }

        userDetailsManager.deleteUser(currentUsername);
        String passwordToUse = passwordChanged ? passwordEncoder.encode(newPassword) : currentUser.getPassword();
        userDetailsManager.createUser(User.withUsername(finalUsername).password(passwordToUse).roles("ADMIN").build());
        return new AccountUpdateResult(true, "账号信息已修改，请使用新账号重新登录。", usernameChanged, true);
    }

    public record AccountUpdateCommand(String newUsername, String currentPassword, String newPassword,
                                       String confirmPassword) {
    }

    public record AccountUpdateResult(boolean ok, String message, boolean usernameChanged, boolean changed) {
        private static AccountUpdateResult rejected(String message) {
            return new AccountUpdateResult(false, message, false, false);
        }
    }
}