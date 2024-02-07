package searchengine.services;

import lombok.Getter;
import lombok.Setter;
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

import javax.annotation.PreDestroy;
import java.io.IOException;
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
    private Site site;
    final private String link;
    //@Value("${connection-settings.userAgent}")
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0 Chrome/51.0.2704.106 Safari/537.36 OPR/38.0.2220.41";
    private String referrer;
    private int timeout;


    List<Indexing> taskList = new ArrayList<>();



    /*@Value("${userAgent в application.yml}")
    private String userAgent;

    @Value("${referrer в application.yml}")
    private String referrer;

    @Value("${timeout в application.yml}")
    private int timeout;*/

    public Indexing(String link, Site site, SiteRepository siteRepository, PageRepository pageRepository) {
        this.link = link;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
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

        if (this.isCancelled()) {
            cancel(true);
        }

        Set<String> linksSet;

        Connection.Response response;
        Document document;

        try {
//            Thread.sleep(950);
//            response = Jsoup.connect(link).execute();
            Thread.sleep(950);
            document = Jsoup.connect(link).userAgent(userAgent).get();
        } catch (IOException e) {
            System.out.println("Broken link: " + link);
            return;
        }
            /*response = Jsoup.connect(link)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0 Chrome/51.0.2704.106 Safari/537.36 OPR/38.0.2220.41")
                    .referrer("google.com").timeout(1000).execute().bufferUp();
            document = response.parse();*/

        Page page = new Page();
        page.setSite(site);
        page.setPath(link);
        page.setCode(document.connection().response().statusCode());
        page.setContent(document.text());
        pageRepository.save(page);


        linksSet = document.select("a").stream()
                .map(e -> e.attr("abs:href"))
                .filter(e -> e.startsWith(link)
                        && !e.contains("#")
                        && !e.endsWith(".jpg")
                        && !e.endsWith(".pdf")
                        && !e.endsWith(".png")
                        && !e.contains("?"))
                .collect(Collectors.toSet());

        if (this.isCancelled()) {
            linksSet.clear();
        }

        linksSet.removeAll(IndexingServiceImpl.globalLinksSet);
        IndexingServiceImpl.globalLinksSet.addAll(linksSet);
        for (String subLink : linksSet) {
            Indexing parse = new Indexing(subLink, site, siteRepository, pageRepository);

            //parse.fork();
            taskList.add(parse);
        }
        ForkJoinTask.invokeAll(taskList);
        //System.out.println("Set size: " + Main.globalLinksSet.size());
        taskList.forEach(Indexing::join);
        System.out.println("Indexing ended");
    }
}
