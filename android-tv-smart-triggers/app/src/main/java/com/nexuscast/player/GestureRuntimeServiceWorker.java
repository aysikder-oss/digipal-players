package com.nexuscast.player;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewFeature;

/** ST-only counterpart to WebViewClient interception for service-worker fetches. */
final class GestureRuntimeServiceWorker implements AutoCloseable {
    interface TrustState { boolean trusted(); }
    interface Transport {
        boolean supported();
        void setClient(ServiceWorkerClientCompat client);
    }

    private static GestureRuntimeServiceWorker owner;
    private final GestureRuntimeAssets runtime;
    private final TrustState trustedDocument;
    private final GestureRuntimeAssets.OriginPolicy policy;
    private final Transport transport;

    GestureRuntimeServiceWorker(GestureRuntimeAssets runtime, TrustState trustedDocument,
            GestureRuntimeAssets.OriginPolicy policy) {
        this(runtime, trustedDocument, policy, new Transport() {
            public boolean supported() {
                return WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
                        && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST);
            }
            public void setClient(ServiceWorkerClientCompat client) {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(client);
            }
        });
    }

    GestureRuntimeServiceWorker(GestureRuntimeAssets runtime, TrustState trustedDocument,
            GestureRuntimeAssets.OriginPolicy policy, Transport transport) {
        this.runtime = runtime;
        this.trustedDocument = trustedDocument;
        this.policy = policy;
        this.transport = transport;
    }

    boolean install() {
        if (!transport.supported()) {
            Log.i("STGestureRuntime", "Service-worker interception not supported by this WebView");
            return false;
        }
        synchronized (GestureRuntimeServiceWorker.class) {
            transport.setClient(new ServiceWorkerClientCompat() {
                @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    // Reuse the exact URL allowlist and initiator checks. All unrelated
                    // requests retain the existing service worker's network behavior.
                    return runtime.intercept(request, trustedDocument.trusted(), policy);
                }
            });
            owner = this;
        }
        Log.i("STGestureRuntime", "Service-worker interception installed: " + GestureRuntimeAssets.VERSION);
        return true;
    }

    @Override public void close() {
        synchronized (GestureRuntimeServiceWorker.class) {
            // A retiring Activity must not remove a newer Activity's process-wide client.
            if (owner == this) {
                transport.setClient(null);
                owner = null;
            }
        }
    }
}
