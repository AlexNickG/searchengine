package searchengine.model;

import com.sun.istack.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import javax.persistence.Index;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = {@Index(name = "path_index", columnList = "path", unique = true)})
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    private Site site;
    @Column(columnDefinition = "TEXT NOT NULL, Index(path(512))")
    private String path;
    @Column(columnDefinition = "INT NOT NULL")
    private int code;
    @Column(columnDefinition = "MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", nullable = false)
    private String content;
}
