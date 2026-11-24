package q;
import java.util.*; import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*; import javax.tools.Diagnostic;
@SupportedAnnotationTypes("p.Hello") @SupportedOptions({"my.option"})
public class OptProc extends AbstractProcessor {
  boolean done;
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) {
    if (!done && !a.isEmpty()) { done = true; processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "option my.option=" + processingEnv.getOptions().get("my.option")
      + " source=" + processingEnv.getSourceVersion()); } return false; }
}
