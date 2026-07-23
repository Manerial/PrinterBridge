package org.printerbridge.api;

record PrintResult(String status, String message) {

    static PrintResult ok() {
        return new PrintResult("OK", null);
    }

    static PrintResult error(String message) {
        return new PrintResult("ERROR", message);
    }
}
