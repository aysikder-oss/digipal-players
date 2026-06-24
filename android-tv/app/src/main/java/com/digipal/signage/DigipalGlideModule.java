package com.digipal.signage;

  import android.content.Context;
  import androidx.annotation.NonNull;
  import com.bumptech.glide.GlideBuilder;
  import com.bumptech.glide.annotation.GlideModule;
  import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
  import com.bumptech.glide.module.AppGlideModule;

  /**
   * Custom GlideModule that keeps 6 full-screen bitmaps in RAM.
   * Default Glide cache (~1/8 of free memory) can hold only 1-2 TV-resolution
   * frames; 6 screens eliminates re-decode flashes when a playlist loops back.
   * Technique from OptiSigns setMemoryCacheScreens(6).
   */
  @GlideModule
  public final class DigipalGlideModule extends AppGlideModule {

      @Override
      public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
          MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                  .setMemoryCacheScreens(6)
                  .build();
          builder.setMemorySizeCalculator(calculator);
      }

      @Override
      public boolean isManifestParsingEnabled() {
          // Disable legacy V3 manifest scanning; use @GlideModule annotation only.
          return false;
      }
  }
  