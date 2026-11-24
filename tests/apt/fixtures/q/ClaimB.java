package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*; import javax.tools.Diagnostic;
@SupportedAnnotationTypes("p.Hello")
public class ClaimB extends AbstractProcessor {
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) {
    if (!a.isEmpty()) processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "B saw " + a.size()); return false; }
}
