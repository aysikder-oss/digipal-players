package com.digipal.signage;

import android.app.ActivityManager;
import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Custom GlideModule with RAM-adaptive image cache.
 *
 * Cache size scales with the device's heap class so low-memory devices
 * (Fire TV Stick: ~256 MB heap) don't burn 48 MB on image thumbnails:
 *   ≤256 MB heap → 2 screens (~16 MB)  Fire TV Stick 2nd/3rd gen
 *   ≤512 MB heap → 3 screens (~24 MB)  Fire TV 4K / mid-range
 *   >512 MB heap → 6 screens (~48 MB)  Shield / high-end
 *
 * Previously hardcoded to 6 screens for all devices (technique from OptiSigns).
 */
@GlideModule
public final class DigipalGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int memClass = (am != null) ? am.getMemoryClass() : 256;
        int screens  = memClass <= 256 ? 2 : memClass <= 512 ? 3 : 6;
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(screens)
                .build();
        builder.setMemorySizeCalculator(calculator);
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // Disable legacy V3 manifest scanning; use @GlideModule annotation only.
        return false;
    }
}
