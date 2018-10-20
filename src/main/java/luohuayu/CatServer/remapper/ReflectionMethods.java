package luohuayu.CatServer.remapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import luohuayu.CatServer.CatServer;

public class ReflectionMethods {
    // Class.forName
    public static Class<?> forName(String className) throws ClassNotFoundException {
        return forName(className, true, ReflectionUtils.getCallerClassloader());
    }

    public static Class<?> forName(String className, boolean initialize, ClassLoader classLoader) throws ClassNotFoundException {
        if (!className.startsWith("net.minecraft.server." + CatServer.getNativeVersion())) return Class.forName(className, initialize, classLoader);
        className = ReflectionTransformer.jarMapping.classes.get(className.replace('.', '/')).replace('/', '.');
        return Class.forName(className, initialize, classLoader);
    }

    // Get Fields
    public static Field getField(Class<?> inst, String name) throws NoSuchFieldException, SecurityException {
        if (!inst.getName().startsWith("net.minecraft.")) return inst.getField(name);
        return inst.getField(ReflectionTransformer.remapper.mapFieldName(RemapUtils.reverseMap(inst), name, null));
    }

    public static Field getDeclaredField(Class<?> inst, String name) throws NoSuchFieldException, SecurityException {
        if (!inst.getName().startsWith("net.minecraft.")) return inst.getDeclaredField(name);
        return inst.getDeclaredField(ReflectionTransformer.remapper.mapFieldName(RemapUtils.reverseMap(inst), name, null));
    }

    // Get Methods
    public static Method getMethod(Class<?> inst, String name, Class<?>...parameterTypes) throws NoSuchMethodException, SecurityException {
        if (!inst.getName().startsWith("net.minecraft.")) return inst.getMethod(name, parameterTypes);
        return inst.getMethod(RemapUtils.mapMethod(inst, name, parameterTypes), parameterTypes);
    }

    public static Method getDeclaredMethod(Class<?> inst, String name, Class<?>...parameterTypes) throws NoSuchMethodException, SecurityException {
        if (!inst.getName().startsWith("net.minecraft.")) return inst.getDeclaredMethod(name, parameterTypes);
        return inst.getDeclaredMethod(RemapUtils.mapMethod(inst, name, parameterTypes), parameterTypes);
    }

    // getName
    public static String getName(Field field) {
        if (!field.getDeclaringClass().getName().startsWith("net.minecraft.")) return field.getName();
        return RemapUtils.demapFieldName(field);
    }

    public static String getName(Method method) {
        if (!method.getDeclaringClass().getName().startsWith("net.minecraft.")) return method.getName();
        return RemapUtils.demapMethodName(method);
    }

    // getSimpleName
    public static String getSimpleName(Class<?> inst) {
        if (!inst.getName().startsWith("net.minecraft.")) return inst.getSimpleName();
        String[] name = RemapUtils.reverseMapExternal(inst).split("\\.");
        return name[name.length - 1];
    }

    // ClassLoader.loadClass
    public static Class<?> loadClass(String pClazzName) throws ClassNotFoundException {
        return loadClass((ClassLoader)null, pClazzName);
    }
    
    public static Class<?> loadClass(ClassLoader pLoader, String pClazzName) throws ClassNotFoundException {
        if (pClazzName.startsWith("net.minecraft."))
            pClazzName = RemapUtils.mapClass(pClazzName.replace('.', '/')).replace('/', '.');
        return pLoader == null ? Class.forName(pClazzName) : pLoader.loadClass(pClazzName);
    }
}
