package searchengine.model;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@TypeDef(name = "pgsql_enum", typeClass = PostgreSQLEnumType.class)
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FAILED') NOT NULL")
    @Type(type = "pgsql_enum")
    private Status status;
    @Column(name = "status_time", columnDefinition = "DATETIME NOT NULL")
    private LocalDateTime statusTime;
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String url;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String name;
    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL)
    private List<Page> pageList = new ArrayList<>();
    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL)
    private List<Lemma> lemmaList = new ArrayList<>();

}
