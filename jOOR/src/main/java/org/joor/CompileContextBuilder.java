package org.joor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class CompileContextBuilder<T> {

    private CompileOptions compileOptions;
    private List<Compile.CharSequenceJavaFileObject> files = new ArrayList<>();
    private Function<CompileContext, T> finalizer;

    public CompileContextBuilder(Function<CompileContext, T> finalizer) {

        this.finalizer = finalizer;
    }

    public CompileContextBuilder<T> compileOptions(CompileOptions compileOptions) {
        this.compileOptions = compileOptions;
        return this;
    }

    public CompileContextBuilder<T> file(String className, String content) {
        Compile.CharSequenceJavaFileObject file = new Compile.CharSequenceJavaFileObject(className, content);
        files.add(file);
        return this;
    }

    protected CompileContext build() {
        CompileOptions ops = compileOptions == null ? new CompileOptions() : compileOptions;
        CompileContext context = new CompileContext(ops);
        for (Compile.CharSequenceJavaFileObject file : files) {
            context.addFile(file);
        }
        return context;
    }

    public T compile() {
        return finalizer.apply(build());
    }
}
