package org.bukkit.plugin.java;

import com.google.common.io.ByteStreams;

import luohuayu.CatServer.CatServer;
import luohuayu.CatServer.remapper.CatServerRemapper;
import luohuayu.CatServer.remapper.ClassInheritanceProvider;
import luohuayu.CatServer.remapper.Transformer;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.RemapperProcessor;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.md_5.specialsource.transformer.MavenShade;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.commons.lang.Validate;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.PluginDescriptionFile;

/**
 * A ClassLoader for plugins, to allow shared classes across multiple plugins
 */
final class PluginClassLoader extends URLClassLoader {
    private final JavaPluginLoader loader;
    private final Map<String, Class<?>> classes = new HashMap<String, Class<?>>();
    private final PluginDescriptionFile description;
    private final File dataFolder;
    private final File file;
    private final JarFile jar;
    private final Manifest manifest;
    private final URL url;
    final JavaPlugin plugin;
    private JavaPlugin pluginInit;
    private IllegalStateException pluginState;

    private JarRemapper remapper;
    private RemapperProcessor remapperProcessor;
    private JarMapping jarMapping;
    private static final String org_bukkit_craftbukkit = new String(new char[] {'o','r','g','/','b','u','k','k','i','t','/','c','r','a','f','t','b','u','k','k','i','t'});

    PluginClassLoader(final JavaPluginLoader loader, final ClassLoader parent, final PluginDescriptionFile description, final File dataFolder, final File file) throws IOException, InvalidPluginException, MalformedURLException {
        super(new URL[] {file.toURI().toURL()}, parent);
        Validate.notNull(loader, "Loader cannot be null");

        this.loader = loader;
        this.description = description;
        this.dataFolder = dataFolder;
        this.file = file;
        this.jar = new JarFile(file);
        this.manifest = jar.getManifest();
        this.url = file.toURI().toURL();

        jarMapping = getJarMapping();

        JointProvider provider = new JointProvider();
        provider.add(new ClassInheritanceProvider());
        provider.add(new ClassLoaderProvider(this));

        jarMapping.setFallbackInheritanceProvider(provider);
        jarMapping.setInheritanceMap(loader.getGlobalInheritanceMap(provider));

        remapper = new CatServerRemapper(jarMapping);
        remapperProcessor = new RemapperProcessor(loader.getGlobalInheritanceMap(provider), jarMapping);
        remapperProcessor.setRemapReflectField(true);
        remapperProcessor.setRemapReflectClass(true);

        Transformer.init(jarMapping, remapper);

        try {
            Class<?> jarClass;
            try {
                jarClass = Class.forName(description.getMain(), true, this);
            } catch (ClassNotFoundException ex) {
                throw new InvalidPluginException("Cannot find main class `" + description.getMain() + "'", ex);
            }

            Class<? extends JavaPlugin> pluginClass;
            try {
                pluginClass = jarClass.asSubclass(JavaPlugin.class);
            } catch (ClassCastException ex) {
                throw new InvalidPluginException("main class `" + description.getMain() + "' does not extend JavaPlugin", ex);
            }

            plugin = pluginClass.newInstance();
        } catch (IllegalAccessException ex) {
            throw new InvalidPluginException("No public constructor", ex);
        } catch (InstantiationException ex) {
            throw new InvalidPluginException("Abnormal plugin type", ex);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, true);
    }

    Class<?> findClass(String name, boolean checkGlobal) throws ClassNotFoundException {
        if (name.startsWith("net.minecraft.server."+CatServer.getNativeVersion())) {
            String remappedClass = jarMapping.classes.get(name.replaceAll("\\.", "\\/"));
            Class<?> clazz = ((net.minecraft.launchwrapper.LaunchClassLoader)MinecraftServer.getServerInst().getClass().getClassLoader()).findClass(remappedClass);
            return clazz;
        }

        if (name.startsWith("org.bukkit.")) {
            throw new ClassNotFoundException(name);
        }
        Class<?> result = classes.get(name);

        if (result == null) {
            if (checkGlobal) {
                result = loader.getClassByName(name);
            }

            if (result == null) {
                String path = name.replace('.', '/').concat(".class");
                JarEntry entry = jar.getJarEntry(path);

                if (entry != null) {
                    byte[] classBytes;

                    try (InputStream is = jar.getInputStream(entry)) {
                        classBytes = ByteStreams.toByteArray(is);
                    } catch (IOException ex) {
                        throw new ClassNotFoundException(name, ex);
                    }

                    int dot = name.lastIndexOf('.');
                    if (dot != -1) {
                        String pkgName = name.substring(0, dot);
                        if (getPackage(pkgName) == null) {
                            try {
                                if (manifest != null) {
                                    definePackage(pkgName, manifest, url);
                                } else {
                                    definePackage(pkgName, null, null, null, null, null, null, null);
                                }
                            } catch (IllegalArgumentException ex) {
                                if (getPackage(pkgName) == null) {
                                    throw new IllegalStateException("Cannot find package " + pkgName);
                                }
                            }
                        }
                    }

                    CodeSigner[] signers = entry.getCodeSigners();
                    CodeSource source = new CodeSource(url, signers);

                    result = defineClass(name, classBytes, 0, classBytes.length, source);
                }

                if (result == null) {
                    if (remapper == null) {
                        result = super.findClass(name);
                    } else {
                        result = remappedFindClass(name);
                    }
                }

                if (result != null) {
                    loader.setClass(name, result);
                }
            }

            classes.put(name, result);
        }

        return result;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            jar.close();
        }
    }

    Set<String> getClasses() {
        return classes.keySet();
    }

    synchronized void initialize(JavaPlugin javaPlugin) {
        Validate.notNull(javaPlugin, "Initializing plugin cannot be null");
        Validate.isTrue(javaPlugin.getClass().getClassLoader() == this, "Cannot initialize plugin outside of this class loader");
        if (this.plugin != null || this.pluginInit != null) {
            throw new IllegalArgumentException("Plugin already initialized!", pluginState);
        }

        pluginState = new IllegalStateException("Initial initialization");
        this.pluginInit = javaPlugin;

        javaPlugin.init(loader, loader.server, description, dataFolder, file, this);
    }
    
    
    private void loadNmsMappings(JarMapping jarMapping, String obfVersion) throws IOException {
        Map<String, String> relocations = new HashMap<String, String>();
        relocations.put("net.minecraft.server", "net.minecraft.server." + obfVersion);

        jarMapping.loadMappings(
                new BufferedReader(new InputStreamReader(loader.getClass().getClassLoader().getResourceAsStream("mappings/"+obfVersion+"/cb2numpkg.srg"))),
                new MavenShade(relocations),
                null, false);
    }

    private JarMapping getJarMapping() {
        JarMapping jarMapping = new JarMapping();
        try {
            jarMapping.packages.put(org_bukkit_craftbukkit + "/libs/com/google/gson", "com/google/gson");

            loadNmsMappings(jarMapping, CatServer.getNativeVersion());

            return jarMapping;
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
}
    
    private Class<?> remappedFindClass(String name) throws ClassNotFoundException {
        Class<?> result = null;

        try {
            // Load the resource to the name
            String path = name.replace('.', '/').concat(".class");
            URL url = this.findResource(path);
            if (url != null) {
                InputStream stream = url.openStream();
                if (stream != null) {
                    byte[] bytecode = null;

                    // Reflection remap and inheritance extract
                    if (remapperProcessor != null) {
                        // add to inheritance map
                        bytecode = remapperProcessor.process(stream);
                        if (bytecode == null) stream = url.openStream();
                    }

                    // Remap the classes
                    byte[] remappedBytecode = Transformer.transformSS(remapper, bytecode);

                    // Define (create) the class using the modified byte code
                    // The top-child class loader is used for this to prevent access violations
                    // Set the codesource to the jar, not within the jar, for compatibility with
                    // plugins that do new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()))
                    // instead of using getResourceAsStream - see https://github.com/MinecraftPortCentral/Cauldron-Plus/issues/75
                    JarURLConnection jarURLConnection = (JarURLConnection) url.openConnection(); // parses only
                    URL jarURL = jarURLConnection.getJarFileURL();
                    CodeSource codeSource = new CodeSource(jarURL, new CodeSigner[0]);

                    result = this.defineClass(name, remappedBytecode, 0, remappedBytecode.length, codeSource);
                    if (result != null) {
                        // Resolve it - sets the class loader of the class
                        this.resolveClass(result);
                    }
                }
            }
        } catch (Throwable t) {
            throw new ClassNotFoundException("Failed to remap class "+name, t);
        }

        return result;
    }
}
