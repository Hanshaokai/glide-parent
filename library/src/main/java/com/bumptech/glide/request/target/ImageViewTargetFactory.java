package com.bumptech.glide.request.target;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * A factory responsible for producing the correct type of
 * {@link com.bumptech.glide.request.target.Target} for a given {@link android.view.View} subclass.
 */
public class ImageViewTargetFactory {

    @SuppressWarnings("unchecked")
    public <Z> Target<Z> buildTarget(ImageView view, Class<Z> clazz) {
        if (Bitmap.class.equals(clazz)) {// Bitmap 类型 或 Drawable 类型 只能为 drawable 类型
            return (Target<Z>) new BitmapImageViewTarget(view);
        } else if (Drawable.class.isAssignableFrom(clazz)) {

            return (Target<Z>) new DrawableImageViewTarget(view);
        } else {
            throw new IllegalArgumentException(
                    "Unhandled class: " + clazz + ", try .as*(Class).transcode(ResourceTranscoder)");
        }
    }
}
