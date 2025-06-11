package searchengine.exceptions;

public class ResourceDoesNotMatchException extends RuntimeException{
    public ResourceDoesNotMatchException(String message) {
        super(message);
    }

//    public ResourceDoesNotMatchException(String message, Throwable cause) {
//        super(message, cause);
//    }
}
