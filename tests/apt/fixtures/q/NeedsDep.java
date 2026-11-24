package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*;
@SupportedAnnotationTypes("p.Hello")
public class NeedsDep extends AbstractProcessor {
  private final String s = missing.Dep.v();
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) { return false; }
}
