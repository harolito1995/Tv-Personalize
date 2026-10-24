package com.harolhome.tv;

/**
 * Failure while loading the remote catalogue or one of its images. The code drives the Spanish
 * message shown on screen; the detail is only kept for logs.
 */
class CatalogException extends Exception {

    enum Code {
        BAD_URL,     // not a parseable absolute URL
        NOT_HTTPS,   // parsed, but plain http or another scheme
        OFFLINE,     // no connection, timeout or name resolution failure
        HTTP,        // server answered with a non-200 status
        REDIRECTS,   // too many redirects or a broken redirect chain
        TOO_BIG,     // catalogue or image over the configured size limit
        FORMAT,      // not valid JSON, unsupported version or not a decodable image
        STORAGE      // impossible to save the file in private storage
    }

    final Code code;
    final int httpCode;

    CatalogException(Code code, String detail) {
        this(code, detail, 0);
    }

    CatalogException(Code code, String detail, int httpCode) {
        super(detail == null ? code.name() : detail);
        this.code = code;
        this.httpCode = httpCode;
    }
}
