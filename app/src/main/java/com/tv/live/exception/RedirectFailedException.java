package com.tv.live.exception;

import java.io.IOException;

public class RedirectFailedException extends IOException {
    private final int code;
    private final String location;
    private final String originUrl;

    public RedirectFailedException(String msg, int code, String originUrl, String location) {
        super(msg);
        this.code = code;
        this.originUrl = originUrl;
        this.location = location;
    }

    public int getCode() {
        return code;
    }

    public String getLocation() {
        return location;
    }

    public String getOriginUrl() {
        return originUrl;
    }
}
