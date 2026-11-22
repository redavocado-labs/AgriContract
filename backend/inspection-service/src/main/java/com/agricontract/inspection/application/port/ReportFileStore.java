package com.agricontract.inspection.application.port;

import java.io.InputStream;

public interface ReportFileStore {
    record StoredFile(String fileId, String status) {}
    StoredFile storeOnBehalfOf(String actor, String key, String contentType, long length, InputStream body);
}
