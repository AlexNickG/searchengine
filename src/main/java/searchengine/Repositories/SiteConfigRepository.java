package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.SiteConfig;

import java.util.Optional;

public interface SiteConfigRepository extends JpaRepository<SiteConfig, Long> {
    Optional<SiteConfig> findByUrl(String url);
}