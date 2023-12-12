package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
@Entity
@Getter
@Setter
@Table(name = "`index`")
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne(cascade = CascadeType.ALL)
    private Page page;
    @ManyToOne(cascade = CascadeType.ALL)
    private Lemma lemma;
    @Column(name = "`rank`", columnDefinition = "FLOAT NOT NULL")
    private float rank;
}
