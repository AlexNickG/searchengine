package searchengine.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

//@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "Задан пустой поисковый запрос")
public class EmptyQueryException extends RuntimeException{
    public EmptyQueryException(String message) {
        super(message);
    }
}
