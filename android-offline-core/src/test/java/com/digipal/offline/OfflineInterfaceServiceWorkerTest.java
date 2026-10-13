package com.digipal.offline;

import androidx.webkit.ServiceWorkerClientCompat;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class OfflineInterfaceServiceWorkerTest {
    private static final class Recorder implements OfflineInterfaceServiceWorker.Transport {
        ServiceWorkerClientCompat client;
        boolean supported = true;
        public boolean supported() { return supported; }
        public void setClient(ServiceWorkerClientCompat value) { client = value; }
    }
    @Test public void unsupportedTransportDoesNotChangeExistingClient() {
        Recorder transport = new Recorder();
        transport.supported = false;
        OfflineInterfaceServiceWorker handler = new OfflineInterfaceServiceWorker(request -> null, transport);
        assertFalse(handler.install());
        assertNull(transport.client);
    }
    @Test public void retiringActivityDoesNotRemoveReplacementClient() {
        Recorder transport = new Recorder();
        OfflineInterfaceServiceWorker first = new OfflineInterfaceServiceWorker(request -> null, transport);
        OfflineInterfaceServiceWorker second = new OfflineInterfaceServiceWorker(request -> null, transport);
        try {
            assertTrue(first.install());
            assertTrue(second.install());
            ServiceWorkerClientCompat replacement = transport.client;
            first.close();
            assertSame(replacement, transport.client);
            second.close();
            assertNull(transport.client);
        } finally { first.close(); second.close(); }
    }
}
