package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.SiteConfig;

public interface SiteConfigRepository extends JpaRepository<SiteConfig, Long> {
}