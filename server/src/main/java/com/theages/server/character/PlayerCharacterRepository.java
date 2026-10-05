package com.theages.server.character;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerCharacterRepository extends JpaRepository<PlayerCharacter, Long> {

    Optional<PlayerCharacter> findByName(String name);
}
