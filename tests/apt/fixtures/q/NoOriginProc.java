package q;
import java.io.*; import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*; import javax.tools.*;
@SupportedAnnotationTypes("p.Hello")
public class NoOriginProc extends AbstractProcessor {
  boolean done;
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) {
    if (done || a.isEmpty()) return false; done = true;
    try (Writer w = processingEnv.getFiler().createSourceFile("demo.NoOrigin").openWriter()) { w.write("package demo; public class NoOrigin { }"); } catch (IOException e) { throw new RuntimeException(e); }
    return false; }
}
