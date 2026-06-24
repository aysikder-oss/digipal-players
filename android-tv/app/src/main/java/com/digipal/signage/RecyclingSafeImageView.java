package com.digipal.signage;

  import android.content.Context;
  import android.graphics.Canvas;
  import android.util.Log;
  import android.widget.ImageView;

  /**
   * ImageView that swallows RuntimeException during draw so a recycled-bitmap
   * race condition during rapid playlist image transitions does not crash the app.
   * Inspired by OptiSigns' ImageViewRecyclable pattern.
   */
  public class RecyclingSafeImageView extends ImageView {

      private static final String TAG = "DigipalImageView";

      public RecyclingSafeImageView(Context context) {
          super(context);
      }

      @Override
      protected void onDraw(Canvas canvas) {
          try {
              super.onDraw(canvas);
          } catch (RuntimeException e) {
              // Swallow recycled-bitmap RuntimeException — happens when Glide
              // recycles a bitmap that is still being drawn during a transition.
              Log.w(TAG, "onDraw suppressed (recycled bitmap): " + e.getMessage());
          }
      }
  }
  