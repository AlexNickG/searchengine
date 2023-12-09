package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import javax.persistence.Index;

@Entity
@Getter
@Setter
//@Table(indexes = @Index(name = "path_index", columnList = "path", unique = true))
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne(cascade = CascadeType.ALL)
    private Site site;
    @Column(name = "path", columnDefinition = "TEXT", nullable = false)
    private String path;
    @Column(columnDefinition = "INT NOT NULL")
    private int code;
    @Column(columnDefinition = "MEDIUMTEXT NOT NULL")
    private String content;
}
