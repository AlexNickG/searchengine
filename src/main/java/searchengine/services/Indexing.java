package searchengine.services;

import lombok.SneakyThrows;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;
//@Component
//@ConfigurationProperties(prefix = "connection-settings")
public class Indexing extends RecursiveAction {
    PageRepository pageRepository;
    SiteRepository siteRepository;

    /*@Value("${userAgent в application.yml}")
    private String userAgent;

    @Value("${referrer в application.yml}")
    private String referrer;

    @Value("${timeout в application.yml}")
    private int timeout;*/

    public Indexing(String link, SiteRepository siteRepository, PageRepository pageRepository) {
        this.link = link;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
    }

    final private String link;

//    public Indexing(/*String userAgent, String referrer, int timeout, */String link) {
//        this.userAgent = userAgent;
//        this.referrer = referrer;
//        this.timeout = timeout;
//        this.link = link;
//    }

    @SneakyThrows
    @Override
    protected void compute() {
        Set<String> linksSet;
        List<Indexing> taskList = new ArrayList<>();
        Connection.Response response;
        Document document;
        try {
            Thread.sleep(1000);
            response = Jsoup.connect(link)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0 Chrome/51.0.2704.106 Safari/537.36 OPR/38.0.2220.41")
                    .referrer("google.com").timeout(1000)
                    .execute();
            document = response.parse();
        } catch (IOException e) {
            System.out.println("Broken link: " + link);
            return;
        }


        Page page = new Page();
        Site site = new Site();
        site.setUrl("https://skillbox.ru");
        site.setStatus(Status.INDEXING);
        site.setName("Skillbox");
        site.setStatusTime(LocalDateTime.now());
        page.setSite(site);
        page.setPath(link);
        page.setCode(response.statusCode());
        page.setContent(document.text());
        siteRepository.save(site);
        pageRepository.save(page);


        linksSet = document.select("a").stream()
                .map(e -> e.attr("abs:href"))
                .filter(e -> e.startsWith("http://skillbox.ru")
                        && !e.contains("#")
                        && !e.endsWith(".jpg")
                        && !e.endsWith(".pdf")
                        && !e.endsWith(".png")
                        && !e.contains("?"))
                .collect(Collectors.toSet());

        //linksSet.removeAll(Main.globalLinksSet);
        //Main.globalLinksSet.addAll(linksSet);

        for (String subLink : linksSet) {
            Indexing parse = new Indexing(subLink, siteRepository, pageRepository);
            parse.fork();
            taskList.add(parse);
        }

        //System.out.println("Set size: " + Main.globalLinksSet.size());
        taskList.forEach(Indexing::join);
    }
}
