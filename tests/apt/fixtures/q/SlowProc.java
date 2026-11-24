package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*;
@SupportedAnnotationTypes("p.Hello")
public class SlowProc extends AbstractProcessor {
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) { if (!a.isEmpty()) { try { Thread.sleep(120000); } catch (InterruptedException e) { } } return false; }
}
