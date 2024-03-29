package searchengine.model;

import com.sun.istack.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import javax.persistence.Index;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = {@Index(name = "path_index", columnList = "path", unique = true)})
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    private Site site;
    @Column(columnDefinition = "TEXT NOT NULL, Index(path(512))")
    private String path;
    @Column(columnDefinition = "INT NOT NULL")
    private int code;
    @Column(columnDefinition = "MEDIUMTEXT NOT NULL")
    private String content;
    @ManyToMany(cascade = CascadeType.ALL) //fetch - ?
    @JoinTable(name = "index",
            joinColumns = {@JoinColumn(name = "page_id")},
            inverseJoinColumns = {@JoinColumn(name = "lemma_id")})
    private List<Lemma> lemmas;
    @Override
    public String toString() {
        return "Page{" +
                "id=" + id +
                ", path='" + path + '\'' +
                ", content='" + content + '\'' +
                '}';
    }
}
