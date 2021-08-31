/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.joor;

/* [java-8] */

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;


/**
 * A utility that simplifies in-memory compilation of new classes.
 *
 * @author Lukas Eder
 */
class Compile {

    static Class<?> compile(String className, String content, CompileOptions compileOptions) {
        return compile(new Context(className, content, compileOptions));
    }

    public static ContextBuilder<Class<?>> using() {
        return new ContextBuilder<Class<?>>(Compile::compile);
    }

    static Class<?> compile(Context compileContext) {
        Lookup lookup = MethodHandles.lookup();
        ClassLoader cl = lookup.lookupClass().getClassLoader();

        try {
            return cl.loadClass(compileContext.getClassName());
        }
        catch (ClassNotFoundException ignore) {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

            try {
                ClassFileManager fileManager = new ClassFileManager(compiler.getStandardFileManager(null, null, null));

                StringWriter out = new StringWriter();

                List<String> options = new ArrayList<>(compileContext.getCompileOptions().options);
                if (!options.contains("-classpath")) {
                    StringBuilder classpath = new StringBuilder();
                    String separator = System.getProperty("path.separator");
                    String cp = System.getProperty("java.class.path");
                    String mp = System.getProperty("jdk.module.path");

                    if (cp != null && !"".equals(cp))
                        classpath.append(cp);
                    if (mp != null && !"".equals(mp))
                        classpath.append(mp);

                    if (cl instanceof URLClassLoader) {
                        for (URL url : ((URLClassLoader) cl).getURLs()) {
                            if (classpath.length() > 0)
                                classpath.append(separator);

                            if ("file".equals(url.getProtocol()))
                                classpath.append(new File(url.toURI()));
                        }
                    }

                    options.addAll(Arrays.asList("-classpath", classpath.toString()));
                }

                CompilationTask task = compiler.getTask(out, fileManager, null, options, null, compileContext.getFiles());

                if (!compileContext.getCompileOptions().processors.isEmpty())
                    task.setProcessors(compileContext.getCompileOptions().processors);

                task.call();

                if (fileManager.isEmpty())
                    throw new ReflectException("Compilation error: " + out);

                Class<?> result = null;

                // This works if we have private-access to the interfaces in the class hierarchy
                if (Reflect.CACHED_LOOKUP_CONSTRUCTOR != null) {
                    result = fileManager.loadAndReturnMainClass(compileContext.getClassName(),
                        (name, bytes) -> Reflect.on(cl).call("defineClass", name, bytes, 0, bytes.length).get());
                }
                /* [java-9] */

                // Lookup.defineClass() has only been introduced in Java 9. It is
                // required to get private-access to interfaces in the class hierarchy
                else {

                    // This method is called by client code from two levels up the current stack frame
                    // We need a private-access lookup from the class in that stack frame in order to get
                    // private-access to any local interfaces at that location.
                    Class<?> caller = StackWalker
                        .getInstance(RETAIN_CLASS_REFERENCE)
                        .walk(s -> s
                            .skip(2)
                            .findFirst()
                            .get()
                            .getDeclaringClass());

                    // If the compiled class is in the same package as the caller class, then
                    // we can use the private-access Lookup of the caller class
                    if (compileContext.getClassName().startsWith(caller.getPackageName() + ".") &&

                        // [#74] This heuristic is necessary to prevent classes in subpackages of the caller to be loaded
                        //       this way, as subpackages cannot access private content in super packages.
                        //       The heuristic will work only with classes that follow standard naming conventions.
                        //       A better implementation is difficult at this point.
                        Character.isUpperCase(compileContext.getClassName().charAt(caller.getPackageName().length() + 1))) {
                        Lookup privateLookup = MethodHandles.privateLookupIn(caller, lookup);
                        result = fileManager.loadAndReturnMainClass(compileContext.getClassName(),
                            (name, bytes) -> privateLookup.defineClass(bytes));
                    }

                    // Otherwise, use an arbitrary class loader. This approach doesn't allow for
                    // loading private-access interfaces in the compiled class's type hierarchy
                    else {
                        ByteArrayClassLoader c = new ByteArrayClassLoader(fileManager.classes());
                        result = fileManager.loadAndReturnMainClass(compileContext.getClassName(),
                            (name, bytes) -> c.loadClass(name));
                    }
                }
                /* [/java-9] */

                return result;
            }
            catch (ReflectException e) {
                throw e;
            }
            catch (Exception e) {
                throw new ReflectException("Error while compiling " + compileContext.getClassName(), e);
            }
        }
    }

    /* [java-9] */
    static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        ByteArrayClassLoader(Map<String, byte[]> classes) {
            super(ByteArrayClassLoader.class.getClassLoader());

            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);

            if (bytes == null)
                return super.findClass(name);
            else
                return defineClass(name, bytes, 0, bytes.length);
        }
    }
    /* [/java-9] */

    static final class JavaFileObject extends SimpleJavaFileObject {
        final ByteArrayOutputStream os = new ByteArrayOutputStream();

        JavaFileObject(String name, JavaFileObject.Kind kind) {
            super(URI.create("string:///" + name.replace('.', '/') + kind.extension), kind);
        }

        byte[] getBytes() {
            return os.toByteArray();
        }

        @Override
        public OutputStream openOutputStream() {
            return os;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return new String(os.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class ClassFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, JavaFileObject> fileObjectMap;
        private Map<String, byte[]> classes;

        ClassFileManager(StandardJavaFileManager standardManager) {
            super(standardManager);

            fileObjectMap = new HashMap<>();
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
            JavaFileManager.Location location,
            String className,
            JavaFileObject.Kind kind,
            FileObject sibling
        ) {
            JavaFileObject result = new JavaFileObject(className, kind);
            fileObjectMap.put(className, result);
            return result;
        }

        boolean isEmpty() {
            return fileObjectMap.isEmpty();
        }

        Map<String, byte[]> classes() {
            if (classes == null) {
                classes = new HashMap<>();

                for (Entry<String, JavaFileObject> entry : fileObjectMap.entrySet())
                    classes.put(entry.getKey(), entry.getValue().getBytes());
            }

            return classes;
        }

        Class<?> loadAndReturnMainClass(String mainClassName, ThrowingBiFunction<String, byte[], Class<?>> definer) throws Exception {
            Class<?> result = null;

            for (Entry<String, byte[]> entry : classes().entrySet()) {
                Class<?> c = definer.apply(entry.getKey(), entry.getValue());
                if (mainClassName.equals(entry.getKey()))
                    result = c;
            }

            return result;
        }
    }

    @FunctionalInterface
    interface ThrowingBiFunction<T, U, R> {
        R apply(T t, U u) throws Exception;
    }

    static final class CharSequenceJavaFileObject extends SimpleJavaFileObject {
        final CharSequence content;
        private final String rawClassName;

        public CharSequenceJavaFileObject(String className, CharSequence content) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE);
            this.rawClassName = className;
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }

        public String getRawClassName() {
            return rawClassName;
        }
    }

    public static class Context {
        private String className;
        private final CompileOptions compileOptions;
        private final List<CharSequenceJavaFileObject> files = new ArrayList<>();

        public Context(CompileOptions compileOptions) {
            this.compileOptions = compileOptions;
        }

        public Context(String className, String content, CompileOptions compileOptions) {
            this(compileOptions);
            addFile(className, content);
        }

        public List<CharSequenceJavaFileObject> getFiles() {
            return files;
        }

        public void addFile(String className, String content) {
            CharSequenceJavaFileObject file = new CharSequenceJavaFileObject(className, content);
            addFile(file);
        }

        public void addFile(CharSequenceJavaFileObject file) {
            files.add(file);
            if (this.className == null) this.className = file.getRawClassName();
        }

        public String getClassName() {
            return className;
        }

        public CompileOptions getCompileOptions() {
            return compileOptions;
        }
    }

    public static class ContextBuilder<T> {

        private CompileOptions compileOptions;
        private List<CharSequenceJavaFileObject> files = new ArrayList<>();
        private Function<Context, T> finalizer;

        public ContextBuilder(Function<Context, T> finalizer) {

            this.finalizer = finalizer;
        }

        public ContextBuilder<T> compileOptions(CompileOptions compileOptions) {
            this.compileOptions = compileOptions;
            return this;
        }

        public ContextBuilder<T> file(String className, String content) {
            CharSequenceJavaFileObject file = new CharSequenceJavaFileObject(className, content);
            files.add(file);
            return this;
        }

        protected Context build() {
            CompileOptions ops = compileOptions == null ? new CompileOptions() : compileOptions;
            Context context = new Context(ops);
            for (CharSequenceJavaFileObject file : files) {
                context.addFile(file);
            }
            return context;
        }

        public T compile() {
            return finalizer.apply(build());
        }
    }
}
/* [/java-8] */
