package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*; import javax.tools.Diagnostic;
@SupportedAnnotationTypes("*")
public class StarProc extends AbstractProcessor {
  int n;
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) {
    n++; processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "star call " + n + " annos=" + a.size() + " over=" + env.processingOver()); return false; }
}
