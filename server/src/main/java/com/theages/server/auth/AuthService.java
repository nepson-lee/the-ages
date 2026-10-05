package com.theages.server.auth;

import com.theages.server.account.Account;
import com.theages.server.account.AccountRepository;
import com.theages.server.character.CharacterItemRepository;
import com.theages.server.character.PlayerCharacter;
import com.theages.server.character.PlayerCharacterRepository;
import com.theages.server.world.WorldProperties;
import com.theages.server.world.ZoneDefinition;
import com.theages.server.world.item.EquipSlot;
import com.theages.server.world.item.ItemRecord;
import com.theages.server.world.item.ItemTemplate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AccountRepository accounts;
    private final PlayerCharacterRepository characters;
    private final CharacterItemRepository items;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final WorldProperties world;

    public AuthService(AccountRepository accounts, PlayerCharacterRepository characters, CharacterItemRepository items,
                       PasswordEncoder passwordEncoder, TokenService tokenService, WorldProperties world) {
        this.accounts = accounts;
        this.characters = characters;
        this.items = items;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.world = world;
    }

    /** 建立帳號並同時建立同名角色，出生在起始區域，身上帶著出生物品。 */
    @Transactional
    public String register(String username, String password) {
        if (accounts.existsByUsername(username)) {
            throw new AuthException("這個名字已經有人使用了");
        }
        Account account = accounts.save(new Account(username, passwordEncoder.encode(password)));
        ZoneDefinition start = world.startingZoneDefinition();
        PlayerCharacter character = characters.save(new PlayerCharacter(account.getId(), username, start.id(),
            start.spawn().x(), start.spawn().z(), world.startingGold()));
        items.replaceAll(character.getId(), startingItems());
        return tokenService.issue(username);
    }

    @Transactional(readOnly = true)
    public String login(String username, String password) {
        return accounts.findByUsername(username)
            .filter(a -> passwordEncoder.matches(password, a.getPasswordHash()))
            .map(a -> tokenService.issue(a.getUsername()))
            .orElseThrow(() -> new AuthException("帳號或密碼錯誤"));
    }

    /** 出生物品：裝備類若該欄位還空著就直接穿上。 */
    private List<ItemRecord> startingItems() {
        Map<String, ItemTemplate> templates = world.validatedContent().items();
        Set<EquipSlot> used = EnumSet.noneOf(EquipSlot.class);
        List<ItemRecord> records = new ArrayList<>();
        for (String id : world.startingItems()) {
            ItemTemplate t = templates.get(id);
            boolean equip = t.isEquipment() && used.add(t.slot());
            records.add(new ItemRecord(id, 1, equip));
        }
        return records;
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
