package com.nexuscast.player;

import android.app.Activity;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SmartTriggerPermissionsTest {
    static class Request extends PermissionRequest {
        boolean denied;
        String[] granted;
        final String origin;
        final String[] resources;
        Request(String origin, String... resources) { this.origin = origin; this.resources = resources; }
        public Uri getOrigin() { return Uri.parse(origin); }
        public String[] getResources() { return resources; }
        public void grant(String[] resources) { granted = resources; }
        public void deny() { denied = true; }
    }
    private SmartTriggerController controller(Activity activity) {
        return new SmartTriggerController(activity, new SmartTriggerController.Host() {
            public WebView webView() { return null; }
            public boolean trustedOrigin(String value) { return value.equals("https://www.digipalsignage.com"); }
        });
    }
    @Test public void untrustedIframeIsDeniedWithoutStartingAPermissionDialog() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Request r = new Request("https://untrusted.example", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        controller.requestWebPermission(r);
        assertTrue(r.denied);
        assertNull(r.granted);
        controller.destroy();
    }
    @Test public void unsupportedWebResourcesAreNeverGranted() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Request r = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        controller.requestWebPermission(r);
        assertTrue(r.denied);
        controller.destroy();
    }
    @Test public void deniedCameraDoesNotGrantTheWebResource() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Request r = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        controller.requestWebPermission(r);
        controller.permissionsResult(SmartTriggerController.WEB_PERMISSION_REQUEST);
        assertTrue(r.denied);
        controller.destroy();
    }
    @Test public void nativePlaybackMustNotSuspendTheSTWebView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        assertTrue(controller.requiresActiveWebView());
        controller.setConfig("[]");
        assertTrue(controller.requiresActiveWebView());
        controller.destroy();
        assertFalse(controller.requiresActiveWebView());
    }
}
