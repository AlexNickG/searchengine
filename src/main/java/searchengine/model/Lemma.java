package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Getter
@Setter
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne(cascade = CascadeType.ALL)
    private Site site;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String lemma;
    @Column(columnDefinition = "INT NOT NULL")
    private int frequency;
}
