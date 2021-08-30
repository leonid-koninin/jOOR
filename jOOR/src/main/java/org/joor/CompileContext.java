package org.joor;

import java.util.ArrayList;
import java.util.List;

public class CompileContext {
    private String className;
    private final CompileOptions compileOptions;
    private final List<Compile.CharSequenceJavaFileObject> files = new ArrayList<>();

    public CompileContext(CompileOptions compileOptions) {
        this.compileOptions = compileOptions;
    }

    public CompileContext(String className, String content, CompileOptions compileOptions) {
        this(compileOptions);
        addFile(className, content);
    }

    public List<Compile.CharSequenceJavaFileObject> getFiles() {
        return files;
    }

    public void addFile(String className, String content) {
        Compile.CharSequenceJavaFileObject file = new Compile.CharSequenceJavaFileObject(className, content);
        addFile(file);
    }

    public void addFile(Compile.CharSequenceJavaFileObject file) {
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
