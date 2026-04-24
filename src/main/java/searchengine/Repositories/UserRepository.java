package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.AppUser;

import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
}
