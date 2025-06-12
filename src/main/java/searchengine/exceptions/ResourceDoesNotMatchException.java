package searchengine.exceptions;

public class ResourceDoesNotMatchException extends RuntimeException{
    public ResourceDoesNotMatchException(String message) {
        super(message);
    }
}
