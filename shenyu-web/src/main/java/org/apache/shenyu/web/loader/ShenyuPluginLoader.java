/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.web.loader;

import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import org.apache.shenyu.plugin.api.ShenyuPlugin;
import org.apache.shenyu.plugin.base.handler.PluginDataHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Shenyu Plugin loader.
 */
public final class ShenyuPluginLoader extends ClassLoader implements Closeable {
    
    private static final Logger LOG = LoggerFactory.getLogger(ShenyuPluginLoader.class);
    
    static {
        registerAsParallelCapable();
    }
    
    private static volatile ShenyuPluginLoader pluginLoader;
    
    private final ConcurrentHashMap<String, Object> objectPool = new ConcurrentHashMap<>();
    
    private final ReentrantLock lock = new ReentrantLock();
    
    private final List<PluginJar> jars = Lists.newArrayList();
    
    private ShenyuPluginLoader() {
        super(ShenyuPluginLoader.class.getClassLoader());
    }
    
    /**
     * Get plugin loader instance.
     *
     * @return plugin loader instance
     */
    public static ShenyuPluginLoader getInstance() {
        if (null == pluginLoader) {
            synchronized (ShenyuPluginLoader.class) {
                if (null == pluginLoader) {
                    pluginLoader = new ShenyuPluginLoader();
                }
            }
        }
        return pluginLoader;
    }
    
    /**
     * Load extend plugins list.
     *
     * @param path the path
     * @return the list
     * @throws IOException the io exception
     * @throws ClassNotFoundException the class not found exception
     * @throws InstantiationException the instantiation exception
     * @throws IllegalAccessException the illegal access exception
     */
    public List<ShenyuLoaderResult> loadExtendPlugins(final String path) throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        File[] jarFiles = ShenyuPluginPathBuilder.getPluginPath(path).listFiles(file -> file.getName().endsWith(".jar"));
        if (null == jarFiles) {
            return Collections.emptyList();
        }
        List<ShenyuLoaderResult> results = new ArrayList<>();
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (File each : jarFiles) {
                outputStream.reset();
                JarFile jar = new JarFile(each, true);
                jars.add(new PluginJar(jar, each));
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry jarEntry = entries.nextElement();
                    String entryName = jarEntry.getName();
                    if (entryName.endsWith(".class") && !entryName.contains("$")) {
                        String className = entryName.substring(0, entryName.length() - 6).replaceAll("/", ".");
                        Object instance = getOrCreateInstance(className);
                        if (Objects.nonNull(instance)) {
                            results.add(buildResult(instance));
                        }
                    }
                }
            }
        }
        return results;
    }
    
    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        String path = classNameToPath(name);
        for (PluginJar each : jars) {
            ZipEntry entry = each.jarFile.getEntry(path);
            if (Objects.nonNull(entry)) {
                try {
                    int index = name.lastIndexOf('.');
                    if (index != -1) {
                        String packageName = name.substring(0, index);
                        definePackageInternal(packageName, each.jarFile.getManifest());
                    }
                    byte[] data = ByteStreams.toByteArray(each.jarFile.getInputStream(entry));
                    return defineClass(name, data, 0, data.length);
                } catch (final IOException ex) {
                    LOG.error("Failed to load class {}.", name, ex);
                }
            }
        }
        throw new ClassNotFoundException(String.format("Class name is %s not found.", name));
    }
    
    @Override

    protected Enumeration<URL> findResources(final String name) {
        List<URL> resources = Lists.newArrayList();
        for (PluginJar each : jars) {
            JarEntry entry = each.jarFile.getJarEntry(name);
            if (Objects.nonNull(entry)) {
                try {
                    resources.add(new URL(String.format("jar:file:%s!/%s", each.sourcePath.getAbsolutePath(), name)));
                } catch (final MalformedURLException ignored) {
                }
            }
        }
        return Collections.enumeration(resources);
    }
    
    @Override
    protected URL findResource(final String name) {
        for (PluginJar each : jars) {
            JarEntry entry = each.jarFile.getJarEntry(name);
            if (Objects.nonNull(entry)) {
                try {
                    return new URL(String.format("jar:file:%s!/%s", each.sourcePath.getAbsolutePath(), name));
                } catch (final MalformedURLException ignored) {
                }
            }
        }
        return null;
    }
    
    @Override
    public void close() {
        for (PluginJar each : jars) {
            try {
                each.jarFile.close();
            } catch (final IOException ex) {
                LOG.error("close shenyu plugin jar is ", ex);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> T getOrCreateInstance(final String className) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (objectPool.containsKey(className)) {
            return (T) objectPool.get(className);
        }
        lock.lock();
        try {
            Object inst = objectPool.get(className);
            if (Objects.isNull(inst)) {
                Class<?> clazz = Class.forName(className, true, this);
                if (ShenyuPlugin.class.isAssignableFrom(clazz) || PluginDataHandler.class.isAssignableFrom(clazz)) {
                    inst = clazz.newInstance();
                    objectPool.put(className, inst);
                }
            }
            return (T) inst;
        } finally {
            lock.unlock();
        }
    }
    
    private ShenyuLoaderResult buildResult(final Object instance) {
        ShenyuLoaderResult result = new ShenyuLoaderResult();
        if (instance instanceof ShenyuPlugin) {
            result.setShenyuPlugin((ShenyuPlugin) instance);
        } else if (instance instanceof PluginDataHandler) {
            result.setPluginDataHandler((PluginDataHandler) instance);
        }
        return result;
    }
    
    private String classNameToPath(final String className) {
        return String.join("", className.replace(".", "/"), ".class");
    }
    
    private void definePackageInternal(final String packageName, final Manifest manifest) {
        if (null != getPackage(packageName)) {
            return;
        }
        Attributes attributes = manifest.getMainAttributes();
        String specTitle = attributes.getValue(Attributes.Name.SPECIFICATION_TITLE);
        String specVersion = attributes.getValue(Attributes.Name.SPECIFICATION_VERSION);
        String specVendor = attributes.getValue(Attributes.Name.SPECIFICATION_VENDOR);
        String implTitle = attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
        String implVersion = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        String implVendor = attributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
        definePackage(packageName, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, null);
    }
    
    private static class PluginJar {
        
        private final JarFile jarFile;
        
        private final File sourcePath;
    
        /**
         * Instantiates a new Plugin jar.
         *
         * @param jarFile the jar file
         * @param sourcePath the source path
         */
        PluginJar(final JarFile jarFile, final File sourcePath) {
            this.jarFile = jarFile;
            this.sourcePath = sourcePath;
        }
    }
}
