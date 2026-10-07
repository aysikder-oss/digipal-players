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
import android.Manifest;
import android.os.Looper;
import org.robolectric.Shadows;
import java.time.Duration;

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

    @Test public void cameraAndMicrophoneDialogsAreSerializedInsteadOfRejectingTheSecondInput() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Request camera = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        Request mic = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        controller.requestWebPermission(camera);
        controller.requestWebPermission(mic);
        assertFalse(mic.denied);
        assertNull(mic.granted);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        controller.permissionsResult(SmartTriggerController.WEB_PERMISSION_REQUEST);
        assertArrayEquals(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}, camera.granted);
        assertFalse(mic.denied);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        controller.permissionsResult(SmartTriggerController.WEB_PERMISSION_REQUEST);
        assertArrayEquals(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}, mic.granted);
        controller.destroy();
    }

    @Test public void authorizedCameraWorksWhileMicrophoneOrHardwarePermissionIsPending() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        Request mic = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        controller.requestWebPermission(mic);
        Request camera = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        controller.requestWebPermission(camera);
        assertArrayEquals(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}, camera.granted);
        controller.destroy();

        SmartTriggerController hardware = controller(activity);
        hardware.startBleScan();
        Request second = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        hardware.requestWebPermission(second);
        assertArrayEquals(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}, second.granted);
        hardware.destroy();
    }

    @Test public void cancellingAnInputCannotApplyItsAndroidResultToANewerInput() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Request old = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        Request current = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        controller.requestWebPermission(old);
        controller.cancelWebPermission(old);
        controller.requestWebPermission(current);
        controller.permissionsResult(SmartTriggerController.WEB_PERMISSION_REQUEST);
        assertNull(current.granted);
        assertFalse(current.denied);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        controller.permissionsResult(SmartTriggerController.WEB_PERMISSION_REQUEST);
        assertArrayEquals(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}, current.granted);
        assertNull(old.granted);
        controller.destroy();
    }

    @Test public void navigationAndDestructionDenyEveryQueuedMediaRequest() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (boolean destroy : new boolean[]{false, true}) {
            SmartTriggerController controller = controller(activity);
            Request camera = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            Request mic = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_AUDIO_CAPTURE);
            controller.requestWebPermission(camera);
            controller.requestWebPermission(mic);
            if (destroy) controller.destroy(); else controller.navigationStarted();
            assertTrue(camera.denied);
            assertTrue(mic.denied);
            Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
            controller.permissionsResult(SmartTriggerController.WEB_PERMISSION_REQUEST);
            assertNull(camera.granted);
            assertNull(mic.granted);
            controller.destroy();
            Shadows.shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        }
    }

    @Test public void unresolvedMediaPermissionFailsWithinItsDeadline() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SmartTriggerController controller = controller(activity);
        Request camera = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        Request mic = new Request("https://www.digipalsignage.com", PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        controller.requestWebPermission(camera);
        controller.requestWebPermission(mic);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        assertTrue(camera.denied);
        assertTrue(mic.denied);
        controller.destroy();
    }
}
