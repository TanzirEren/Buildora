package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*;
@SupportedAnnotationTypes("p.Hello")
public class CrashProc extends AbstractProcessor {
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) { if (!a.isEmpty()) throw new IllegalStateException("boom in CrashProc"); return false; }
}
