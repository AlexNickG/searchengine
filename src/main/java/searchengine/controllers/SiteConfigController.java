package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.Repositories.SiteConfigRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.model.SiteConfig;
import searchengine.services.PageProcessorService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/sites")
@RequiredArgsConstructor
public class SiteConfigController {

    private final SiteConfigRepository siteConfigRepository;
    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageProcessorService pageProcessorService;

    @GetMapping
    public List<SiteConfig> getAll() {
        return siteConfigRepository.findAll();
    }

    @PostMapping
    public SiteConfig create(@RequestBody SiteConfig dto) {
        dto.setId(null);
        SiteConfig saved = siteConfigRepository.save(dto);
        syncToSitesList();
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<SiteConfig> update(@PathVariable Long id, @RequestBody SiteConfig dto) {
        return siteConfigRepository.findById(id).map(existing -> {
            existing.setUrl(dto.getUrl());
            existing.setName(dto.getName());
            SiteConfig saved = siteConfigRepository.save(existing);
            syncToSitesList();
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!siteConfigRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        siteConfigRepository.deleteById(id);
        syncToSitesList();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/data")
    public ResponseEntity<Void> deleteSiteData(@RequestParam String url) {
        searchengine.model.Site site = siteRepository.findByUrl(url);
        if (site != null) {
            pageProcessorService.clearSite(site.getId());
        }
        siteConfigRepository.findByUrl(url).ifPresent(sc -> {
            siteConfigRepository.deleteById(sc.getId());
            syncToSitesList();
        });
        return ResponseEntity.noContent().build();
    }

    private void syncToSitesList() {
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
    }
}