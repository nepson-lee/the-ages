package com.theages.server.auth;

import com.theages.server.account.Account;
import com.theages.server.account.AccountRepository;
import com.theages.server.character.PlayerCharacter;
import com.theages.server.character.PlayerCharacterRepository;
import com.theages.server.world.WorldProperties;
import com.theages.server.world.ZoneDefinition;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AccountRepository accounts;
    private final PlayerCharacterRepository characters;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final WorldProperties world;

    public AuthService(AccountRepository accounts, PlayerCharacterRepository characters,
                       PasswordEncoder passwordEncoder, TokenService tokenService, WorldProperties world) {
        this.accounts = accounts;
        this.characters = characters;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.world = world;
    }

    /** 建立帳號並同時建立同名角色，出生在起始區域。 */
    @Transactional
    public String register(String username, String password) {
        if (accounts.existsByUsername(username)) {
            throw new AuthException("這個名字已經有人使用了");
        }
        Account account = accounts.save(new Account(username, passwordEncoder.encode(password)));
        ZoneDefinition start = world.startingZoneDefinition();
        characters.save(new PlayerCharacter(account.getId(), username, start.id(),
            start.spawn().x(), start.spawn().z()));
        return tokenService.issue(username);
    }

    @Transactional(readOnly = true)
    public String login(String username, String password) {
        return accounts.findByUsername(username)
            .filter(a -> passwordEncoder.matches(password, a.getPasswordHash()))
            .map(a -> tokenService.issue(a.getUsername()))
            .orElseThrow(() -> new AuthException("帳號或密碼錯誤"));
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
