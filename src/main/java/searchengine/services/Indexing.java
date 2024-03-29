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
public class Indexing extends RecursiveAction {
    private PageRepository pageRepository;
    private SiteRepository siteRepository;
    private LemmaRepository lemmaRepository;
    private LemmaFinder lemmaFinder;
    private Site site;
    final private String link;
    //@Value("${connection-settings.userAgent}")
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
    private String referrer;
    private int timeout;


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

        //System.out.println("indexing starts");
    }

    @Override
    public boolean cancel(boolean c) {
        taskList.forEach(t -> t.cancel(true));
        return super.cancel(true);
    }

    @SneakyThrows
    @Override
    protected void compute() {

        if (IndexingServiceImpl.stop) {
            cancel(true);
        }

        Set<String> linksSet;
        int statusCode;
        //String refLink;
        Connection connection;
        Document document;


            connection = Jsoup.connect(link).ignoreHttpErrors(true);
            Thread.sleep(550);
            document = connection.userAgent(userAgent).get();

        statusCode = connection.response().statusCode();

        //refLink = document.select("a").first().attr("href");
        URL refLink = new URL(link);
        Page page = new Page();
        page.setSite(site);
        page.setPath(refLink.getPath());
        page.setCode(statusCode);
        page.setContent(document.toString());
        int pageId = pageRepository.save(page).getId();
        //lemmaFinder.collectLemmas(pageId);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);


        linksSet = document.select("a").stream()
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
        for (String subLink : linksSet) {
            Indexing parse = new Indexing(subLink, site, siteRepository, pageRepository, lemmaRepository, lemmaFinder);

            taskList.add(parse);
        }
        ForkJoinTask.invokeAll(taskList);

        //lemmaFinder.saveIndex(page);
        System.out.println("Set size: " + IndexingServiceImpl.globalLinksSet.size());
        taskList.forEach(Indexing::join);

        //System.out.println("Indexing ended");
    }
}
