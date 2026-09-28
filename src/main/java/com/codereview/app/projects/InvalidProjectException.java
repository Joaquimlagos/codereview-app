package com.codereview.app.projects;

public class InvalidProjectException extends RuntimeException {

    public InvalidProjectException(String message) {
        super(message);
    }
}
