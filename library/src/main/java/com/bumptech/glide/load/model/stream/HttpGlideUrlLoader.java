package com.bumptech.glide.load.model.stream;

import android.support.annotation.Nullable;

import com.bumptech.glide.load.Option;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.HttpUrlFetcher;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelCache;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;

import java.io.InputStream;

/**
 * An {@link com.bumptech.glide.load.model.ModelLoader} for translating {@link
 * com.bumptech.glide.load.model.GlideUrl} (http/https URLS) into {@link java.io.InputStream} data.
 */
public class HttpGlideUrlLoader implements ModelLoader<GlideUrl, InputStream> {
    /**
     * An integer option that is used to determine the maximum connect and read timeout durations (in
     * milliseconds) for network connections.
     * <p>
     * <p>Defaults to 2500ms.
     */
    public static final Option<Integer> TIMEOUT = Option.memory(
            "com.bumptech.glide.load.model.stream.HttpGlideUrlLoader.Timeout", 2500);

    @Nullable
    private final ModelCache<GlideUrl, GlideUrl> modelCache;

    public HttpGlideUrlLoader() {
        this(null);
    }

    public HttpGlideUrlLoader(ModelCache<GlideUrl, GlideUrl> modelCache) {
        this.modelCache = modelCache;
    }

    @Override
    public LoadData<InputStream> buildLoadData(GlideUrl model, int width, int height,
                                               Options options) {
        // GlideUrls memoize parsed URLs so caching them saves a few object instantiations and time
        // spent parsing urls.
        GlideUrl url = model;
        if (modelCache != null) {// 缓存地址 节省解析时间
            url = modelCache.get(model, 0, 0);
            if (url == null) {
                modelCache.put(model, 0, 0, model);
                url = model;
            }
        }
        int timeout = options.get(TIMEOUT);//请求时间
        return new LoadData<>(url, new HttpUrlFetcher(url, timeout));
    }

    @Override
    public boolean handles(GlideUrl model) {
        return true;
    }

    /**
     * The default factory for {@link HttpGlideUrlLoader}s.
     */
    public static class Factory implements ModelLoaderFactory<GlideUrl, InputStream> {
        private final ModelCache<GlideUrl, GlideUrl> modelCache = new ModelCache<>(500);

        @Override
        public ModelLoader<GlideUrl, InputStream> build(MultiModelLoaderFactory multiFactory) {
            return new HttpGlideUrlLoader(modelCache);
        }

        @Override
        public void teardown() {
            // Do nothing.
        }
    }
}
