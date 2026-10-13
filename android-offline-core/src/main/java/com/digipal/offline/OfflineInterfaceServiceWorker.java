package com.digipal.offline;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewFeature;

/** Standard candidate counterpart to WebViewClient's local interception. */
public final class OfflineInterfaceServiceWorker implements AutoCloseable {
    public interface Interceptor { WebResourceResponse intercept(WebResourceRequest request); }
    public interface Transport {
        boolean supported();
        void setClient(ServiceWorkerClientCompat client);
    }
    private static OfflineInterfaceServiceWorker owner;
    private final Interceptor interceptor;
    private final Transport transport;

    public OfflineInterfaceServiceWorker(Interceptor interceptor) {
        this(interceptor, new Transport() {
            public boolean supported() {
                return WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST);
            }
            public void setClient(ServiceWorkerClientCompat client) {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(client);
            }
        });
    }
    public OfflineInterfaceServiceWorker(Interceptor interceptor, Transport transport) {
        this.interceptor = interceptor;
        this.transport = transport;
    }
    public boolean install() {
        if (!transport.supported()) return false;
        synchronized (OfflineInterfaceServiceWorker.class) {
            transport.setClient(new ServiceWorkerClientCompat() {
                @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return interceptor.intercept(request);
                }
            });
            owner = this;
        }
        return true;
    }
    public void close() {
        synchronized (OfflineInterfaceServiceWorker.class) {
            if (owner == this) {
                transport.setClient(null);
                owner = null;
            }
        }
    }
}
