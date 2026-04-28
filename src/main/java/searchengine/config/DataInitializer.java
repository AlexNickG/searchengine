package searchengine.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import searchengine.Repositories.SiteConfigRepository;
import searchengine.Repositories.UserRepository;
import searchengine.model.AppUser;
import searchengine.model.SiteConfig;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SiteConfigRepository siteConfigRepository;
    private final SitesList sitesList;

    @Override
    public void run(ApplicationArguments args) {
        initAdminUser();
        initSiteConfig();
    }

    private void initAdminUser() {
        if (userRepository.count() == 0) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("changeme"));
            admin.setRole("ROLE_ADMIN");
            admin.setEnabled(true);
            userRepository.save(admin);
            log.warn("=== Default admin user created (admin/changeme). Change the password immediately! ===");
        }
    }

    private void initSiteConfig() {
        if (siteConfigRepository.count() == 0 && sitesList.getSites() != null) {
            // First run: seed DB from yaml
            sitesList.getSites().forEach(s -> {
                SiteConfig sc = new SiteConfig();
                sc.setUrl(s.getUrl());
                sc.setName(s.getName());
                siteConfigRepository.save(sc);
            });
            log.info("Seeded site_config from application.yaml ({} sites)", sitesList.getSites().size());
        }

        // Always load active config from DB into SitesList
        List<Site> sites = siteConfigRepository.findAll().stream()
                .map(sc -> {
                    Site s = new Site();
                    s.setUrl(sc.getUrl());
                    s.setName(sc.getName());
                    s.setStartUrl(sc.getStartUrl());
                    return s;
                })
                .toList();
        sitesList.setSites(sites);
        log.info("Loaded {} site(s) from site_config into SitesList", sites.size());
    }
}