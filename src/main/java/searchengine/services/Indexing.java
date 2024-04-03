package searchengine.services;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;

//@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "connection-settings")
public class Indexing extends RecursiveAction {
    private PageRepository pageRepository;
    private SiteRepository siteRepository;
    private LemmaRepository lemmaRepository;
    private LemmaFinder lemmaFinder;
    private Site site;
    final private String link;
    //@Value("${connection-settings.userAgent}")
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
    private List<Indexing> taskList = new ArrayList<>();

    /*@Value("${userAgent в application.yml}")
    private String userAgent;

    @Value("${referrer в application.yml}")
    private String referrer;

    @Value("${timeout в application.yml}")
    private int timeout;*/

    public Indexing(String link, Site site, SiteRepository siteRepository, PageRepository pageRepository, LemmaRepository lemmaRepository, LemmaFinder lemmaFinder) {
        this.link = link;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.lemmaFinder = lemmaFinder;
        this.site = site;
    }

    @Override
    public boolean cancel(boolean c) {
        taskList.forEach(t -> t.cancel(true));
        return super.cancel(true);
    }

    @SneakyThrows
    @Override
    protected void compute() {
        Thread.sleep(550);
        if (IndexingServiceImpl.stop) {
            cancel(true);
        }

        System.out.println("Thread" + Thread.currentThread().getName() + "connect to: "+ link);
        Connection connection = Jsoup.connect(link).ignoreHttpErrors(true);
        Document document = connection.userAgent(userAgent).get();
        int statusCode = connection.response().statusCode();

        String content = document.toString();
        if (String.valueOf(statusCode).startsWith("4") || String.valueOf(statusCode).startsWith("5")) {
            content = null;
        }
        Page page = new Page();
        page.setSite(site);
        page.setPath(new URL(link).getPath());
        page.setCode(statusCode);
        page.setContent(content);
        int pageId = pageRepository.saveAndFlush(page).getId();
        lemmaFinder.collectLemmas(pageId);//why does the location where this method is called have no effect?
        site.setStatusTime(LocalDateTime.now());
        siteRepository.saveAndFlush(site);

        Set<String> linksSet = document.select("a").stream()
                .map(e -> e.attr("abs:href"))
                .filter(e -> e.contains(site.getUrl())
                        && !e.contains("#") //TODO: add to config
                        && !e.endsWith(".jpg")
                        && !e.endsWith(".pdf")
                        && !e.endsWith(".png")
                        && !e.contains("?"))
                .collect(Collectors.toSet());

        if (IndexingServiceImpl.stop) {
            System.out.println("cancelled");
            linksSet.clear();
        }

        linksSet.removeAll(IndexingServiceImpl.globalLinksSet);
        IndexingServiceImpl.globalLinksSet.addAll(linksSet);

        System.out.println("linksSet size: " + linksSet.size());
        for (String subLink : linksSet) {
            Indexing parse = new Indexing(subLink, site, siteRepository, pageRepository, lemmaRepository, lemmaFinder);
            taskList.add(parse);
        }
        ForkJoinTask.invokeAll(taskList);

        System.out.println(Thread.currentThread().getName() + " isCancelled: " + isCancelled());
        System.out.println(Thread.currentThread().getName() + " isCompletedAbnormally: " + isCompletedAbnormally());
        //System.out.println("Set size: " + IndexingServiceImpl.globalLinksSet.size());
        taskList.forEach(Indexing::join);
    }
}
