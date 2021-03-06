package com.bumptech.glide.request;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.util.Pools;
import android.util.Log;

import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.Engine;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.request.transition.TransitionFactory;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import com.bumptech.glide.util.pool.FactoryPools;
import com.bumptech.glide.util.pool.StateVerifier;

/**
 * A {@link Request} that loads a {@link com.bumptech.glide.load.engine.Resource} into a given
 * {@link Target}.
 *
 * @param <R> The type of the resource that will be transcoded from the loaded resource.
 */
// 图片资源回调 尺寸获得即绘制完成后回调 回调
public final class SingleRequest<R> implements Request,
        SizeReadyCallback,
        ResourceCallback,
        FactoryPools.Poolable {
    /**
     * Tag for logging internal events, not generally suitable for public use.
     */
    private static final String TAG = "Request";
    /**
     * Tag for logging externally useful events (request completion, timing etc).
     */
    private static final String GLIDE_TAG = "Glide";
    private static final Pools.Pool<SingleRequest<?>> POOL = FactoryPools.simple(150,
            new FactoryPools.Factory<SingleRequest<?>>() {
                @Override
                public SingleRequest<?> create() {
                    return new SingleRequest<Object>();
                }
            });

    private enum Status {
        /**
         * Created but not yet running.
         */
        PENDING,
        /**
         * In the process of fetching media.
         */
        RUNNING,
        /**
         * Waiting for a callback given to the Target to be called to determine target dimensions.
         */
        WAITING_FOR_SIZE,
        /**
         * Finished loading media successfully.
         */
        COMPLETE,
        /**
         * Failed to load media, may be restarted.
         */
        FAILED,
        /**
         * Cancelled by the user, may not be restarted.
         */
        CANCELLED,
        /**
         * Cleared by the user with a placeholder set, may not be restarted.
         */
        CLEARED,
        /**
         * Temporarily paused by the system, may be restarted.
         */
        PAUSED,
    }

    private final String tag = String.valueOf(hashCode());
    private final StateVerifier stateVerifier = StateVerifier.newInstance();

    private RequestCoordinator requestCoordinator;
    private GlideContext glideContext;
    private Object model;
    private Class<R> transcodeClass;
    private BaseRequestOptions<?> requestOptions;
    private int overrideWidth;
    private int overrideHeight;
    private Priority priority;
    private Target<R> target;
    private RequestListener<R> requestListener;
    private Engine engine;
    private TransitionFactory<? super R> animationFactory;
    private Resource<R> resource;
    private Engine.LoadStatus loadStatus;
    private long startTime;
    private Status status;
    private Drawable errorDrawable;
    private Drawable placeholderDrawable;
    private Drawable fallbackDrawable;
    private int width;
    private int height;

    public static <R> SingleRequest<R> obtain(
            GlideContext glideContext,
            Object model,
            Class<R> transcodeClass,
            BaseRequestOptions<?> requestOptions,
            int overrideWidth,
            int overrideHeight,
            Priority priority,
            Target<R> target,
            RequestListener<R> requestListener,
            RequestCoordinator requestCoordinator,
            Engine engine,
            TransitionFactory<? super R> animationFactory) {
        @SuppressWarnings("unchecked") SingleRequest<R> request =//的到未初始化的SingleRequest
                (SingleRequest<R>) POOL.acquire();
        if (request == null) {
            request = new SingleRequest<>();
        }
        request.init(
                glideContext,
                model,
                transcodeClass,
                requestOptions,
                overrideWidth,
                overrideHeight,
                priority,
                target,
                requestListener,
                requestCoordinator,
                engine,
                animationFactory);
        return request;
    }

    @Synthetic
    SingleRequest() {
        // just create, instances are reused with recycle/init
    }

    private void init(
            GlideContext glideContext,
            Object model,
            Class<R> transcodeClass,
            BaseRequestOptions<?> requestOptions,
            int overrideWidth,
            int overrideHeight,
            Priority priority,
            Target<R> target,
            RequestListener<R> requestListener,
            RequestCoordinator requestCoordinator,
            Engine engine,
            TransitionFactory<? super R> animationFactory) {
        this.glideContext = glideContext;
        this.model = model;
        this.transcodeClass = transcodeClass;
        this.requestOptions = requestOptions;
        this.overrideWidth = overrideWidth;
        this.overrideHeight = overrideHeight;
        this.priority = priority;
        this.target = target;
        this.requestListener = requestListener;
        this.requestCoordinator = requestCoordinator;
        this.engine = engine;
        this.animationFactory = animationFactory;
        status = Status.PENDING;
    }

    @Override
    public StateVerifier getVerifier() {
        return stateVerifier;
    }

    @Override
    public void recycle() {
        glideContext = null;
        model = null;
        transcodeClass = null;
        requestOptions = null;
        overrideWidth = -1;
        overrideHeight = -1;
        target = null;
        requestListener = null;
        requestCoordinator = null;
        animationFactory = null;
        loadStatus = null;
        errorDrawable = null;
        placeholderDrawable = null;
        fallbackDrawable = null;
        width = -1;
        height = -1;
        POOL.release(this);
    }

    @Override
    public void begin() {
        stateVerifier.throwIfRecycled();// 检测状态异常
        startTime = LogTime.getLogTime(); // 记录当前时间为开始时间
        if (model == null) {
            if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
                width = overrideWidth;
                height = overrideHeight;
            }
            // Only log at more verbose log levels if the user has set a fallback drawable, because
            // fallback Drawables indicate the user expects null models occasionally.
            //下载失败图片
            int logLevel = getFallbackDrawable() == null ? Log.WARN : Log.DEBUG;
            onLoadFailed(new GlideException("Received null model"), logLevel);
            return;
        }
// 最终的都要执行onSizeReday  图片请求在这个方法里
        status = Status.WAITING_FOR_SIZE;// onSizeReady  里有异步线程 这里设置 等待 使view 先放置占位图片
        if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
            // 有尺寸进入 onSizeRady方法
            onSizeReady(overrideWidth, overrideHeight);
        } else {
            target.getSize(this); // 如果尺寸为空 就去获得默认尺寸 再回到 onSizeReady方法
        }

        if ((status == Status.RUNNING || status == Status.WAITING_FOR_SIZE)// 等待尺寸或运行且 未完成（canNotifyStatusChanged缩略图请求为空 或未完成 都返回ture）
                && canNotifyStatusChanged()) {//  等待请求时 放置占位图片
            //占位图片
            target.onLoadStarted(getPlaceholderDrawable());// 从图片设置属性类里 获得占位图片
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished run method in " + LogTime.getElapsedMillis(startTime));
        }
    }

    /**
     * Cancels the current load but does not release any resources held by the request and continues
     * to display the loaded resource if the load completed before the call to cancel.
     * <p>
     * <p> Cancelled requests can be restarted with a subsequent call to {@link #begin()}. </p>
     *
     * @see #clear()
     */
    void cancel() {
        stateVerifier.throwIfRecycled();
        status = Status.CANCELLED;
        if (loadStatus != null) {
            loadStatus.cancel();
            loadStatus = null;
        }
    }

    /**
     * Cancels the current load if it is in progress, clears any resources held onto by the request
     * and replaces the loaded resource if the load completed with the placeholder.
     * <p>
     * <p> Cleared requests can be restarted with a subsequent call to {@link #begin()} </p>
     *
     * @see #cancel()
     */
    @Override
    public void clear() {
        Util.assertMainThread();
        if (status == Status.CLEARED) {
            return;
        }
        cancel();
        // Resource must be released before canNotifyStatusChanged is called.
        if (resource != null) {
            releaseResource(resource);
        }
        if (canNotifyStatusChanged()) {
            target.onLoadCleared(getPlaceholderDrawable());
        }
        // Must be after cancel().
        status = Status.CLEARED;
    }

    @Override
    public boolean isPaused() {
        return status == Status.PAUSED;
    }

    @Override
    public void pause() {
        clear();
        status = Status.PAUSED;
    }

    private void releaseResource(Resource<?> resource) {
        engine.release(resource);
        this.resource = null;
    }

    @Override
    public boolean isRunning() {
        return status == Status.RUNNING || status == Status.WAITING_FOR_SIZE;
    }

    @Override
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    @Override
    public boolean isResourceSet() {
        return isComplete();
    }

    @Override
    public boolean isCancelled() {
        return status == Status.CANCELLED || status == Status.CLEARED;
    }

    @Override
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    private Drawable getErrorDrawable() {
        if (errorDrawable == null) {
            errorDrawable = requestOptions.getErrorPlaceholder();
            if (errorDrawable == null && requestOptions.getErrorId() > 0) {
                errorDrawable = loadDrawable(requestOptions.getErrorId());
            }
        }
        return errorDrawable;
    }

    private Drawable getPlaceholderDrawable() {
        if (placeholderDrawable == null) {
            placeholderDrawable = requestOptions.getPlaceholderDrawable();
            if (placeholderDrawable == null && requestOptions.getPlaceholderId() > 0) {
                placeholderDrawable = loadDrawable(requestOptions.getPlaceholderId());
            }
        }
        return placeholderDrawable;
    }

    private Drawable getFallbackDrawable() {
        if (fallbackDrawable == null) {
            fallbackDrawable = requestOptions.getFallbackDrawable();
            if (fallbackDrawable == null && requestOptions.getFallbackId() > 0) {
                fallbackDrawable = loadDrawable(requestOptions.getFallbackId());
            }
        }
        return fallbackDrawable;
    }

    private Drawable loadDrawable(int resourceId) {
        Resources resources = glideContext.getResources();
        return ResourcesCompat.getDrawable(resources, resourceId, requestOptions.getTheme());
    }

    private void setErrorPlaceholder() {
        if (!canNotifyStatusChanged()) {
            return;
        }
// 如果资源或链接为空 就 获得   从图片属性设置里拿  下载错误图片 或者备用图片  链接或资源 空就获得备用图片 不空就设置错误图片
        Drawable error = model == null ? getFallbackDrawable() : getErrorDrawable();
        if (error == null) {
            error = getPlaceholderDrawable();
        }
        target.onLoadFailed(error);
    }

    /**
     * A callback method that should never be invoked directly.
     */
    @Override
    public void onSizeReady(int width, int height) {
        stateVerifier.throwIfRecycled();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("Got onSizeReady in " + LogTime.getElapsedMillis(startTime));
        }
        if (status != Status.WAITING_FOR_SIZE) {
            return;
        }
        status = Status.RUNNING; // 将状态设置成正在运行

        float sizeMultiplier = requestOptions.getSizeMultiplier();
        this.width = maybeApplySizeMultiplier(width, sizeMultiplier);
        this.height = maybeApplySizeMultiplier(height, sizeMultiplier);

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished setup for calling load in " + LogTime.getElapsedMillis(startTime));
        }
        //engine 在glide 初始胡时加入的
        loadStatus = engine.load(
                glideContext,
                model,
                requestOptions.getSignature(),
                this.width,
                this.height,
                requestOptions.getResourceClass(),
                transcodeClass,
                priority,
                requestOptions.getDiskCacheStrategy(),
                requestOptions.getTransformations(),
                requestOptions.isTransformationRequired(),
                requestOptions.getOptions(),
                requestOptions.isMemoryCacheable(),
                requestOptions.getUseUnlimitedSourceGeneratorsPool(),
                requestOptions.getOnlyRetrieveFromCache(),
                this);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished onSizeReady in " + LogTime.getElapsedMillis(startTime));
        }
    }

    private static int maybeApplySizeMultiplier(int size, float sizeMultiplier) {
        return size == Target.SIZE_ORIGINAL ? size : Math.round(sizeMultiplier * size);
    }

    private boolean canSetResource() {
        return requestCoordinator == null || requestCoordinator.canSetImage(this);
    }

    private boolean canNotifyStatusChanged() {
        return requestCoordinator == null || requestCoordinator.canNotifyStatusChanged(this);
    }

    private boolean isFirstReadyResource() {
        return requestCoordinator == null || !requestCoordinator.isAnyResourceSet();
    }

    private void notifyLoadSuccess() {
        if (requestCoordinator != null) {
            requestCoordinator.onRequestSuccess(this);// 将携带 数据的 SingleRequest 对象回调到ThumbnailRequestCoordinator
        }
    }

    /**
     * A callback method that should never be invoked directly.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onResourceReady(Resource<?> resource, DataSource dataSource) {
        //返回的Bitmap 最终封装成的EngineResource
        stateVerifier.throwIfRecycled();
        loadStatus = null;
        if (resource == null) {
            GlideException exception = new GlideException("Expected to receive a Resource<R> with an "
                    + "object of " + transcodeClass + " inside, but instead got null.");
            onLoadFailed(exception);
            return;
        }

        Object received = resource.get();//得到bitmap
        if (received == null || !transcodeClass.isAssignableFrom(received.getClass())) {
            releaseResource(resource);
            GlideException exception = new GlideException("Expected to receive an object of "
                    + transcodeClass + " but instead" + " got "
                    + (received != null ? received.getClass() : "") + "{" + received + "} inside" + " "
                    + "Resource{" + resource + "}."
                    + (received != null ? "" : " " + "To indicate failure return a null Resource "
                    + "object, rather than a Resource object containing null data."));
            onLoadFailed(exception);
            return;
        }

        if (!canSetResource()) {  //放置 完成状态 要询问  缩略图请求
            releaseResource(resource);
            // We can't put the status to complete before asking canSetResource().
            status = Status.COMPLETE;
            return;
        }

        onResourceReady((Resource<R>) resource, (R) received, dataSource);// 进入此方法 最终封装成的EngineResource 第二个是bitmap 原始未封装的
    }

    /**
     * Internal {@link #onResourceReady(Resource, DataSource)} where arguments are known to be safe.
     *
     * @param resource original {@link Resource}, never <code>null</code>
     * @param result   object returned by {@link Resource#get()}, checked for type and never
     *                 <code>null</code>
     */
    private void onResourceReady(Resource<R> resource, R result, DataSource dataSource) { //最第一个 终封装成的EngineResource 和第二个是bitmap 原始未封装的
        // We must call isFirstReadyResource before setting status.
        boolean isFirstResource = isFirstReadyResource();// 是否是第一此返回的资源  status = Status.COMPLETE;  还未设置
        status = Status.COMPLETE;// 获得资源 设置成完成状态
        this.resource = resource;

        if (glideContext.getLogLevel() <= Log.DEBUG) {
            Log.d(GLIDE_TAG, "Finished loading " + result.getClass().getSimpleName() + " from "
                    + dataSource + " for " + model + " with size [" + width + "x" + height + "] in "
                    + LogTime.getElapsedMillis(startTime) + " ms");
        }

        if (requestListener == null
                || !requestListener.onResourceReady(result, model, target, dataSource, isFirstResource)) {// 两种回调 需回调一种  一个是自己设置的继承监听回调 自己处理 一种是默认处理
            // 自己处理 也可返回 false 表示自己没处理
            Transition<? super R> animation =// 涉及到动画
                    animationFactory.build(dataSource, isFirstResource);
            target.onResourceReady(result, animation); //  回调 数据 到 targetView  将图片 放到对应的ImageView
        }

        notifyLoadSuccess();// 进入此方法  通知缩略图请求
    }

    /**
     * A callback method that should never b
     * e invoked directly.
     */
    @Override
    public void onLoadFailed(GlideException e) {
        onLoadFailed(e, Log.WARN);
    }

    private void onLoadFailed(GlideException e, int maxLogLevel) {
        stateVerifier.throwIfRecycled();
        int logLevel = glideContext.getLogLevel();
        if (logLevel <= maxLogLevel) {
            Log.w(GLIDE_TAG, "Load failed for " + model + " with size [" + width + "x" + height + "]", e);
            if (logLevel <= Log.INFO) {
                e.logRootCauses(GLIDE_TAG);
            }
        }

        loadStatus = null;
        status = Status.FAILED;// 下载失败将 失败标记赋予 状态字段
        //TODO: what if this is a thumbnail request?
        if (requestListener == null || !requestListener.onLoadFailed(e, model, target,
                isFirstReadyResource())) {// 如果自己对下载错误回调进行处理返回 ture 这里不再处理 如果返回false  这里 进行放置错误图片
            setErrorPlaceholder();
        }
    }

    private void logV(String message) {
        Log.v(TAG, message + " this: " + tag);
    }
}
