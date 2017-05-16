package com.bumptech.glide.request.target;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.support.annotation.Nullable;
import android.widget.ImageView;
import com.bumptech.glide.request.transition.Transition;

/**
 * A base {@link com.bumptech.glide.request.target.Target} for displaying resources in {@link
 * android.widget.ImageView}s.
 *
 * @param <Z> The type of resource that this target will display in the wrapped {@link
 *            android.widget.ImageView}.
 */
public abstract class ImageViewTarget<Z> extends ViewTarget<ImageView, Z>
    implements Transition.ViewAdapter {

  @Nullable
  private Animatable animatable;

  public ImageViewTarget(ImageView view) {
    super(view);
  }

  /**
   * Returns the current {@link android.graphics.drawable.Drawable} being displayed in the view
   * using {@link android.widget.ImageView#getDrawable()}.
   */
  @Override
  @Nullable
  public Drawable getCurrentDrawable() {
    return view.getDrawable();
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param drawable {@inheritDoc}
   */
  @Override
  public void setDrawable(Drawable drawable) {
    view.setImageDrawable(drawable);
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param placeholder {@inheritDoc}
   */
  @Override
  public void onLoadStarted(@Nullable Drawable placeholder) {
    super.onLoadStarted(placeholder);
    setResourceInternal(null);// 先将原先view 上的 图片缓存置空
    setDrawable(placeholder);// 往view 里放置view
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param errorDrawable {@inheritDoc}
   */
  @Override
  public void onLoadFailed(@Nullable Drawable errorDrawable) {
    super.onLoadFailed(errorDrawable);
    setResourceInternal(null);
    setDrawable(errorDrawable);
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param placeholder {@inheritDoc}
   */
  @Override
  public void onLoadCleared(@Nullable Drawable placeholder) {
    super.onLoadCleared(placeholder);
    setResourceInternal(null);
    setDrawable(placeholder);
  }

  @Override
  public void onResourceReady(Z resource, @Nullable Transition<? super Z> transition) {
    if (transition == null || !transition.transition(resource, this)) {// 一种动画处理一种不动画处理
      setResourceInternal(resource);// 这里设置图片 resource这里是bitmap 原始未封装的
    } else {
      maybeUpdateAnimatable(resource);//  这里不设置图片
    }
  }

  @Override
  public void onStart() {
    if (animatable != null) {
      animatable.start();
    }
  }

  @Override
  public void onStop() {
    if (animatable != null) {
      animatable.stop();
    }
  }

  private void setResourceInternal(@Nullable Z resource) {
    maybeUpdateAnimatable(resource);
    setResource(resource);
  }

  private void maybeUpdateAnimatable(@Nullable Z resource) {
    if (resource instanceof Animatable) {
      animatable = (Animatable) resource;
      animatable.start(); // 开始动画
    } else {
      animatable = null;
    }
  }

  protected abstract void setResource(@Nullable Z resource);
}

