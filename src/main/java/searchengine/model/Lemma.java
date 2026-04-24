package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "lemma", uniqueConstraints = @UniqueConstraint(name = "uq_lemma_site", columnNames = {"lemma", "site_id"}))
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    private Site site;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String lemma;
    @Column(columnDefinition = "INT NOT NULL")
    private int frequency;
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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        Lemma lemma1 = (Lemma) o;
        return lemma.equals(lemma1.lemma);
    }

    @Override
    public int hashCode() {
        return lemma.hashCode();
    }
}
