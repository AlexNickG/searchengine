package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
@Entity
@Getter
@Setter
@Table(name = "`index`") //escaped "index" and "rank" because they're reserved by MySQL words
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(name = "page_id", columnDefinition = "INT NOT NULL")
    private int pageId;
    @Column(name = "lemma_id", columnDefinition = "INT NOT NULL")
    private int lemmaId;
    @Column(name = "`rank`", columnDefinition = "FLOAT NOT NULL")
    private float rank;
}
