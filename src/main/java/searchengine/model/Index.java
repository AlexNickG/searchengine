package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Getter
@Setter
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
//    @ManyToOne(cascade = CascadeType.ALL)
//    private Page page;
//    @ManyToOne(cascade = CascadeType.ALL)
//    private Lemma lemma;
    @Column(columnDefinition = "FLOAT NOT NULL")
    private float rank;
}
