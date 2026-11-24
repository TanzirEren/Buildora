package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*; import javax.tools.Diagnostic;
@SupportedAnnotationTypes("p.Hello")
public class BadProc extends AbstractProcessor {
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) {
    for (Element e : env.getElementsAnnotatedWith(p.Hello.class)) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "careful with " + e.getSimpleName(), e);
      processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "just a note");
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "class must not be named Main", e);
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "global problem without element");
    }
    return false;
  }
}
