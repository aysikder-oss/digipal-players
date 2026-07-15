package com.nexuscast.player;

  import android.content.Context;
  import android.graphics.Canvas;
  import android.util.AttributeSet;
  import android.util.Log;
  import androidx.appcompat.widget.AppCompatImageView;

  public class RecyclingSafeImageView extends AppCompatImageView {
      private static final String TAG = "DigipalImageView";

      public RecyclingSafeImageView(Context context) {
          super(context);
      }

      public RecyclingSafeImageView(Context context, AttributeSet attrs) {
          super(context, attrs);
      }

      public RecyclingSafeImageView(Context context, AttributeSet attrs, int defStyleAttr) {
          super(context, attrs, defStyleAttr);
      }

      @Override
      protected void onDraw(Canvas canvas) {
          try {
              super.onDraw(canvas);
          } catch (RuntimeException e) {
              Log.w(TAG, "onDraw suppressed (recycled bitmap): " + e.getMessage());
          }
      }

      @Override
      protected void onDetachedFromWindow() {
          try {
              setImageDrawable(null);
          } catch (Throwable ignored) {}
          super.onDetachedFromWindow();
      }
  }
  