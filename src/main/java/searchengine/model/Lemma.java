package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    //    @ManyToOne(fetch = FetchType.LAZY)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    private Site site;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String lemma;
    @Column(columnDefinition = "INT NOT NULL")
    private int frequency;
    //
    /*@ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "index",
            joinColumns = {@JoinColumn(name = "lemma_id")},
            inverseJoinColumns = {@JoinColumn(name = "page_id")})*/
    @ManyToMany(mappedBy = "lemmas", cascade = {CascadeType.DETACH, CascadeType.MERGE, CascadeType.PERSIST, CascadeType.REFRESH})//, fetch = FetchType.EAGER)
    private List<Page> pages; //do we really need this field?

    @Override
    public String toString() {
        return "Lemma{" +
                "id=" + id +
                /*", site=" + site +*/
                ", lemma='" + lemma + '\'' +
                ", frequency=" + frequency +
                '}';
    }
}
