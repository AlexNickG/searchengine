package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "`index`") //escaped "index" and "rank" because they're reserved by MySQL words
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "page_id", columnDefinition = "INT NOT NULL")
    private Integer pageId;
    @Column(name = "lemma_id", columnDefinition = "INT NOT NULL")
    private Integer lemmaId;
    @Column(name = "`rank`", columnDefinition = "FLOAT NOT NULL")
    private float rank;
}
