/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core.io;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of the {@link ResourceLoader} interface.
 * Used by {@link ResourceEditor}, and serves as base class for
 * {@link org.springframework.context.support.AbstractApplicationContext}.
 * Can also be used standalone.
 *
 * <p>Will return a {@link UrlResource} if the location value is a URL,
 * and a {@link ClassPathResource} if it is a non-URL path or a
 * "classpath:" pseudo-URL.
 *
 * @author Juergen Hoeller
 * @see FileSystemResourceLoader
 * @see org.springframework.context.support.ClassPathXmlApplicationContext
 * @since 10.03.2004
 */
public class DefaultResourceLoader implements ResourceLoader {

	private final Set<ProtocolResolver> protocolResolvers = new LinkedHashSet<>(4);
	private final Map<Class<?>, Map<Resource, ?>> resourceCaches = new ConcurrentHashMap<>(4);
	@Nullable
	private ClassLoader classLoader;


	/**
	 * 创建 ClassLoader，默认使用线程上线文 cl，未获取到使用 ClassUtils 类的 cl，否则使用系统 cl。
	 * <p>
	 * Create a new DefaultResourceLoader.
	 * <p>ClassLoader access will happen using the thread context class loader
	 * at the time of this ResourceLoader's initialization.
	 *
	 * @see java.lang.Thread#getContextClassLoader()
	 */
	public DefaultResourceLoader() {
		this.classLoader = ClassUtils.getDefaultClassLoader();
	}

	/**
	 * Create a new DefaultResourceLoader.
	 *
	 * @param classLoader the ClassLoader to load class path resources with, or {@code null}
	 *                    for using the thread context class loader at the time of actual resource access
	 */
	public DefaultResourceLoader(@Nullable ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * Return the ClassLoader to load class path resources with.
	 * <p>Will get passed to ClassPathResource's constructor for all
	 * ClassPathResource objects created by this resource loader.
	 *
	 * @see ClassPathResource
	 */
	@Override
	@Nullable
	public ClassLoader getClassLoader() {
		return (this.classLoader != null ? this.classLoader : ClassUtils.getDefaultClassLoader());
	}

	/**
	 * Specify the ClassLoader to load class path resources with, or {@code null}
	 * for using the thread context class loader at the time of actual resource access.
	 * <p>The default is that ClassLoader access will happen using the thread context
	 * class loader at the time of this ResourceLoader's initialization.
	 */
	public void setClassLoader(@Nullable ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * Register the given resolver with this resource loader, allowing for
	 * additional protocols to be handled.
	 * <p>Any such resolver will be invoked ahead of this loader's standard
	 * resolution rules. It may therefore also override any default rules.
	 *
	 * @see #getProtocolResolvers()
	 * @since 4.3
	 */
	public void addProtocolResolver(ProtocolResolver resolver) {
		Assert.notNull(resolver, "ProtocolResolver must not be null");
		this.protocolResolvers.add(resolver);
	}

	/**
	 * Return the collection of currently registered protocol resolvers,
	 * allowing for introspection as well as modification.
	 *
	 * @since 4.3
	 */
	public Collection<ProtocolResolver> getProtocolResolvers() {
		return this.protocolResolvers;
	}

	/**
	 * 获取资源缓存，使用一个初始容量为 4 的 ConcurrentHashMap 存储。
	 * 如果资源类型不存在，就创建一个 ConcurrentHashMap 并使用 Class 类型的资源作为 key，
	 * 新建的 ConcurrentHashMap 作为值存入缓存。
	 * <p>
	 * Obtain a cache for the given value type, keyed by {@link Resource}.
	 *
	 * @param valueType the value type, e.g. an ASM {@code MetadataReader}
	 * @return the cache {@link Map}, shared at the {@code ResourceLoader} level
	 * @since 5.0
	 */
	@SuppressWarnings("unchecked")
	public <T> Map<Resource, T> getResourceCache(Class<T> valueType) {
		return (Map<Resource, T>) this.resourceCaches.computeIfAbsent(valueType, key -> new ConcurrentHashMap<>());
	}

	/**
	 * Clear all resource caches in this resource loader.
	 *
	 * @see #getResourceCache
	 * @since 5.0
	 */
	public void clearResourceCaches() {
		this.resourceCaches.clear();
	}


	@Override
	public Resource getResource(String location) {
		Assert.notNull(location, "Location must not be null");

		// 先从注册过的 protocolResolver 中获取 resource
		for (ProtocolResolver protocolResolver : this.protocolResolvers) {
			Resource resource = protocolResolver.resolve(location, this);
			if (resource != null) {
				return resource;
			}
		}

		// 创建 resource，注意无论资源是否存在，都会创建资源对象，使用前最好使用 exits() 方法判断一下
		// 1. / 开头的，构造 ClassPathResource
		// 2. classpath: 开头的，截取后构造 ClassPathResource
		// 3. 如果是 FileURL（比如以 file: 开头） 构造 FileUrlResource，否则构造 UrlResource
		// 4. 3 抛 MalformedURLException 异常时构造 ClassPathResource
		if (location.startsWith("/")) {
			return getResourceByPath(location);
		} else if (location.startsWith(CLASSPATH_URL_PREFIX)) {
			return new ClassPathResource(location.substring(CLASSPATH_URL_PREFIX.length()), getClassLoader());
		} else {
			try {
				// Try to parse the location as a URL...
				URL url = new URL(location);
				return (ResourceUtils.isFileURL(url) ? new FileUrlResource(url) : new UrlResource(url));
			} catch (MalformedURLException ex) {
				// No URL -> resolve as resource path.
				return getResourceByPath(location);
			}
		}
	}

	/**
	 * 被子类利用返回不同子类类型资源。
	 * <p>
	 * Return a Resource handle for the resource at the given path.
	 * <p>The default implementation supports class path locations. This should
	 * be appropriate for standalone implementations but can be overridden,
	 * e.g. for implementations targeted at a Servlet container.
	 *
	 * @param path the path to the resource
	 * @return the corresponding Resource handle
	 * @see ClassPathResource
	 * @see org.springframework.context.support.FileSystemXmlApplicationContext#getResourceByPath
	 * @see org.springframework.web.context.support.XmlWebApplicationContext#getResourceByPath
	 */
	protected Resource getResourceByPath(String path) {
		return new ClassPathContextResource(path, getClassLoader());
	}


	/**
	 * 通过静态内部类继承资源类的方式返回资源对象，同时还可以通过内部类实现接口增加行为而不影响子类。
	 * <p>
	 * ClassPathResource that explicitly expresses a context-relative path
	 * through implementing the ContextResource interface.
	 */
	protected static class ClassPathContextResource extends ClassPathResource implements ContextResource {

		public ClassPathContextResource(String path, @Nullable ClassLoader classLoader) {
			super(path, classLoader);
		}

		@Override
		public String getPathWithinContext() {
			return getPath();
		}

		@Override
		public Resource createRelative(String relativePath) {
			String pathToUse = StringUtils.applyRelativePath(getPath(), relativePath);
			return new ClassPathContextResource(pathToUse, getClassLoader());
		}
	}

}
