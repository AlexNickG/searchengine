package searchengine.model;

import javax.persistence.*;

@Entity
@Table(indexes = {})
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(name = "site_id", columnDefinition = "INT NOT NULL")
    private int siteId;
    @Column(columnDefinition = "TEXT NOT NULL", unique = true, nullable = false)
    private String path;
    @Column(columnDefinition = "INT NOT NULL")
    private int code;
    @Column(columnDefinition = "MEDIUMTEXT NOT NULL")
    private String content;
}
