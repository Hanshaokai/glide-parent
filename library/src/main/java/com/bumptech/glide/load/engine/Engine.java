package com.bumptech.glide.load.engine;

import android.os.Looper;
import android.os.MessageQueue;
import android.support.v4.util.Pools;
import android.util.Log;

import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import com.bumptech.glide.util.pool.FactoryPools;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Responsible for starting loads and managing active and cached resources.
 */
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {
    private static final String TAG = "Engine";
    private static final int JOB_POOL_SIZE = 150;
    private final Map<Key, EngineJob<?>> jobs;
    private final EngineKeyFactory keyFactory;
    private final MemoryCache cache;
    private final EngineJobFactory engineJobFactory;
    private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
    private final ResourceRecycler resourceRecycler;
    private final LazyDiskCacheProvider diskCacheProvider;
    private final DecodeJobFactory decodeJobFactory;

    // Lazily instantiate to avoid exceptions if Glide is initialized on a background thread. See
    // #295.
    private ReferenceQueue<EngineResource<?>> resourceReferenceQueue;

    /**
     * Allows a request to indicate it no longer is interested in a given load.
     */
    public static class LoadStatus {
        private final EngineJob<?> engineJob;
        private final ResourceCallback cb;

        public LoadStatus(ResourceCallback cb, EngineJob<?> engineJob) {
            this.cb = cb;
            this.engineJob = engineJob;
        }

        public void cancel() {
            engineJob.removeCallback(cb);
        }
    }

    public Engine(MemoryCache memoryCache,
                  DiskCache.Factory diskCacheFactory,
                  GlideExecutor diskCacheExecutor,
                  GlideExecutor sourceExecutor,
                  GlideExecutor sourceUnlimitedExecutor) {
        this(memoryCache, diskCacheFactory, diskCacheExecutor, sourceExecutor, sourceUnlimitedExecutor,
                null, null, null, null, null, null);
    }

    // Visible for testing.
    Engine(MemoryCache cache,
           DiskCache.Factory diskCacheFactory,
           GlideExecutor diskCacheExecutor,
           GlideExecutor sourceExecutor,
           GlideExecutor sourceUnlimitedExecutor,
           Map<Key, EngineJob<?>> jobs,
           EngineKeyFactory keyFactory,
           Map<Key, WeakReference<EngineResource<?>>> activeResources,
           EngineJobFactory engineJobFactory,
           DecodeJobFactory decodeJobFactory,
           ResourceRecycler resourceRecycler) {
        this.cache = cache;
        this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

        if (activeResources == null) {
            activeResources = new HashMap<>();
        }
        this.activeResources = activeResources;

        if (keyFactory == null) {
            keyFactory = new EngineKeyFactory();
        }
        this.keyFactory = keyFactory;

        if (jobs == null) {
            jobs = new HashMap<>();
        }
        this.jobs = jobs;

        if (engineJobFactory == null) {
            engineJobFactory = new EngineJobFactory(diskCacheExecutor, sourceExecutor,
                    sourceUnlimitedExecutor, this);
        }
        this.engineJobFactory = engineJobFactory;

        if (decodeJobFactory == null) {
            decodeJobFactory = new DecodeJobFactory(diskCacheProvider);
        }
        this.decodeJobFactory = decodeJobFactory;

        if (resourceRecycler == null) {
            resourceRecycler = new ResourceRecycler();
        }
        this.resourceRecycler = resourceRecycler;

        cache.setResourceRemovedListener(this);
    }

    /**
     * Starts a load for the given arguments. Must be called on the main thread.
     * <p>
     * <p> The flow for any request is as follows: <ul> <li>Check the memory cache and provide the
     * cached resource if present</li> <li>Check the current put of actively used resources and return
     * the active resource if present</li> <li>Check the current put of in progress loads and add the
     * cb to the in progress load if present</li> <li>Start a new load</li> </ul> </p>
     * <p>
     * <p> Active resources are those that have been provided to at least one request and have not yet
     * been released. Once all consumers of a resource have released that resource, the resource then
     * goes to cache. If the resource is ever returned to a new consumer from cache, it is re-added to
     * the active resources. If the resource is evicted from the cache, its resources are recycled and
     * re-used if possible and the resource is discarded. There is no strict requirement that
     * consumers release their resources so active resources are held weakly. </p>
     *
     * @param width  The target width in pixels of the desired resource.
     * @param height The target height in pixels of the desired resource.
     * @param cb     The callback that will be called when the load completes.
     */
    public <R> LoadStatus load(
            GlideContext glideContext,
            Object model,
            Key signature,
            int width,
            int height,
            Class<?> resourceClass,
            Class<R> transcodeClass,
            Priority priority,
            DiskCacheStrategy diskCacheStrategy,
            Map<Class<?>, Transformation<?>> transformations,
            boolean isTransformationRequired,
            Options options,
            boolean isMemoryCacheable,
            boolean useUnlimitedSourceExecutorPool,
            boolean onlyRetrieveFromCache,
            ResourceCallback cb) {//ResourceCallback 回调 singleRequest实现
        Util.assertMainThread();
        long startTime = LogTime.getLogTime();

        EngineKey key = keyFactory.buildKey(model, signature, width, height, transformations,
                resourceClass, transcodeClass, options);
// 涉及缓存  这里两种缓存 后面 细致探究
        EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);// 从缓存中获取
        if (cached != null) {
            cb.onResourceReady(cached, DataSource.MEMORY_CACHE);// 从缓存中取得 回调到 singleRequest 中
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                //从缓存中获取
                logWithTimeAndKey("Loaded resource from cache", startTime, key);
            }
            return null;
        }
        //  磁盘缓存 若删除了 就从活性缓存中获取
        EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
        if (active != null) {
            cb.onResourceReady(active, DataSource.MEMORY_CACHE);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from active resources", startTime, key);
            }
            return null;
        }

        EngineJob<?> current = jobs.get(key); // 从集合中拿 集合中没有 走下面 构造    引擎中此引擎任务 是否之前已加入 加入就不走了
        if (current != null) {
            current.addCallback(cb);// 接着把 SIngleRequest的对象放入 EnginJob 中  在EnginJob中调用 SingleRequest 中的加载完成的方法
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Added to existing load", startTime, key);
            }
            return new LoadStatus(cb, current); // 走这里 没有了下载过程
        }
// 线程涉及到了  开启线程为下载图片做准备
        EngineJob<R> engineJob = engineJobFactory.build(key, isMemoryCacheable,
                useUnlimitedSourceExecutorPool);
        DecodeJob<R> decodeJob = decodeJobFactory.build(
                glideContext,
                model,
                key,
                signature,
                width,
                height,
                resourceClass,
                transcodeClass,
                priority,
                diskCacheStrategy,
                transformations,
                isTransformationRequired,
                onlyRetrieveFromCache,
                options,
                engineJob);
        jobs.put(key, engineJob);// 加到集合缓存
        //回调是否成功
        engineJob.addCallback(cb); // 回调 是加到缓存后再单独加的
        engineJob.start(decodeJob);// 进入下载过程

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Started new load", startTime, key);
        }
        return new LoadStatus(cb, engineJob);
    }

    private static void logWithTimeAndKey(String log, long startTime, Key key) {
        Log.v(TAG, log + " in " + LogTime.getElapsedMillis(startTime) + "ms, key: " + key);
    }

    private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {
            return null;
        }

        EngineResource<?> active = null;
        WeakReference<EngineResource<?>> activeRef = activeResources.get(key);
        if (activeRef != null) {
            active = activeRef.get();
            if (active != null) {
                active.acquire();
            } else {
                activeResources.remove(key);
            }
        }

        return active;
    }

    /**
     *
     * @param key
     * @param isMemoryCacheable  是否跳过skip  默认false
     * @return
     */
    private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {
            return null;
        }

        EngineResource<?> cached = getEngineResourceFromCache(key); // 并做了删除
        if (cached != null) {
            cached.acquire();// 放到活性缓存中 存储到一个activeResources 中 就是一个弱引用的HashMAp 用来缓存正在使用
            // 使用中的 图片  使用activeResource 来缓存正在使用中的图片  可以保护这些图片不会被 LruCache 算法回收掉
            activeResources.put(key, new ResourceWeakReference(key, cached, getReferenceQueue()));
        }
        return cached;
    }

    @SuppressWarnings("unchecked")
    private EngineResource<?> getEngineResourceFromCache(Key key) {
        Resource<?> cached = cache.remove(key);//LruResourceCache 初始化的cache  从缓存中删除

        final EngineResource<?> result;
        if (cached == null) {
            result = null;
        } else if (cached instanceof EngineResource) {
            // Save an object allocation if we've cached an EngineResource (the typical case).
            result = (EngineResource<?>) cached;
        } else {
            result = new EngineResource<>(cached, true /*isMemoryCacheable*/);
        }
        return result;
    }

    public void release(Resource<?> resource) {
        Util.assertMainThread();
        if (resource instanceof EngineResource) {
            ((EngineResource<?>) resource).release();
        } else {
            throw new IllegalArgumentException("Cannot release anything but an EngineResource");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onEngineJobComplete(Key key, EngineResource<?> resource) {
        Util.assertMainThread();//主线程调用
        // A null resource indicates that the load failed, usually due to an exception.
        if (resource != null) {
            resource.setResourceListener(key, this);//往其中加监听

            if (resource.isCacheable()) {  // 进行缓存  这里是弱引用 缓存
                activeResources.put(key, new ResourceWeakReference(key, resource, getReferenceQueue()));
            }
        }
        // TODO: should this check that the engine job is still current?
        jobs.remove(key);
    }

    @Override
    public void onEngineJobCancelled(EngineJob engineJob, Key key) {
        Util.assertMainThread();
        EngineJob<?> current = jobs.get(key);
        if (engineJob.equals(current)) {
            jobs.remove(key);
        }
    }

    @Override
    public void onResourceRemoved(final Resource<?> resource) {
        Util.assertMainThread();
        resourceRecycler.recycle(resource);
    }

    @Override
    public void onResourceReleased(Key cacheKey, EngineResource resource) {
        Util.assertMainThread();
        activeResources.remove(cacheKey);
        // 现将缓存 从 activieResourse中移除 然后再将它 put LURResoureseCache中
        // 这样也就实现了 正在使用中的图片使用软引用来进行缓存 不在使用的图片使用lureCacheel
        //来进行缓存的功能  这就是内存缓存的原理
        if (resource.isCacheable()) {
            cache.put(cacheKey, resource);
        } else {
            resourceRecycler.recycle(resource);
        }
    }

    public void clearDiskCache() {
        diskCacheProvider.getDiskCache().clear();
    }

    private ReferenceQueue<EngineResource<?>> getReferenceQueue() {
        if (resourceReferenceQueue == null) {
            resourceReferenceQueue = new ReferenceQueue<>();
            MessageQueue queue = Looper.myQueue();
            queue.addIdleHandler(new RefQueueIdleHandler(activeResources, resourceReferenceQueue));
        }
        return resourceReferenceQueue;
    }

    private static class LazyDiskCacheProvider implements DecodeJob.DiskCacheProvider {

        private final DiskCache.Factory factory;
        private volatile DiskCache diskCache;

        public LazyDiskCacheProvider(DiskCache.Factory factory) {
            this.factory = factory;
        }

        @Override
        public DiskCache getDiskCache() {
            if (diskCache == null) {
                synchronized (this) {
                    if (diskCache == null) {
                        diskCache = factory.build();
                    }
                    if (diskCache == null) {
                        diskCache = new DiskCacheAdapter();
                    }
                }
            }
            return diskCache;
        }
    }

    private static class ResourceWeakReference extends WeakReference<EngineResource<?>> {
        @Synthetic
        final Key key;

        public ResourceWeakReference(Key key, EngineResource<?> r,
                                     ReferenceQueue<? super EngineResource<?>> q) {
            super(r, q);
            this.key = key;
        }
    }

    // Responsible for cleaning up the active resource map by remove weak references that have been
    // cleared.
    private static class RefQueueIdleHandler implements MessageQueue.IdleHandler {
        private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
        private final ReferenceQueue<EngineResource<?>> queue;

        public RefQueueIdleHandler(Map<Key, WeakReference<EngineResource<?>>> activeResources,
                                   ReferenceQueue<EngineResource<?>> queue) {
            this.activeResources = activeResources;
            this.queue = queue;
        }

        @Override
        public boolean queueIdle() {
            ResourceWeakReference ref = (ResourceWeakReference) queue.poll();
            if (ref != null) {
                activeResources.remove(ref.key);
            }

            return true;
        }
    }

    // Visible for testing.
    static class DecodeJobFactory {
        @Synthetic
        final DecodeJob.DiskCacheProvider diskCacheProvider;
        @Synthetic
        final Pools.Pool<DecodeJob<?>> pool = FactoryPools.simple(JOB_POOL_SIZE,
                new FactoryPools.Factory<DecodeJob<?>>() {
                    @Override
                    public DecodeJob<?> create() {
                        return new DecodeJob<Object>(diskCacheProvider, pool);
                    }
                });
        private int creationOrder;

        DecodeJobFactory(DecodeJob.DiskCacheProvider diskCacheProvider) {
            this.diskCacheProvider = diskCacheProvider;
        }

        @SuppressWarnings("unchecked")
        <R> DecodeJob<R> build(GlideContext glideContext,
                               Object model,
                               EngineKey loadKey,
                               Key signature,
                               int width,
                               int height,
                               Class<?> resourceClass,
                               Class<R> transcodeClass,
                               Priority priority,
                               DiskCacheStrategy diskCacheStrategy,
                               Map<Class<?>, Transformation<?>> transformations,
                               boolean isTransformationRequired,
                               boolean onlyRetrieveFromCache,
                               Options options,
                               DecodeJob.Callback<R> callback) {
            DecodeJob<R> result = (DecodeJob<R>) pool.acquire();
            return result.init(
                    glideContext,
                    model,
                    loadKey,
                    signature,
                    width,
                    height,
                    resourceClass,
                    transcodeClass,
                    priority,
                    diskCacheStrategy,
                    transformations,
                    isTransformationRequired,
                    onlyRetrieveFromCache,
                    options,
                    callback,
                    creationOrder++);
        }
    }

    // Visible for testing.
    static class EngineJobFactory {
        @Synthetic
        final GlideExecutor diskCacheExecutor;
        @Synthetic
        final GlideExecutor sourceExecutor;
        @Synthetic
        final GlideExecutor sourceUnlimitedExecutor;
        @Synthetic
        final EngineJobListener listener;
        @Synthetic
        final Pools.Pool<EngineJob<?>> pool = FactoryPools.simple(JOB_POOL_SIZE,
                new FactoryPools.Factory<EngineJob<?>>() {
                    @Override
                    public EngineJob<?> create() {
                        return new EngineJob<Object>(diskCacheExecutor, sourceExecutor, sourceUnlimitedExecutor,
                                listener, pool);
                    }
                });

        EngineJobFactory(GlideExecutor diskCacheExecutor, GlideExecutor sourceExecutor,
                         GlideExecutor sourceUnlimitedExecutor, EngineJobListener listener) {
            this.diskCacheExecutor = diskCacheExecutor;
            this.sourceExecutor = sourceExecutor;
            this.sourceUnlimitedExecutor = sourceUnlimitedExecutor;
            this.listener = listener;
        }

        @SuppressWarnings("unchecked")
        <R> EngineJob<R> build(Key key, boolean isMemoryCacheable,
                               boolean useUnlimitedSourceGeneratorPool) {
            EngineJob<R> result = (EngineJob<R>) pool.acquire();
            return result.init(key, isMemoryCacheable, useUnlimitedSourceGeneratorPool);
        }
    }
}
